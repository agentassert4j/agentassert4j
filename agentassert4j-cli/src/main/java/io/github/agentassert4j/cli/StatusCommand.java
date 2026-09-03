package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DriftDetector;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.model.ArchivedTemplateVersion;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.result.DriftReport;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * status 命令 — 查看已录制调用点与基线状态（裁决前后的巡检入口）。
 *
 * <p>invocationKey 是 调用点 的稳定标识（分组器确定性产出），approve/reject 的
 * --invocation 以它（或其唯一前缀）为目标。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "status", description = "查看已录制调用点与基线状态", mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--diff"}, description = "对存在候选指纹的 调用点 渲染候选与基线的逐维差异")
    boolean diff;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 巡检报告（agentassert4j.status/1）")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr，人类巡检表不输出
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<InvocationProfile> profiles = repository.findAllInvocations();
            Map<String, String> labelsByInvocationKey = businessLabelsByInvocationKey(repository);
            Map<String, String> driftByInvocationKey = templateDriftByInvocationKey(repository, profiles);

            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (InvocationProfile profile : profiles) {
                    if (invocations.length() > 0) invocations.append(",");
                    String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(profile.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), ""))).append("\",\"status\":\"").append(profile.getBaselineStatus()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(profile.getVersionTag() != null ? profile.getVersionTag() : "")).append("\",\"hasCandidate\":").append(profile.getCandidateFingerprint() != null).append(",\"templateDrift\":\"").append(driftByInvocationKey.getOrDefault(profile.getInvocationKey(), "none")).append("\",\"archivedVersions\":\"").append(RecursiveJsonParser.escape(archivedTags)).append("\"}");
                }
                StringBuilder uncoveredJson = new StringBuilder();
                for (String tag : uncoveredBusinessTags(repository, profiles)) {
                    if (uncoveredJson.length() > 0) uncoveredJson.append(",");
                    uncoveredJson.append("\"").append(RecursiveJsonParser.escape(tag)).append("\"");
                }
                StringBuilder unestablishedJson = new StringBuilder();
                for (CliSupport.InvocationFootprint footprint : unestablishedFootprints(repository, profiles)) {
                    if (unestablishedJson.length() > 0) unestablishedJson.append(",");
                    unestablishedJson.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(footprint.invocationKey)).append("\",\"recordCount\":").append(footprint.recordCount).append("}");
                }
                out.println("{\"schema\":\"agentassert4j.status/1\",\"invocations\":[" + invocations + "],\"uncovered\":[" + uncoveredJson + "],\"unestablished\":[" + unestablishedJson + "]}");
                return 0;
            }

            out.println("invocationKey                                              状态       版本   候选  漂移  归档版本      业务标签");
            for (InvocationProfile profile : profiles) {
                String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                out.printf("  %-50s %-9s %-6s %-4s %-4s %-12s %s%n", CliSupport.displayKey(profile.getInvocationKey()), String.valueOf(profile.getBaselineStatus()), String.valueOf(profile.getVersionTag()), profile.getCandidateFingerprint() != null ? "有" : "-", driftSymbol(driftByInvocationKey.get(profile.getInvocationKey())), archivedTags.isEmpty() ? "-" : archivedTags, labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), "-"));
                printTemplateText(repository, profile);
                if (diff) {
                    printCandidateDiff(profile);
                }
            }

            List<String> uncovered = uncoveredBusinessTags(repository, profiles);
            for (String tag : uncovered) {
                out.println("  " + tag + ": 已录制但无基线（先执行 baseline）");
            }
            printUnestablished(repository, profiles);
            out.println("共 " + profiles.size() + " 个基线画像。");
            printGraphSnapshot(repository);
            return 0;
        } catch (RuntimeException e) {
            err.println("status 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * invocationKey → 业务标签（逗号连接）。业务标签是用户代码里的标识，
     * invocationKey 是分组器派生键——两套体系的对照必须就地可见，
     * 否则用户对着自己的代码认不出哪行是哪个 调用点。
     */
    private static Map<String, String> businessLabelsByInvocationKey(StorageRepository repository) {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        for (String invocationId : CliSupport.recordedInvocationIds(repository)) {
            String invocationKey = new BaselineService(repository).invocationKeyOfFirstRecord(invocationId);
            if (invocationKey == null) {
                continue;
            }
            List<String> labels = mapping.get(invocationKey);
            if (labels == null) {
                labels = new ArrayList<>();
                mapping.put(invocationKey, labels);
            }
            labels.add(invocationId);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : mapping.entrySet()) {
            result.put(entry.getKey(), String.join(",", entry.getValue()));
        }
        return result;
    }

    /**
     * 归档版本标签列表（最近归档在前）——rollback --version 的可选值来源。
     */
    private static String archivedVersionTags(StorageRepository repository, String invocationKey) {
        try {
            List<String> tags = new ArrayList<>();
            for (ArchivedTemplateVersion archived : repository.findArchivedVersions(invocationKey)) {
                tags.add(archived.getVersionTag());
            }
            return String.join(",", tags);
        } catch (RuntimeException e) {
            // 归档查询失败不阻断巡检
            return "-";
        }
    }

    /**
     * 候选差异渲染（与 approve/reject 输出同源）——巡检时预览裁决证据。
     */
    private void printCandidateDiff(InvocationProfile profile) {
        if (profile.getCandidateFingerprint() == null) {
            return;
        }
        out.println("      └ 候选差异（基线 → 候选）:");
        for (String line : FingerprintDiffRenderer.render(profile.getFingerprint(), profile.getCandidateFingerprint())) {
            out.println("        " + line);
        }
    }

    /**
     * 画像模板身份的漂移三态（● 一致 / ▲ 漂移 / - 无身份）：与 replay 共用同一
     * 检测器单一真源，巡检不跑 replay 就能看见「哪里漂了」。检测与下游扩散无关，
     * 不依赖图快照。
     */
    private static Map<String, String> templateDriftByInvocationKey(StorageRepository repository, List<InvocationProfile> profiles) {
        DriftReport drift = DriftDetector.detect(repository, null);
        Map<String, String> result = new HashMap<>();
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            result.put(point.getInvocationKey(), "drifted");
        }
        for (String key : drift.getZeroTemplateKeys()) {
            result.put(key, "none");
        }
        for (InvocationProfile profile : profiles) {
            result.putIfAbsent(profile.getInvocationKey(), "clean");
        }
        return result;
    }

    private static String driftSymbol(String driftStatus) {
        if ("drifted".equals(driftStatus)) {
            return "▲";
        }
        return "clean".equals(driftStatus) ? "●" : "-";
    }

    /**
     * 模板原文巡检：按画像模板哈希反查 prompt_texts 归档原文，随 --diff 渲染——
     * 与候选差异同属审阅证据，缺省巡检不渲染（百画像×长模板全量输出不可读）。
     * 行数超限截断；原文缺席（无模板身份的调用点）静默跳过。
     */
    private void printTemplateText(StorageRepository repository, InvocationProfile profile) {
        if (!diff) {
            return;
        }
        String hash = profile.getTemplateHash();
        if (hash == null || hash.isEmpty()) {
            return;
        }
        String text;
        try {
            text = repository.findTemplateText(hash);
        } catch (RuntimeException e) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        out.println("      └ 模板原文（" + hash.substring(0, Math.min(8, hash.length())) + "）:");
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int shown = Math.min(lines.length, 20);
        for (int i = 0; i < shown; i++) {
            out.println("        " + lines[i]);
        }
        if (lines.length > shown) {
            out.println("        …（其余 " + (lines.length - shown) + " 行省略）");
        }
    }

    /**
     * 已录制业务标签中尚无对应基线画像的（记录标签 → 分组 → 画像缺失）。
     */
    private static List<String> uncoveredBusinessTags(StorageRepository repository, List<InvocationProfile> profiles) {
        List<String> uncovered = new ArrayList<>();
        for (String invocationId : CliSupport.recordedInvocationIds(repository)) {
            String invocationKey = new BaselineService(repository).invocationKeyOfFirstRecord(invocationId);
            if (invocationKey == null) {
                continue;
            }
            boolean covered = false;
            for (InvocationProfile profile : profiles) {
                if (invocationKey.equals(profile.getInvocationKey())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(invocationId);
            }
        }
        return uncovered;
    }

    /**
     * 已录制但尚无基线画像的调用点段：新版本键与零声明键在建档前在此可见，
     * 否则它们只会在对齐报告的缺/新增步骤里被动暴露。
     */
    private void printUnestablished(StorageRepository repository, List<InvocationProfile> profiles) {
        List<CliSupport.InvocationFootprint> unestablished = unestablishedFootprints(repository, profiles);
        if (unestablished.isEmpty()) {
            out.println("未建档调用点：无。");
            return;
        }
        out.println("未建档调用点（重跑 baseline 收编）：");
        for (CliSupport.InvocationFootprint footprint : unestablished) {
            out.println("  " + CliSupport.displayKey(footprint.invocationKey) + "（" + (footprint.label != null ? footprint.label : "无标签") + "）记录 " + footprint.recordCount + " 条，最近会话 " + footprint.lastSessionId);
        }
    }

    private static List<CliSupport.InvocationFootprint> unestablishedFootprints(StorageRepository repository, List<InvocationProfile> profiles) {
        Set<String> established = new HashSet<>();
        for (InvocationProfile profile : profiles) {
            established.add(profile.getInvocationKey());
        }
        List<CliSupport.InvocationFootprint> unestablished = new ArrayList<>();
        for (CliSupport.InvocationFootprint footprint : CliSupport.recordedInvocationFootprints(repository)) {
            if (!established.contains(footprint.invocationKey)) {
                unestablished.add(footprint);
            }
        }
        return unestablished;
    }

    /**
     * 依赖图快照巡检：快照是最近一次 replay 的分析视图留档（本命令只读不重建，
     * 看实时图用 graph show）。
     */
    private void printGraphSnapshot(StorageRepository repository) {
        String json = null;
        try {
            json = repository.loadGraph();
        } catch (RuntimeException e) {
            // 快照缺席不阻断状态巡检
        }
        if (json == null || json.trim().isEmpty()) {
            out.println("依赖图：无快照（执行 replay 后生成；实时视图用 graph show）。");
            return;
        }
        InMemoryDependencyGraph graph = InMemoryDependencyGraph.fromJson(json);
        out.println("依赖图快照：" + graph.nodeCount() + " 节点 / " + graph.edgeCount() + " 边（最近一次 replay 生成；实时视图用 graph show）");
    }
}
