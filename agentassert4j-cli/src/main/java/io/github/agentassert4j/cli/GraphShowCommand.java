package io.github.agentassert4j.cli;

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
 * graph show 命令 — 现场重建值溯源图并渲染（只读，不落盘）。
 *
 * <p>图是派生数据：本命令每次从交互记录重建，永远反映最新录制状态。
 * HIGH 边 = 会话内值溯源（工具参数值回溯到任一更早记录的输出，携带命中值与源/目标记录对）；
 * LOW 边 = 相邻对的字段名前缀提示（不携带证据）。人读面节点/边走 displayKey 短形，
 * 完整键在图例逐字可寻址；JSON 面保持完整键（机器契约不变）。
 * 多轮工具会话之外的数据建不出边——空图说明录制数据缺会话链，不是图功能故障。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "show", description = "Rebuild and inspect the value-flow provenance graph (nodes/edges/evidence/cycles)", mixinStandardHelpOptions = true)
public class GraphShowCommand implements Callable<Integer> {

    // 证据值人读截断预算：走 abbreviateText 单源（空白折叠 + 截断 + ASCII 省略号），
    // JSON 叶子值可含换行，裸截断会破坏单行报告格式
    private static final int EVIDENCE_DISPLAY_BUDGET = 40;

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
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr，人类渲染不输出
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            InMemoryDependencyGraph graph = CliSupport.rebuildGraph(repository);

            Set<String> nodes = new TreeSet<>(graph.getAllNodes());
            List<GraphEdge> edges = new ArrayList<>(graph.getAllEdges());
            edges.sort((a, b) -> (a.getSource() + ">" + a.getTarget()).compareTo(b.getSource() + ">" + b.getTarget()));

            if (jsonOutput) {
                out.println(graphJson(nodes, edges, graph));
                return 0;
            }

            renderHuman(nodes, edges, graph);
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

    private String graphJson(Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph) {
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
                + (edges.isEmpty() ? ",\"note\":\"edges form when a recorded tool-call argument value traces back to an upstream response in the same session; non-overlapping or synthetic interactions produce no edges\"" : "") + "}";
    }

    private void renderHuman(Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph) {
        StringBuilder nodeLine = new StringBuilder();
        for (String node : nodes) {
            if (nodeLine.length() > 0) nodeLine.append(", ");
            nodeLine.append(CliSupport.displayKey(node));
        }
        out.println("Nodes (" + nodes.size() + "): " + nodeLine);
        out.println("Edges (" + edges.size() + "):");
        if (edges.isEmpty()) {
            out.println("  No data-flow edges: an edge forms when a tool call's argument value traces back to an upstream response in the same session; non-overlapping or synthetic interactions produce no edges.");
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
        if (edges.isEmpty()) {
            out.println("Note: no edges; dependency edges come from value flow in multi-turn tool sessions (an upstream output value appearing in a downstream parameter).");
            out.println("      Recording data must contain multi-turn interactions within one sessionId to produce edges.");
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
