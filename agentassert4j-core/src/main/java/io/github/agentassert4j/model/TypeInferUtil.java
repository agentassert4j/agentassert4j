package io.github.agentassert4j.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 值类型推断工具 — 从实际参数值推断类型。
 * 优先级 2 策略：SDK 未提供 argTypes 时的退路。
 *
 * <p><b>TODO: [包位置]</b> 本类是工具类，按规范应放在 {@code util/} 包。
 * 但因与 ToolCall.argTypes 紧密关联，方案文档示意放在 model 包。
 * 待 Phase 2 后评估是否迁移至 util 包。</p>
 */
public final class TypeInferUtil {

    private TypeInferUtil() {}

    /**
     * 从实际参数值推断类型签名。
     * "ORD-001" → String, 123 → Number, true → Boolean
     */
    public static Map<String, String> inferTypesFromValues(Map<String, Object> args) {
        Map<String, String> types = new HashMap<>();
        if (args == null) return types;
        args.forEach((k, v) -> {
            if (v instanceof Number) types.put(k, "Number");
            else if (v instanceof Boolean) types.put(k, "Boolean");
            else types.put(k, "String");
        });
        return types;
    }
}
