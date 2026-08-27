package io.github.agentassert4j.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具调用参数值类型派生 — 按值形态映射到固定类型词表。
 *
 * <p>词表：string / number / boolean / object / array / null（键统一小写）。
 * 捕获侧（SDK 填充 ToolCall.argTypes）与重放侧（executor 从响应派生）必须共用
 * 本派生逻辑——两侧词表漂移会直接造成参数类型维指纹的假阳性回归。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public final class ArgTypeUtil {

    private ArgTypeUtil() {
    }

    /**
     * 按值的运行时形态派生参数类型名；null 入参返回空 Map。
     */
    public static Map<String, String> derive(Map<String, Object> arguments) {
        Map<String, String> types = new LinkedHashMap<>();
        if (arguments == null) {
            return types;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            String type;
            if (value instanceof String) {
                type = "string";
            } else if (value instanceof Number) {
                type = "number";
            } else if (value instanceof Boolean) {
                type = "boolean";
            } else if (value instanceof Map) {
                type = "object";
            } else if (value instanceof List) {
                type = "array";
            } else {
                type = "null";
            }
            types.put(entry.getKey().toLowerCase(Locale.ROOT), type);
        }
        return types;
    }
}
