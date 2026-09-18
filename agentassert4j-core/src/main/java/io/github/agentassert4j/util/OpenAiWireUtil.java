package io.github.agentassert4j.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat wire 词形单源 — 记录列（samplingParams / toolsDefinition）与重放请求
 * 共用的形状约定在此一处定义。
 *
 * <p>各框架适配 mapper 把框架侧参数与工具定义映射为该形状落库；键名与信封结构
 * 是跨面等价的冻结契约（与三协议摄取、重放发射侧同形），新增适配线只消费本类，
 * 不再各自手写键名字面量。JSON-Schema 键词供「类型化 schema 树 → 形状映射」的
 * 转换器（LangChain4j 一类）共用。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
public final class OpenAiWireUtil {

    /**
     * 采样参数的规范键。调用方逐项判空后组装，未设置的项不落键。
     */
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_TOP_P = "top_p";
    public static final String KEY_TOP_K = "top_k";
    public static final String KEY_MAX_TOKENS = "max_tokens";
    public static final String KEY_FREQUENCY_PENALTY = "frequency_penalty";
    public static final String KEY_PRESENCE_PENALTY = "presence_penalty";
    public static final String KEY_STOP = "stop";

    /**
     * 工具定义 function 信封的键与类型值。
     */
    public static final String KEY_NAME = "name";
    public static final String KEY_DESCRIPTION = "description";
    public static final String KEY_PARAMETERS = "parameters";
    public static final String KEY_FUNCTION = "function";
    public static final String TOOL_TYPE_FUNCTION = "function";

    /**
     * JSON-Schema 的 wire 键词与类型名——类型化 schema 转换器的输出词表。
     */
    public static final String SCHEMA_TYPE = "type";
    public static final String SCHEMA_PROPERTIES = "properties";
    public static final String SCHEMA_REQUIRED = "required";
    public static final String SCHEMA_ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String SCHEMA_ITEMS = "items";
    public static final String SCHEMA_ENUM = "enum";
    public static final String SCHEMA_ANY_OF = "anyOf";
    public static final String SCHEMA_REF = "$ref";
    public static final String SCHEMA_DEFS = "$defs";
    public static final String SCHEMA_STRING = "string";
    public static final String SCHEMA_INTEGER = "integer";
    public static final String SCHEMA_NUMBER = "number";
    public static final String SCHEMA_BOOLEAN = "boolean";
    public static final String SCHEMA_NULL = "null";
    public static final String SCHEMA_OBJECT = "object";
    public static final String SCHEMA_ARRAY = "array";

    private OpenAiWireUtil() {
    }

    /**
     * 组装采样参数的 wire 映射：null 项跳过，空 stop 序列跳过；
     * 全部缺省时返回空映射（调用方据此不落 samplingParams 列）。
     */
    public static Map<String, Object> sampling(Double temperature, Double topP, Integer topK, Integer maxTokens, Double frequencyPenalty, Double presencePenalty, List<String> stopSequences) {
        Map<String, Object> sampling = new LinkedHashMap<>();
        if (temperature != null) {
            sampling.put(KEY_TEMPERATURE, temperature);
        }
        if (topP != null) {
            sampling.put(KEY_TOP_P, topP);
        }
        if (topK != null) {
            sampling.put(KEY_TOP_K, topK);
        }
        if (maxTokens != null) {
            sampling.put(KEY_MAX_TOKENS, maxTokens);
        }
        if (frequencyPenalty != null) {
            sampling.put(KEY_FREQUENCY_PENALTY, frequencyPenalty);
        }
        if (presencePenalty != null) {
            sampling.put(KEY_PRESENCE_PENALTY, presencePenalty);
        }
        if (stopSequences != null && !stopSequences.isEmpty()) {
            sampling.put(KEY_STOP, stopSequences);
        }
        return sampling;
    }

    /**
     * 组装单个工具定义的 function 信封：description 缺省补空串，
     * parameters 为已解析的形状对象（空 schema 传空映射）。
     */
    public static Map<String, Object> functionTool(String name, String description, Object parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put(KEY_NAME, name);
        function.put(KEY_DESCRIPTION, description != null ? description : "");
        function.put(KEY_PARAMETERS, parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put(SCHEMA_TYPE, TOOL_TYPE_FUNCTION);
        tool.put(KEY_FUNCTION, function);
        return tool;
    }
}
