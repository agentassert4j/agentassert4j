package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 参数值追踪器 — 通过字段值精确匹配 + 字段名前缀匹配构建值溯源图谱。
 *
 * <p>两层匹配策略：
 * <ul>
 *   <li>第 1 层：字段值精确 equals，对会话内全部更早记录触达（值溯源语义——值产生后隔步被引用
 *       也建边）→ HIGH 置信度，携带证据（命中值 + 源/目标记录 id）</li>
 *   <li>第 2 层：字段名前缀匹配，仅相邻对且该对未命中精确匹配 → LOW 置信度（提示，不携带证据）</li>
 * </ul>
 *
 * <p>链路构建：按 sessionId 分组 + 按 timestamp 排序，仅追踪单次会话内的数据流。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ParameterValueTracer {

    private static final int MAX_EXTRACT_DEPTH = 3;
    private static final int MAX_EXTRACTED_VALUES = 500;
    private static final int MIN_PREFIX_LENGTH = 3;

    private final InMemoryDependencyGraph graph;

    /**
     * @param graph 边的落点（调用方显式持有，可与其他追踪共享同一张图）
     */
    public ParameterValueTracer(InMemoryDependencyGraph graph) {
        this.graph = graph;
    }

    public InMemoryDependencyGraph getGraph() {
        return graph;
    }

    /**
     * 从存储层重建完整依赖图谱。
     * 按 sessionId 分组，每个 session 内按 timestamp 排序后追踪依赖。
     *
     * @param repository 存储仓库
     */
    public void rebuildGraph(StorageRepository repository) {
        List<String> sessionIds = repository.findAllSessionIds();
        for (String sessionId : sessionIds) {
            List<InteractionRecord> chain = repository.findBySessionId(sessionId).stream()
                    // timestamp 平局时按 recordId 决胜——同毫秒交互的边方向必须可复现
                    .sorted(Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : "")).collect(Collectors.toList());
            traceDependency(chain);
        }
    }

    /**
     * 在单个 session 的有序链中追踪数据流依赖。
     * 第 1 层：字段值精确匹配（对会话内全部更早记录触达）→ HIGH + 证据；
     * 第 2 层：字段名前缀匹配（仅相邻对、且该对未命中精确匹配）→ LOW。
     * 键为 null 或同键的对不建边：同键多执行不自环（自环既污染溯源图又误触环检测）。
     */
    public void traceDependency(List<InteractionRecord> chain) {
        if (chain == null || chain.size() < 2) return;

        int n = chain.size();
        // 逐记录提取缓存：值集/名集每记录提取一次，供全部对扫描复用
        List<Set<String>> valueCache = new ArrayList<>(n);
        List<Set<String>> nameCache = new ArrayList<>(n);
        List<Set<String>> argValueCache = new ArrayList<>(n);
        List<Set<String>> argNameCache = new ArrayList<>(n);
        List<String> invocationKeys = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            valueCache.add(null);
            nameCache.add(null);
            argValueCache.add(null);
            argNameCache.add(null);
            invocationKeys.add(invocationKeyOf(chain.get(k)));
        }

        for (int i = 1; i < n; i++) {
            String currInvocation = invocationKeys.get(i);
            if (currInvocation == null) continue;

            for (int j = 0; j < i; j++) {
                String prevInvocation = invocationKeys.get(j);
                if (prevInvocation == null || prevInvocation.equals(currInvocation)) continue;

                // ====== 第 1 层：字段值精确匹配（j 会话内全对触达） ======
                if (valueCache.get(j) == null) {
                    valueCache.set(j, extractFieldValues(chain.get(j)));
                }
                if (argValueCache.get(i) == null) {
                    argValueCache.set(i, extractArgValues(chain.get(i)));
                }
                String matchedValue = firstMeaningfulMatch(valueCache.get(j), argValueCache.get(i));
                if (matchedValue != null) {
                    graph.addEdge(prevInvocation, currInvocation, Confidence.HIGH,
                            matchedValue, chain.get(j).getRecordId(), chain.get(i).getRecordId());
                    continue;
                }

                // ====== 第 2 层：字段名前缀匹配（仅相邻对） ======
                if (j == i - 1) {
                    if (nameCache.get(j) == null) {
                        nameCache.set(j, extractFieldNames(chain.get(j)));
                    }
                    if (argNameCache.get(i) == null) {
                        argNameCache.set(i, extractArgNames(chain.get(i)));
                    }
                    if (prefixMatched(nameCache.get(j), argNameCache.get(i))) {
                        graph.addEdge(prevInvocation, currInvocation, Confidence.LOW, null, null, null);
                    }
                }
            }
        }
    }

    /**
     * 值集与参数值集的首个有意义命中：值集 LinkedHashSet 插入序 + isMeaningfulValue 过滤
     * ⇒ 首命中唯一确定（证据可复现的前提）。
     */
    private String firstMeaningfulMatch(Set<String> fieldValues, Set<String> argValues) {
        for (String value : fieldValues) {
            if (isMeaningfulValue(value) && argValues.contains(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 字段名前缀匹配（前缀 ≥ 最小长度）：当前对只需一条命中即建 LOW 边。
     */
    private boolean prefixMatched(Set<String> fieldNames, Set<String> argNames) {
        for (String pName : fieldNames) {
            String pPrefix = extractPrefix(pName);
            if (pPrefix.length() < MIN_PREFIX_LENGTH) continue;
            for (String cName : argNames) {
                if (pPrefix.equals(extractPrefix(cName))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从前序交互提取所有叶子节点的字符串值（RecursiveJsonParser 解析，深度限制 3 层）。
     *
     * <p>值源按记录形状择一：任一工具调用带录制结果（ToolCall.result）时取全部工具
     * 返回——工具返回才是下游参数的真实上游；否则看历史轮次里的 tool 角色结果帧——
     * 逐轮成记录的形状（回路编排在 ChatModel 之外，发起帧与结果分家）下，工具返回
     * 住在下一轮请求历史的 previousTurns，语义上同样是下游参数的真实上游；两者皆无
     * 时取模型回复文本，它是无工具调用的声明记录（纯文本结构化技能）的唯一值源。</p>
     */
    public Set<String> extractFieldValues(InteractionRecord record) {
        Set<String> values = new LinkedHashSet<>();
        if (record == null) return values;

        if (hasRecordedToolResult(record)) {
            for (ToolCall call : record.getToolCalls()) {
                String result = call != null ? call.getResult() : null;
                if (result == null || result.trim().isEmpty()) continue;
                Object json = RecursiveJsonParser.parse(result);
                if (json != null) collectLeafValues(json, values, 0);
            }
            return values;
        }

        if (hasToolTurnResult(record)) {
            for (TurnContext turn : record.getPreviousTurns()) {
                if (turn == null || !"tool".equals(turn.getRole())) continue;
                String content = turn.getContent();
                if (content == null || content.trim().isEmpty()) continue;
                Object json = RecursiveJsonParser.parse(content);
                if (json != null) collectLeafValues(json, values, 0);
            }
            return values;
        }

        if (record.getModelResponse() == null) return values;
        Object json = RecursiveJsonParser.parse(record.getModelResponse());
        if (json != null) {
            collectLeafValues(json, values, 0);
        }
        return values;
    }

    /**
     * 记录中是否存在任一非空的录制工具返回——决定字段值源走工具返回还是模型回复文本。
     */
    private static boolean hasRecordedToolResult(InteractionRecord record) {
        if (record.getToolCalls() == null) return false;
        for (ToolCall call : record.getToolCalls()) {
            if (call != null && call.getResult() != null && !call.getResult().trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 历史轮次中是否存在任一非空的 tool 角色结果帧——逐轮成记录形状下的工具返回载体
     * （回路编排在外层发起，工具结果经回灌进下一轮请求历史）。
     */
    private static boolean hasToolTurnResult(InteractionRecord record) {
        if (record.getPreviousTurns() == null) return false;
        for (TurnContext turn : record.getPreviousTurns()) {
            if (turn != null && "tool".equals(turn.getRole())
                    && turn.getContent() != null && !turn.getContent().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void collectLeafValues(Object node, Set<String> sink, int depth) {
        if (depth > MAX_EXTRACT_DEPTH || sink.size() >= MAX_EXTRACTED_VALUES) return;
        if (node instanceof Map) {
            ((Map<String, Object>) node).values().forEach(v -> collectLeafValues(v, sink, depth + 1));
        } else if (node instanceof List) {
            ((List<Object>) node).forEach(v -> collectLeafValues(v, sink, depth + 1));
        } else if (node != null) {
            String val = node.toString();
            if (val.length() >= 2 && val.length() <= 1000) {
                sink.add(val);
            }
        }
    }

    /**
     * 从当前工具的参数中提取所有值。
     */
    public Set<String> extractArgValues(InteractionRecord record) {
        if (record == null || record.getToolCalls() == null) return Collections.emptySet();
        return record.getToolCalls().stream().filter(tc -> tc.getArguments() != null).flatMap(tc -> tc.getArguments().values().stream()).map(Object::toString).filter(v -> v.length() >= 2).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 从前序交互提取所有字段名——值源选择同 {@link #extractFieldValues}：
     * 带录制工具结果的记录取工具返回，否则取模型回复文本。
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractFieldNames(InteractionRecord record) {
        Set<String> names = new LinkedHashSet<>();
        if (record == null) return names;

        if (hasRecordedToolResult(record)) {
            for (ToolCall call : record.getToolCalls()) {
                String result = call != null ? call.getResult() : null;
                if (result == null || result.trim().isEmpty()) continue;
                Object json = RecursiveJsonParser.parse(result);
                if (json != null) collectFieldNames(json, names, 0);
            }
            return names;
        }

        if (record.getModelResponse() == null) return names;
        Object json = RecursiveJsonParser.parse(record.getModelResponse());
        collectFieldNames(json, names, 0);
        return names;
    }

    @SuppressWarnings("unchecked")
    private void collectFieldNames(Object node, Set<String> sink, int depth) {
        if (depth > MAX_EXTRACT_DEPTH || sink.size() >= MAX_EXTRACTED_VALUES) return;
        if (node instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) node).entrySet()) {
                sink.add(entry.getKey());
                collectFieldNames(entry.getValue(), sink, depth + 1);
            }
        } else if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                collectFieldNames(item, sink, depth + 1);
            }
        }
    }

    /**
     * 从当前工具的参数中提取所有参数名。
     */
    public Set<String> extractArgNames(InteractionRecord record) {
        if (record == null || record.getToolCalls() == null) return Collections.emptySet();
        return record.getToolCalls().stream().filter(tc -> tc.getArguments() != null).flatMap(tc -> tc.getArguments().keySet().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 判断是否为"有意义的值"——排除纯数字、单个字符、布尔值等噪声。
     */
    public boolean isMeaningfulValue(String val) {
        if (val == null || val.length() < 3) return false;
        // 布尔字面量是高噪值：几乎所有工具链都会流经 true/false，建边即假依赖
        if ("true".equals(val) || "false".equals(val)) return false;
        if (val.matches("-?\\d+(\\.\\d+)?")) return false; // 纯数字排除
        return true;
    }

    /**
     * 提取字段名前缀：驼峰 / 下划线 / 连字符。
     * "orderId" → "order", "order_ref" → "order", "order-ref" → "order"
     */
    public String extractPrefix(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return "";
        String[] parts = fieldName.split("(?=[A-Z])|_|-");
        return parts[0].toLowerCase(Locale.ROOT);
    }

    /**
     * 记录的调用点键（依赖图节点身份单一空间）。
     * 优先用落库存储值（enrich 写入，录入即定格），缺失时通过 InvocationResolver 现算；
     * 现算失败（如损坏的工具调用形状）退化为 null——单条记录不阻断整张依赖图重建。
     */
    private String invocationKeyOf(InteractionRecord record) {
        if (record.getInvocationKey() != null && !record.getInvocationKey().isEmpty()) {
            return record.getInvocationKey();
        }
        try {
            return InvocationResolver.resolve(record).getInvocationKey();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
