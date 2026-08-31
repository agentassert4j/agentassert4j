package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.model.ArchivedTemplateVersion;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import io.github.agentassert4j.util.RecursiveJsonParser;

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
            repository = CliSupport.openRepository(db, out);
            List<InvocationProfile> profiles = repository.findAllInvocations();
            Map<String, String> labelsByInvocationKey = businessLabelsByInvocationKey(repository);

            out.println("invocationKey                                              状态       版本   候选  归档版本      业务标签");
            for (InvocationProfile profile : profiles) {
                String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                out.printf("  %-50s %-9s %-6s %-4s %-12s %s%n", profile.getInvocationKey(), String.valueOf(profile.getBaselineStatus()), String.valueOf(profile.getVersionTag()), profile.getCandidateFingerprint() != null ? "有" : "-", archivedTags.isEmpty() ? "-" : archivedTags, labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), "-"));
                printTemplateText(repository, profile.getInvocationKey());
                if (diff) {
                    printCandidateDiff(profile);
                }
            }

            List<String> uncovered = uncoveredBusinessTags(repository, profiles);
            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (InvocationProfile profile : profiles) {
                    if (invocations.length() > 0) invocations.append(",");
                    String archivedTags = archivedVersionTags(repository, profile.getInvocationKey());
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(profile.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(labelsByInvocationKey.getOrDefault(profile.getInvocationKey(), ""))).append("\",\"status\":\"").append(profile.getBaselineStatus()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(profile.getVersionTag() != null ? profile.getVersionTag() : "")).append("\",\"hasCandidate\":").append(profile.getCandidateFingerprint() != null).append(",\"archivedVersions\":\"").append(RecursiveJsonParser.escape(archivedTags)).append("\"}");
                }
                out.println("{\"schema\":\"agentassert4j.status/1\",\"invocations\":[" + invocations + "],\"uncovered\":" + uncovered.size() + "}");
                return 0;
            }
            for (String tag : uncovered) {
                out.println("  " + tag + ": 已录制但无基线（先执行 baseline）");
            }
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
     * 模板文本巡检：chat 类基线的模板原文在录制时随记录归档进 prompt_texts
     * （以 templateHash 为键），这里回放给审阅者——审批面对的模板一目了然。
     * 文本缺席（老数据或 userInput 锚点的会话）静默跳过，不阻断巡检。
     */
    private void printTemplateText(StorageRepository repository, String invocationKey) {
        if (invocationKey == null || !invocationKey.startsWith("chat:")) {
            return;
        }
        String text;
        try {
            text = repository.findTemplateText(invocationKey.substring("chat:".length()));
        } catch (RuntimeException e) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        out.println("      └ 模板原文：");
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            out.println("        " + line);
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
