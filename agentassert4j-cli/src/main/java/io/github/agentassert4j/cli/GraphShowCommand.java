package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.GraphBuildStats;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.algorithm.ParameterValueTracer;
import io.github.agentassert4j.model.GraphEdge;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * graph show 命令 — 现场重建值溯源图并渲染（只读，不写任何文件）。
 *
 * <p>图是派生数据：本命令每次从交互记录重建，永远反映最新录制状态。
 * HIGH 边 = 会话内值溯源（工具参数值回溯到任一更早记录的输出，携带命中值与源/目标记录对）；
 * LOW 边 = 相邻对的字段名前缀提示（不携带证据）。人读输出中节点/边走 displayKey 短形，
 * 完整键在图例逐字可寻址；JSON 面保持完整键（机器契约不变）。
 * 节点集 = 已录制的全部调用点键（含未参与任何边的），空边不再等于空图；
 * 出边的全部条件在空态输出就地列明（与 tracer 实现的真实匹配规则逐条对齐：
 * 独立叶子、精确相等、噪声排除、载体优先级），并附近失诊断（最接近成边的
 * 记录与不成因），扫描统计（会话/记录/键/跨键对）区分「没数据」与「数据在、无边」。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "show", description = "Rebuild and inspect the value-flow provenance graph (nodes/edges/evidence/cycles)", mixinStandardHelpOptions = true)
public class GraphShowCommand implements Callable<Integer> {

    // 证据值人读截断预算：走 abbreviateText 唯一定义处（空白折叠 + 截断 + ASCII 省略号），
    // JSON 叶子值可含换行，裸截断会破坏单行报告格式
    private static final int EVIDENCE_DISPLAY_BUDGET = 40;

