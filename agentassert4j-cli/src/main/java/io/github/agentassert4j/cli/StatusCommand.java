package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DriftDetector;
import io.github.agentassert4j.algorithm.TaskAligner;
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
@Command(name = "status", aliases = {"s"}, description = "Show recorded invocations and baseline status", mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--diff"}, description = "Render per-dimension candidate vs baseline diffs for invocations holding candidate fingerprints")
    boolean diff;

    @Option(names = {"--invocation"}, description = "Narrow the human view to one invocation: business label, invocationKey prefix, or the status display form (one label shows all its template-version buckets; the --json report is always full)")
    String invocation;

    @Option(names = {"--json"}, description = "Print a single-line JSON inspection report to stdout (agentassert4j.status/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr，人类巡检表不输出
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<InvocationProfile> allProfiles = repository.findAllInvocations();
            List<InvocationProfile> profiles = allProfiles;
            Map<String, String> labelsByInvocationKey = businessLabelsByInvocationKey(repository);
            Map<String, TemplateDriftState> driftByInvocationKey = templateDriftByInvocationKey(repository, allProfiles);
            // 缩域是人读巡检特性：--json 通道恒全量（机器消费方自行过滤），换算只在人读路径发生
            String labelFilter = jsonOutput ? null : CliSupport.resolveInvocationFilter(repository, invocation, out);
            int totalCount = allProfiles.size();
            if (labelFilter != null) {
                profiles = new ArrayList<>();
                for (InvocationProfile profile : allProfiles) {
                    if (matchesFilter(profile, labelFilter)) {
                        profiles.add(profile);
                    }
                }
            }

            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (InvocationProfile profile : profiles) {
                    if (invocations.length() > 0) invocations.append(",");
                    String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(profile.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), ""))).append("\",\"status\":\"").append(profile.getBaselineStatus()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(profile.getVersionTag() != null ? profile.getVersionTag() : "")).append("\",\"hasCandidate\":").append(profile.getCandidateFingerprint() != null).append(",\"templateDrift\":\"").append(driftByInvocationKey.getOrDefault(profile.getInvocationKey(), TemplateDriftState.NONE).wireName()).append("\",\"archivedVersions\":\"").append(RecursiveJsonParser.escape(archivedTags)).append("\"}");
                }
                StringBuilder uncoveredJson = new StringBuilder();
                for (String tag : uncoveredBusinessTags(repository, profiles)) {
                    if (uncoveredJson.length() > 0) uncoveredJson.append(",");
                    uncoveredJson.append("\"").append(RecursiveJsonParser.escape(tag)).append("\"");
                }
                StringBuilder unestablishedJson = new StringBuilder();
                for (InvocationFootprint footprint : unestablishedFootprints(repository, profiles)) {
                    if (unestablishedJson.length() > 0) unestablishedJson.append(",");
                    unestablishedJson.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(footprint.invocationKey)).append("\",\"recordCount\":").append(footprint.recordCount).append("}");
                }
                out.println("{\"schema\":\"agentassert4j.status/1\",\"invocations\":[" + invocations + "],\"uncovered\":[" + uncoveredJson + "],\"unestablished\":[" + unestablishedJson + "]}");
                return 0;
            }

            out.printf("  %-50s %-9s %-6s %-4s %-5s %-12s %s%n", "invocationKey", "status", "ver", "cand", "drift", "archived", "label");
            for (InvocationProfile profile : profiles) {
                String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                out.printf("  %-50s %-9s %-6s %-4s %-5s %-12s %s%n", CliSupport.displayKey(profile.getInvocationKey()), String.valueOf(profile.getBaselineStatus()), String.valueOf(profile.getVersionTag()), profile.getCandidateFingerprint() != null ? "yes" : "-", driftByInvocationKey.getOrDefault(profile.getInvocationKey(), TemplateDriftState.NONE).symbol(), archivedTags.isEmpty() ? "-" : archivedTags, labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), "-"));
                printTemplateText(repository, profile);
                if (diff) {
                    printCandidateDiff(profile);
                }
            }

            if (labelFilter != null && profiles.isEmpty()) {
                out.println("No invocation matches '" + CliSupport.visibleText(labelFilter) + "'. Drop --invocation for the full list.");
            }
            List<String> uncovered = uncoveredBusinessTags(repository, profiles);
            for (String tag : uncovered) {
                if (labelFilter != null && !labelFilter.equals(tag)) {
                    continue;
                }
                out.println("  " + tag + ": recorded but no baseline (run `agentassert4j baseline` first)");
            }
            printUnestablished(repository, labelFilter);
            if (labelFilter != null) {
                out.println("Total: " + profiles.size() + " of " + totalCount + " invocation profiles (narrowed by --invocation).");
            } else {
                out.println("Total: " + CliSupport.plural(profiles.size(), "invocation profile") + ".");
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "status failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 缩域过滤谓词：声明标签从键内解析（一标签覆盖其全部模板桶），键前缀兜底
     * （零声明键无标签可依）。解析层已把显示短形/键前缀换算为业务标签，这里只做最终匹配。
     */
    private static boolean matchesFilter(InvocationProfile profile, String labelFilter) {
        String key = profile.getInvocationKey();
        return labelFilter.equals(TaskAligner.declaredLabelOfKey(key)) || key.startsWith(labelFilter);
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
        out.println("      └ candidate diff (baseline → candidate):");
        for (String line : FingerprintDiffRenderer.render(profile.getFingerprint(), profile.getCandidateFingerprint())) {
            out.println("        " + line);
        }
    }

    /**
     * 画像模板身份的漂移三态：与 replay 共用同一检测器单一真源，巡检不跑 replay
     * 就能看见「哪里漂了」。
     */
    private static Map<String, TemplateDriftState> templateDriftByInvocationKey(StorageRepository repository, List<InvocationProfile> profiles) {
        DriftReport drift = DriftDetector.detect(repository);
        Map<String, TemplateDriftState> result = new HashMap<>();
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            result.put(point.getInvocationKey(), TemplateDriftState.DRIFTED);
        }
        for (String key : drift.getZeroTemplateKeys()) {
            result.put(key, TemplateDriftState.NONE);
        }
        for (InvocationProfile profile : profiles) {
            result.putIfAbsent(profile.getInvocationKey(), TemplateDriftState.CLEAN);
        }
        return result;
    }

    /**
     * 画像模板身份三态 — status/1 templateDrift 字段与人读漂移列共用的封闭词表
     * （wire 值冻结；符号仅人读列渲染）。
     */
    private enum TemplateDriftState {
        CLEAN("clean", "●"), DRIFTED("drifted", "▲"), NONE("none", "-");

        private final String wireName;
        private final String symbol;

        TemplateDriftState(String wireName, String symbol) {
            this.wireName = wireName;
            this.symbol = symbol;
        }

        String wireName() {
            return wireName;
        }

        String symbol() {
            return symbol;
        }
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
        out.println("      └ template text (" + hash.substring(0, Math.min(8, hash.length())) + "):");
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int shown = Math.min(lines.length, 20);
        for (int i = 0; i < shown; i++) {
            out.println("        " + lines[i]);
        }
        if (lines.length > shown) {
            out.println("        ... (" + CliSupport.plural(lines.length - shown, "more line") + " omitted)");
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
    private void printUnestablished(StorageRepository repository, String labelFilter) {
        // established 判定必须用全量画像（缩域后的子集会把已建档键误判为未建档）
        List<InvocationFootprint> unestablished = unestablishedFootprints(repository, repository.findAllInvocations());
        if (labelFilter != null) {
            List<InvocationFootprint> filtered = new ArrayList<>();
            for (InvocationFootprint footprint : unestablished) {
                if (labelFilter.equals(TaskAligner.declaredLabelOfKey(footprint.invocationKey)) || footprint.invocationKey.startsWith(labelFilter)) {
                    filtered.add(footprint);
                }
            }
            unestablished = filtered;
        }
        if (unestablished.isEmpty()) {
            out.println("Unestablished invocations: none.");
            return;
        }
        out.println("Unestablished invocations (run `agentassert4j baseline` to collect):");
        for (InvocationFootprint footprint : unestablished) {
            out.println("  " + CliSupport.displayKey(footprint.invocationKey) + " (" + (footprint.label != null ? footprint.label : "no label") + ") " + CliSupport.plural(footprint.recordCount, "record") + ", latest session " + footprint.lastSessionId);
        }
    }

    private static List<InvocationFootprint> unestablishedFootprints(StorageRepository repository, List<InvocationProfile> profiles) {
        Set<String> established = new HashSet<>();
        for (InvocationProfile profile : profiles) {
            established.add(profile.getInvocationKey());
        }
        List<InvocationFootprint> unestablished = new ArrayList<>();
        for (InvocationFootprint footprint : CliSupport.recordedInvocationFootprints(repository)) {
            if (!established.contains(footprint.invocationKey)) {
                unestablished.add(footprint);
            }
        }
        return unestablished;
    }

}
