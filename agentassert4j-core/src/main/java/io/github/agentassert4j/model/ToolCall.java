package io.github.agentassert4j.model;

import java.util.*;

/**
 * 工具调用记录 — LLM 决策调用一个工具的完整信息。
 *
 * <p>argTypes 获取策略：
 * <ol>
 *   <li>优先级 1（精确）：SDK / 框架提供</li>
 *   <li>优先级 2（推断）：从实际参数值推断类型</li>
 *   <li>优先级 3（默认）：全部默认 String</li>
 * </ol>
 * 接入层负责填充 argTypes，核心算法只消费。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ToolCall {

    private String toolName;
    private String toolCallId;
    private Map<String, Object> arguments;
    /**
     * 参数类型签名，如 {"orderId":"String","limit":"Integer"}
     */
    private Map<String, String> argTypes;
    private String result;
    private boolean success;

    /**
     * arguments 值树重建/脱敏的深度上限，与 RecursiveJsonParser 的解析封顶同一量级。
     * 嵌套深度来自模型输出、不可信：无封顶递归会以 StackOverflowError 中断
     * 调用线程（catch Exception 接不住 Error），录制旁路对病态输入按契约丢弃而非中断业务。
     */
    public static final int MAX_TREE_DEPTH = 128;

    /**
     * 深度截断后写入副本的占位值——截断事实随记录落库，就地可见。
     */
    public static final String DEPTH_TRUNCATION_MARKER = "[truncated]";

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }

    public Map<String, String> getArgTypes() {
        return argTypes;
    }

    public void setArgTypes(Map<String, String> argTypes) {
        this.argTypes = argTypes;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * 深拷贝：arguments 值树逐层重建（超过 {@link #MAX_TREE_DEPTH} 的子树截断为
     * {@link #DEPTH_TRUNCATION_MARKER}），上游事后修改嵌套结构不影响副本。
     */
    public ToolCall copy() {
        ToolCall copy = new ToolCall();
        copy.toolName = toolName;
        copy.toolCallId = toolCallId;
        if (arguments != null) {
            copy.arguments = castArgumentTree(deepCopyValue(arguments));
        }
        copy.argTypes = argTypes != null ? new HashMap<>(argTypes) : null;
        copy.result = result;
        copy.success = success;
        return copy;
    }

    private static Map<String, Object> castArgumentTree(Object copied) {
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) copied;
        return typed;
    }

    /**
     * 任意深度重建 Map/List 值树；标量值原样共享（不可变）。
     */
    private static Object deepCopyValue(Object value) {
        return deepCopyValue(value, 1);
    }

    private static Object deepCopyValue(Object value, int depth) {
        if (depth > MAX_TREE_DEPTH) {
            return DEPTH_TRUNCATION_MARKER;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(deepCopyValue(item, depth + 1));
            }
            return out;
        }
        return value;
    }
}
