package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.model.GraphEdge;
import io.github.agentassert4j.spi.StorageRepository;
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
 * <p>图是派生数据：本命令每次从交互记录重建，永远反映最新录制状态；
 * 快照留档归 replay（写者唯一），本命令只读。多轮工具会话之外的数据
 * 建不出边——空图说明录制数据缺会话链，不是图功能故障。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "show", description = "现场重建并查看依赖图谱（节点/边/置信度/环）", mixinStandardHelpOptions = true)
public class GraphShowCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, out);
            InMemoryDependencyGraph graph = CliSupport.rebuildGraph(repository);

            Set<String> nodes = new TreeSet<>(graph.getAllNodes());
            out.println("节点（" + nodes.size() + "）：" + String.join(", ", nodes));

            List<GraphEdge> edges = new ArrayList<>(graph.getAllEdges());
            edges.sort((a, b) -> (a.getSource() + ">" + a.getTarget()).compareTo(b.getSource() + ">" + b.getTarget()));
            out.println("边（" + edges.size() + "）：");
            for (GraphEdge edge : edges) {
                String through = edge.getThroughNodes() != null && !edge.getThroughNodes().isEmpty() ? "（穿透：" + String.join(",", edge.getThroughNodes()) + "）" : "";
                out.println("  " + edge.getSource() + " -> " + edge.getTarget() + "  " + edge.getConfidence() + through);
            }

            Set<String> cycles = graph.detectCycles();
            if (cycles.isEmpty()) {
                out.println("环：无");
            } else {
                out.println("环（" + cycles.size() + " 个节点）：" + String.join(", ", new TreeSet<>(cycles)));
            }
            if (edges.isEmpty()) {
                out.println("提示：无边——依赖边来自多轮工具会话的值流（上游输出值出现在下游参数），");
                out.println("      录制数据需含同一 sessionId 的多轮交互才会产边。");
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("graph show 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
