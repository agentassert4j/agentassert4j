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
 * graph show 命令 — 现场重建依赖图并渲染（只读，不落盘）。
 *
 * <p>图是派生数据：本命令每次从交互记录重建，永远反映最新录制状态。
 * 多轮工具会话之外的数据建不出边——空图说明录制数据缺会话链，
 * 不是图功能故障。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "show", description = "Rebuild and inspect the dependency graph (nodes/edges/confidence/cycles)", mixinStandardHelpOptions = true)
public class GraphShowCommand implements Callable<Integer> {

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
                StringBuilder edgeJson = new StringBuilder();
                for (GraphEdge edge : edges) {
                    if (edgeJson.length() > 0) edgeJson.append(",");
                    edgeJson.append("{\"source\":\"").append(RecursiveJsonParser.escape(edge.getSource())).append("\",\"target\":\"").append(RecursiveJsonParser.escape(edge.getTarget())).append("\",\"confidence\":\"").append(edge.getConfidence()).append("\"}");
                }
                StringBuilder cyclesJson = new StringBuilder();
                for (String node : new TreeSet<>(graph.detectCycles())) {
                    if (cyclesJson.length() > 0) cyclesJson.append(",");
                    cyclesJson.append("\"").append(RecursiveJsonParser.escape(node)).append("\"");
                }
                out.println("{\"schema\":\"" + ReportSchemas.GRAPH + "\",\"nodeCount\":" + nodes.size() + ",\"edgeCount\":" + edges.size() + ",\"edges\":[" + edgeJson + "],\"cycles\":[" + cyclesJson + "]" + (edges.isEmpty() ? ",\"note\":\"edges form when a recorded tool-call argument value traces back to an upstream response in the same session; non-overlapping or synthetic interactions produce no edges\"" : "") + "}");
                return 0;
            }

            out.println("Nodes (" + nodes.size() + "): " + String.join(", ", nodes));
            out.println("Edges (" + edges.size() + "):");
            if (edges.isEmpty()) {
                out.println("  No data-flow edges: an edge forms when a tool call's argument value traces back to an upstream response in the same session; non-overlapping or synthetic interactions produce no edges.");
            }
            for (GraphEdge edge : edges) {
                out.println("  " + edge.getSource() + " -> " + edge.getTarget() + "  " + edge.getConfidence());
            }
            Set<String> cycles = graph.detectCycles();
            if (cycles.isEmpty()) {
                out.println("Cycles: none");
            } else {
                out.println("Cycles (" + CliSupport.plural(cycles.size(), "node") + "): " + String.join(", ", new TreeSet<>(cycles)));
            }
            if (edges.isEmpty()) {
                out.println("Note: no edges; dependency edges come from value flow in multi-turn tool sessions (an upstream output value appearing in a downstream parameter).");
                out.println("      Recording data must contain multi-turn interactions within one sessionId to produce edges.");
            }
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
}