    // 出边条件的机器面表述（JSON note 与人读条目同语义，两处按同一规则维护）：
    // 必须覆盖 tracer 的全部真实匹配规则——独立 JSON 叶子、精确相等、噪声排除
    // （纯数字/布尔/过短）、载体优先级（工具结果 > 历史工具帧 > 响应全文）。
    // 少写一条，用户按说明构造就会全部落空（round13 双宿主实测的坑）
    private static final String EDGE_CONDITIONS_JSON = "an edge requires all of: the two records carry different invocation identities (declare per-step invocation labels); the upstream value is a standalone JSON leaf (a value embedded inside a longer text never matches) found in a tool result, an earlier record's request-history tool frame, or the response body (up to 4 levels deep); the value passes noise filters (short pure numbers and decimals are excluded; 6+ digit pure integers count as ids and pass; true/false excluded; length >= 3); and a later response's tool-call argument equals it exactly (substring embedding does not match). Per record only the highest-priority carrier is scanned: tool result first, then history tool frames, then the response body. A value born in a record request history and consumed by the same record response tool call produces no edge; provenance starts one record later";

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
                out.println(graphJson(repository, nodes, edges, graph, stats));
                return 0;
            }

            renderHuman(repository, nodes, edges, graph, stats);
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

    private String graphJson(StorageRepository repository, Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph, GraphBuildStats stats) {
        StringBuilder edgeJson = new StringBuilder();
        for (GraphEdge edge : edges) {
            if (edgeJson.length() > 0) edgeJson.append(",");
            edgeJson.append("{\"source\":\"").append(RecursiveJsonParser.escape(edge.getSource())).append("\",\"target\":\"").append(RecursiveJsonParser.escape(edge.getTarget())).append("\",\"confidence\":\"").append(edge.getConfidence()).append("\"");
            if (edge.getEvidenceValue() != null) {
                edgeJson.append(",\"evidence\":{\"value\":").append(jsonStringOrNull(edge.getEvidenceValue())).append(",\"sourceRecordId\":").append(jsonStringOrNull(edge.getEvidenceSourceRecordId())).append(",\"targetRecordId\":").append(jsonStringOrNull(edge.getEvidenceTargetRecordId()));
                if (edge.getMatchedValues() != null && !edge.getMatchedValues().isEmpty()) {
                    StringBuilder valuesJson = new StringBuilder();
                    for (String value : edge.getMatchedValues()) {
                        if (valuesJson.length() > 0) valuesJson.append(",");
                        valuesJson.append("\"").append(RecursiveJsonParser.escape(value)).append("\"");
                    }
                    edgeJson.append(",\"matchedValues\":[").append(valuesJson).append("]");
                }
                edgeJson.append("}");
            }
            edgeJson.append("}");
        }
        StringBuilder cyclesJson = new StringBuilder();
        for (String node : new TreeSet<>(graph.detectCycles())) {
            if (cyclesJson.length() > 0) cyclesJson.append(",");
            cyclesJson.append("\"").append(RecursiveJsonParser.escape(node)).append("\"");
        }
        StringBuilder nodesJson = new StringBuilder();
        for (String node : nodes) {
            if (nodesJson.length() > 0) nodesJson.append(",");
            nodesJson.append("\"").append(RecursiveJsonParser.escape(node)).append("\"");
        }
        return "{\"schema\":\"" + ReportSchemas.GRAPH + "\",\"nodeCount\":" + nodes.size() + ",\"nodes\":[" + nodesJson + "],\"edgeCount\":" + edges.size() + ",\"edges\":[" + edgeJson + "],\"cycles\":[" + cyclesJson + "]" + ",\"scanned\":{\"sessions\":" + stats.getSessions() + ",\"records\":" + stats.getRecords() + ",\"invocationKeys\":" + stats.getInvocationKeys() + ",\"candidatePairs\":" + stats.getCandidatePairs() + "}" + (edges.isEmpty() ? ",\"note\":\"" + EDGE_CONDITIONS_JSON + "\"" : "") + graphNearMissJson(repository) + "}";
    }

    private void renderHuman(StorageRepository repository, Set<String> nodes, List<GraphEdge> edges, InMemoryDependencyGraph graph, GraphBuildStats stats) {
        // 近失诊断与边数解耦计算：库里只要存在任何一条真实边，「整库零边」门控就
        // 永远不再触发——其余记录对的近失从此不可见，等于诊断面在真实项目上死亡
        // （round31 双宿主实弹：有边库上的大小写不匹配/裸文本结果全部静默）
        List<String> misses = nearMisses(repository);
        // 节点短形约 100 字符软换行收纳——几十个键挤一行在终端里只能水平滚动
        out.println("Nodes (" + nodes.size() + "):");
        StringBuilder nodeLine = new StringBuilder("  ");
        for (String node : nodes) {
            String token = CliSupport.displayKey(node);
            if (nodeLine.length() + token.length() + 2 > 100 && nodeLine.length() > 2) {
                out.println(nodeLine.toString());
                nodeLine = new StringBuilder("  ");
            }
            if (nodeLine.length() > 2) {
                nodeLine.append(", ");
            }
            nodeLine.append(token);
        }
        if (nodeLine.length() > 2) {
            out.println(nodeLine.toString());
        }
        out.println("Edges (" + edges.size() + "):");
        if (edges.isEmpty()) {
            // 扫描统计先行：candidatePairs=0 即全部记录对共享同一调用点身份（出边前提不存在）
            out.println("  No data-flow edges (scanned " + CliSupport.plural(stats.getRecords(), "record") + " across " + CliSupport.plural(stats.getSessions(), "session") + "; " + CliSupport.plural(stats.getInvocationKeys(), "invocation key") + ", " + CliSupport.plural(stats.getCandidatePairs(), "cross-key record pair") + ").");
            out.println("  An edge means: a value produced by an earlier record was used as an argument of a");
            out.println("  later model-issued tool call, in the same session. All of the following must hold:");
            out.println("  (1) the two records carry different invocation identities (declare per-step labels);");
            out.println("  (2) the upstream value is a standalone JSON leaf of a tool result, an earlier record's");
            out.println("      request-history tool frame, or the response body (up to 4 levels deep) -- a value");
            out.println("      embedded inside a longer text never matches;");
            out.println("  (3) the value passes noise filters: not a short pure number (pure integers with 6+ digits count as ids and pass), not a decimal, not true/false, length >= 3;");
            out.println("  (4) a later response's tool-call argument equals the value exactly -- substring");
            out.println("      embedding does not match;");
            out.println("  (5) per record only the highest-priority carrier is scanned: tool result first,");
            out.println("      then history tool frames, then the response body.");
            out.println("  Note: the hop where a value is born in a record's request history and consumed by");
            out.println("  the same record's response tool call is not drawn (edges link different records);");
            out.println("  provenance therefore starts one record later, and the shown origin is the nearest");
            out.println("  earlier carrier, which may not be the true business source.");
            for (String miss : misses) {
                out.println("  Near miss: " + miss);
            }
        }
        for (GraphEdge edge : edges) {
            out.println(edgeLine(edge));
        }
        if (!edges.isEmpty() && !misses.isEmpty()) {
            out.println("Near misses (" + misses.size() + ") — record pairs that nearly connected:");
            for (String miss : misses) {
                out.println("  Near miss: " + miss);
            }
        }
        Set<String> cycles = graph.detectCycles();
        if (cycles.isEmpty()) {
            out.println("Cycles: none");
        } else {
            out.println("Cycles (" + CliSupport.plural(cycles.size(), "node") + "): " + String.join(", ", new TreeSet<>(cycles)));
        }
        if (!nodes.isEmpty()) {
            // 图例：短形 → 完整键逐字映射（完整键是可寻址身份，可直接复制进 --invocation，
            // 不截断）。多条映射挤进行内（约 100 字符软换行），不再一键一行刷半屏
            out.println("Legend (short form = full invocationKey, copy-paste ready):");
            StringBuilder legendLine = new StringBuilder("  ");
            for (String node : nodes) {
                String mapping = CliSupport.displayKey(node) + " = " + node;
                if (legendLine.length() + mapping.length() + 2 > 100 && legendLine.length() > 2) {
                    out.println(legendLine.toString());
                    legendLine = new StringBuilder("  ");
                }
                if (legendLine.length() > 2) {
                    legendLine.append("  ");
                }
                legendLine.append(mapping);
            }
            if (legendLine.length() > 2) {
                out.println(legendLine.toString());
            }
        }
    }

    /**
     * 近失诊断（至多 3 条）：找出「带模型工具调用参数但没接上上游」的记录，
     * 用 tracer 同一套提取规则回答「差在哪」——上游没有可提取值、值全被噪声排除、
     * 只有子串包含、或参数与上游值毫无交集。任一参数已与上游值精确相等的记录
     * 不算近失（它连通了）；诊断与边数解耦后这条排除是正确性前提，否则已连通的
     * 记录对也会被报成「没接上」。全库没有任何工具调用参数时给出那条结构性事实。
     */
    private static List<String> nearMisses(StorageRepository repository) {
        List<String> lines = new ArrayList<>();
        ParameterValueTracer probe = new ParameterValueTracer(new InMemoryDependencyGraph());
        boolean anySink = false;
        for (String sessionId : repository.findAllSessionIds()) {
            List<InteractionRecord> chain = repository.findBySessionId(sessionId).stream()
                    .sorted(Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : ""))
                    .collect(Collectors.toList());
            if (chain.size() < 2) {
                continue;
            }
            for (int i = 1; i < chain.size() && lines.size() < 3; i++) {
                InteractionRecord later = chain.get(i);
                Set<String> args = probe.extractArgValues(later);
                if (args.isEmpty()) {
                    continue;
                }
                anySink = true;
                String laterKey = CliSupport.invocationKeyOfRecord(later);
                Set<String> leaves = new LinkedHashSet<>();
                for (int j = 0; j < i; j++) {
                    String earlierKey = CliSupport.invocationKeyOfRecord(chain.get(j));
                    if (earlierKey == null || earlierKey.equals(laterKey)) {
                        continue;
                    }
                    leaves.addAll(probe.extractFieldValues(chain.get(j)));
                }
                if (leaves.isEmpty()) {
                    lines.add(CliSupport.displayKey(laterKey) + " (session " + sessionId + "): earlier records expose no extractable values (tool results / history tool frames / response bodies)");
                    continue;
                }
                Set<String> meaningful = new LinkedHashSet<>();
                for (String leaf : leaves) {
                    if (probe.isMeaningfulValue(leaf)) {
                        meaningful.add(leaf);
                    }
                }
                if (meaningful.isEmpty()) {
                    lines.add(CliSupport.displayKey(laterKey) + " (session " + sessionId + "): upstream values are all noise-excluded (short pure numbers and decimals, true/false, or shorter than 3 characters)");
                    continue;
                }
                boolean anyExactMatch = false;
                for (String arg : args) {
                    if (meaningful.contains(arg)) {
                        anyExactMatch = true;
                        break;
                    }
                }
                if (anyExactMatch) {
                    continue;
                }
                boolean embedsOnly = false;
                for (String arg : args) {
                    for (String leaf : meaningful) {
                        if (!arg.equals(leaf) && (arg.contains(leaf) || leaf.contains(arg))) {
                            embedsOnly = true;
                        }
                    }
                }
                lines.add(CliSupport.displayKey(laterKey) + " (session " + sessionId + ")" + (embedsOnly
                        ? ": argument values only embed upstream values as substrings; matching requires exact equality"
                        : ": no argument value equals an upstream value exactly"));
            }
            if (lines.size() >= 3) {
                break;
            }
        }
        if (lines.isEmpty() && !anySink) {
            lines.add("no record carries a model-issued tool call with arguments in any response, or every argument value was filtered as noise (short numbers, decimals, booleans)");
        }
        return lines;
    }

    /**
     * graph/1 的近失诊断：机器面与 human 面同一分类逻辑，消费方零调用即可读到
     * 「差在哪」。字段恒在场（含空数组）——诊断字段随边数出没会让消费方把
     * 「无字段」误读成「无问题」。
     */
    private static String graphNearMissJson(StorageRepository repository) {
        StringBuilder misses = new StringBuilder();
        for (String miss : nearMisses(repository)) {
            if (misses.length() > 0) misses.append(",");
            misses.append("\"").append(RecursiveJsonParser.escape(miss)).append("\"");
        }
        return ",\"nearMisses\":[" + misses + "]";
    }

    private String edgeLine(GraphEdge edge) {
        StringBuilder line = new StringBuilder("  ").append(CliSupport.displayKey(edge.getSource())).append(" -> ").append(CliSupport.displayKey(edge.getTarget())).append("  ").append(edge.getConfidence());
        if (edge.getEvidenceValue() != null) {
            line.append("  \"").append(CliSupport.abbreviateText(edge.getEvidenceValue(), EVIDENCE_DISPLAY_BUDGET)).append("\"");
            if (edge.getEvidenceSourceRecordId() != null && edge.getEvidenceTargetRecordId() != null) {
                line.append(" (").append(edge.getEvidenceSourceRecordId()).append(" -> ").append(edge.getEvidenceTargetRecordId()).append(")");
            }
        }
        return line.toString();
    }

    private static String jsonStringOrNull(String value) {
        return value != null ? "\"" + RecursiveJsonParser.escape(value) + "\"" : "null";
    }
}
