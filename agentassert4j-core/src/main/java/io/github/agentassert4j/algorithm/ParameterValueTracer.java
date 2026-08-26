package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 参数值追踪器 — 通过字段值精确匹配 + 字段名前缀匹配构建依赖图谱。
 *
 * <p>两层匹配策略：
 * <ul>
 *   <li>第 1 层：字段值精确 equals → HIGH 置信度</li>
 *   <li>第 2 层：字段名前缀匹配 + 值非空 → LOW 置信度</li>
 * </ul>
 *
 * <p>链路构建：按 sessionId 分组 + 按 timestamp 排序，仅追踪单次会话内的数据流。</p>
 */
public class ParameterValueTracer {

    private static final int MAX_EXTRACT_DEPTH = 3;
    private static final int MAX_EXTRACTED_VALUES = 500;
    private static final int MIN_PREFIX_LENGTH = 3;

    private final InMemoryDependencyGraph graph;

    public ParameterValueTracer() {
        this.graph = new InMemoryDependencyGraph();
    }

    public ParameterValueTracer(InMemoryDependencyGraph graph) {
        this.graph = graph;
    }

    public InMemoryDependencyGraph getGraph() {
        return graph;
    }

    // ==================== 图谱重建入口 ====================

    /**
     * 从存储层重建完整依赖图谱。
     * 按 sessionId 分组，每个 session 内按 timestamp 排序后追踪依赖。
     *
     * @param repository 存储仓库
     */
    public void rebuildGraph(StorageRepository repository) {
        List<String> sessionIds = repository.findAllSessionIds();
        for (String sessionId : sessionIds) {
            List<InteractionRecord> chain = repository.findBySessionId(sessionId)
                    .stream()
                    // timestamp 平局时按 recordId 决胜——同毫秒交互的边方向必须可复现
                    .sorted(Comparator.comparingLong(InteractionRecord::getTimestamp)
                            .thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : ""))
                    .toList();
            traceDependency(chain);
        }
    }

    // ==================== 单 session 依赖追踪 ====================

    /**
     * 在单个 session 的有序链中追踪数据流依赖。
     * 第 1 层：字段值精确匹配 → HIGH；第 2 层：字段名前缀匹配 → LOW。
     */
    public void traceDependency(List<InteractionRecord> chain) {
        if (chain == null || chain.size() < 2) return;

        for (int i = 1; i < chain.size(); i++) {
            InteractionRecord prev = chain.get(i - 1);
            InteractionRecord curr = chain.get(i);

            String prevSkill = getSkillId(prev);
            String currSkill = getSkillId(curr);
            if (prevSkill == null || currSkill == null || prevSkill.equals(currSkill)) continue;

            // ====== 第 1 层：字段值精确匹配 ======
            Set<String> prevFieldValues = extractFieldValues(prev);
            Set<String> currArgValues = extractArgValues(curr);

            boolean valueMatched = false;
            for (String prevVal : prevFieldValues) {
                if (isMeaningfulValue(prevVal) && currArgValues.contains(prevVal)) {
                    graph.addEdge(prevSkill, currSkill, Confidence.HIGH);
                    valueMatched = true;
                    break; // 一条精确匹配就够了
                }
            }

            // ====== 第 2 层：字段名前缀匹配 ======
            if (!valueMatched) {
                Set<String> prevFieldNames = extractFieldNames(prev);
                Set<String> currArgNames = extractArgNames(curr);

                boolean prefixMatched = false;
                for (String pName : prevFieldNames) {
                    if (prefixMatched) break;
                    String pPrefix = extractPrefix(pName);
                    if (pPrefix.length() < MIN_PREFIX_LENGTH) continue;
                    for (String cName : currArgNames) {
                        String cPrefix = extractPrefix(cName);
                        if (pPrefix.equals(cPrefix)) {
                            // 前缀匹配，建 LOW 边
                            graph.addEdge(prevSkill, currSkill, Confidence.LOW);
                            prefixMatched = true;
                            break; // 当前对只需建一条 LOW 边
                        }
                    }
                }
            }
        }
    }

    // ==================== 值提取 ====================

    /**
     * 从前序工具的 JSON 返回值中提取所有叶子节点的字符串值。
     * 使用 RecursiveJsonParser 解析，深度限制 3 层。
     *
     * <p>TODO: [语义优化] 当前从 InteractionRecord.getModelResponse() 提取值，
     * 但 modelResponse 是 LLM 的回复文本（可能包含 JSON）。
     * 更精确的做法是优先从 ToolCall.getResult()（工具实际返回的 JSON）提取，
     * 仅在无工具调用时降级到 modelResponse。
     * 待录制层确保 ToolCall.result 完整持久化后再优化此处。</p>
     */
    public Set<String> extractFieldValues(InteractionRecord record) {
        Set<String> values = new LinkedHashSet<>();
        if (record == null || record.getModelResponse() == null) return values;

        Object json = RecursiveJsonParser.parse(record.getModelResponse());
        if (json != null) {
            collectLeafValues(json, values, 0);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private void collectLeafValues(Object node, Set<String> sink, int depth) {
        if (depth > MAX_EXTRACT_DEPTH || sink.size() >= MAX_EXTRACTED_VALUES) return;
        if (node instanceof Map) {
            ((Map<String, Object>) node).values()
                    .forEach(v -> collectLeafValues(v, sink, depth + 1));
        } else if (node instanceof List) {
            ((List<Object>) node)
                    .forEach(v -> collectLeafValues(v, sink, depth + 1));
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
        return record.getToolCalls().stream()
                .filter(tc -> tc.getArguments() != null)
                .flatMap(tc -> tc.getArguments().values().stream())
                .map(Object::toString)
                .filter(v -> v.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== 名字提取 ====================

    /**
     * 从前序工具的 JSON 返回值中提取所有字段名。
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractFieldNames(InteractionRecord record) {
        Set<String> names = new LinkedHashSet<>();
        if (record == null || record.getModelResponse() == null) return names;

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
        return record.getToolCalls().stream()
                .filter(tc -> tc.getArguments() != null)
                .flatMap(tc -> tc.getArguments().keySet().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断是否为"有意义的值"——排除纯数字、单个字符、布尔值等噪声。
     */
    public boolean isMeaningfulValue(String val) {
        if (val == null || val.length() < 3) return false;
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
        return parts[0].toLowerCase();
    }

    /**
     * 获取记录的 skillId。
     * 如果 record 已有 skillId 直接使用，否则通过 DeterministicSkillGrouper 计算。
     */
    private String getSkillId(InteractionRecord record) {
        if (record.getSkillId() != null && !record.getSkillId().isEmpty()) {
            return record.getSkillId();
        }
        return DeterministicSkillGrouper.group(record).getSkillId();
    }
}
