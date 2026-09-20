package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.GraphBuildStats;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.model.GraphEdge;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * graph show 命令 — 现场重建值溯源图并渲染（只读，不写任何文件）。
 *
 * <p>图是派生数据：本命令每次从交互记录重建，永远反映最新录制状态。
 * HIGH 边 = 会话内值溯源（工具参数值回溯到任一更早记录的输出，携带命中值与源/目标记录对）；
 * LOW 边 = 相邻对的字段名前缀提示（不携带证据）。人读输出中节点/边走 displayKey 短形，
 * 完整键在图例逐字可寻址；JSON 面保持完整键（机器契约不变）。
 * 节点集 = 已录制的全部调用点键（含未参与任何边的），空边不再等于空图；
 * 出边的三前提在空态输出就地列明，扫描统计（会话/记录/键/跨键对）区分
 * 「没数据」与「数据在、无边」。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "show", description = "Rebuild and inspect the value-flow provenance graph (nodes/edges/evidence/cycles)", mixinStandardHelpOptions = true)
public class GraphShowCommand implements Callable<Integer> {

    // 证据值人读截断预算：走 abbreviateText 唯一定义处（空白折叠 + 截断 + ASCII 省略号），
    // JSON 叶子值可含换行，裸截断会破坏单行报告格式
    private static final int EVIDENCE_DISPLAY_BUDGET = 40;

    // 出边三前提的机器面表述（JSON note 与人读 Note 同语义，两处共用本常量防漂移）
    private static final String EDGE_CONDITIONS_JSON = "an edge needs all three: the two records carry different invocation identities (declare per-step invocation labels); the upstream value appears in a tool result (recorded alongside its call, or carried by an earlier record's request history); a later tool call's argument value equals that upstream value exactly";

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：人类渲染不输出（配置披露恒走 err）
            repository = CliSupport.openRepository(db, err);
            CliSupport.GraphRebuild rebuild = CliSupport.rebuildGraph(repository);
            InMemoryDependencyGraph graph = rebuild.graph;
            GraphBuildStats stats = rebuild.stats;

            Set<String> nodes = new TreeSet<>(graph.getAllNodes());
            List<GraphEdge> edges = new ArrayList<>(graph.getAllEdges());
            edges.sort((a, b) -> (a.getSource() + ">" + a.getTarget()).compareTo(b.getSource() + ">" + b.getTarget()));

            if (jsonOutput) {
                out.println(graphJson(nodes, edges, graph, stats));
                return 0;
            }

            renderHuman(nodes, edges, graph, stats);
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "graph show failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private String graphJson(Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph, GraphBuildStats stats) {
        StringBuilder edgeJson = new StringBuilder();
        for (GraphEdge edge : edges) {
            if (edgeJson.length() > 0) edgeJson.append(",");
            edgeJson.append("{\"source\":\"").append(RecursiveJsonParser.escape(edge.getSource()))
                    .append("\",\"target\":\"").append(RecursiveJsonParser.escape(edge.getTarget()))
                    .append("\",\"confidence\":\"").append(edge.getConfidence()).append("\"");
            if (edge.getEvidenceValue() != null) {
                edgeJson.append(",\"evidence\":{\"value\":").append(jsonStringOrNull(edge.getEvidenceValue()))
                        .append(",\"sourceRecordId\":").append(jsonStringOrNull(edge.getEvidenceSourceRecordId()))
                        .append(",\"targetRecordId\":").append(jsonStringOrNull(edge.getEvidenceTargetRecordId()))
                        .append("}");
            }
            edgeJson.append("}");
        }
        StringBuilder cyclesJson = new StringBuilder();
        for (String node : new TreeSet<>(graph.detectCycles())) {
            if (cyclesJson.length() > 0) cyclesJson.append(",");
            cyclesJson.append("\"").append(RecursiveJsonParser.escape(node)).append("\"");
        }
        return "{\"schema\":\"" + ReportSchemas.GRAPH + "\",\"nodeCount\":" + nodes.size() + ",\"edgeCount\":" + edges.size()
                + ",\"edges\":[" + edgeJson + "],\"cycles\":[" + cyclesJson + "]"
                + ",\"scanned\":{\"sessions\":" + stats.getSessions() + ",\"records\":" + stats.getRecords()
                + ",\"invocationKeys\":" + stats.getInvocationKeys() + ",\"candidatePairs\":" + stats.getCandidatePairs() + "}"
                + (edges.isEmpty() ? ",\"note\":\"" + EDGE_CONDITIONS_JSON + "\"" : "") + "}";
    }

    private void renderHuman(Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph, GraphBuildStats stats) {
        StringBuilder nodeLine = new StringBuilder();
        for (String node : nodes) {
            if (nodeLine.length() > 0) nodeLine.append(", ");
            nodeLine.append(CliSupport.displayKey(node));
        }
        out.println("Nodes (" + nodes.size() + "): " + nodeLine);
        out.println("Edges (" + edges.size() + "):");
        if (edges.isEmpty()) {
            // 扫描统计先行：candidatePairs=0 即全部记录对共享同一调用点身份（出边前提不存在）
            out.println("  No data-flow edges (scanned " + CliSupport.plural(stats.getRecords(), "record") + " across "
                    + CliSupport.plural(stats.getSessions(), "session") + "; " + CliSupport.plural(stats.getInvocationKeys(), "invocation key")
                    + ", " + CliSupport.plural(stats.getCandidatePairs(), "cross-key record pair") + ").");
            out.println("  An edge needs all three: (1) the two records carry different invocation identities");
            out.println("  (declare per-step invocation labels); (2) the upstream value appears in a tool result,");
            out.println("  either recorded alongside its call or carried by an earlier record's request history;");
            out.println("  (3) a later tool call's argument value equals that upstream value exactly.");
        }
        for (GraphEdge edge : edges) {
            out.println(edgeLine(edge));
        }
        Set<String> cycles = graph.detectCycles();
        if (cycles.isEmpty()) {
            out.println("Cycles: none");
        } else {
            out.println("Cycles (" + CliSupport.plural(cycles.size(), "node") + "): " + String.join(", ", new TreeSet<>(cycles)));
        }
        if (!nodes.isEmpty()) {
            // 图例：短形 → 完整键逐字映射。完整键是可寻址身份（可直接复制进 --invocation），
            // 不截断；正文行只走短形，不刷长键
            out.println("Legend:");
            for (String node : nodes) {
                out.println("  " + CliSupport.displayKey(node) + " = " + node);
            }
        }
    }

    private String edgeLine(GraphEdge edge) {
        StringBuilder line = new StringBuilder("  ").append(CliSupport.displayKey(edge.getSource()))
                .append(" -> ").append(CliSupport.displayKey(edge.getTarget()))
                .append("  ").append(edge.getConfidence());
        if (edge.getEvidenceValue() != null) {
            line.append("  \"").append(CliSupport.abbreviateText(edge.getEvidenceValue(), EVIDENCE_DISPLAY_BUDGET)).append("\"");
            if (edge.getEvidenceSourceRecordId() != null && edge.getEvidenceTargetRecordId() != null) {
                line.append(" (").append(edge.getEvidenceSourceRecordId())
                        .append(" -> ").append(edge.getEvidenceTargetRecordId()).append(")");
            }
        }
        return line.toString();
    }

    private static String jsonStringOrNull(String value) {
        return value != null ? "\"" + RecursiveJsonParser.escape(value) + "\"" : "null";
    }
}
