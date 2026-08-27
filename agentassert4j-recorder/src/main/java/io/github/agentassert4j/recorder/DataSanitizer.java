package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;

import java.util.*;

/**
 * 配置式数据脱敏器 — 在 InteractionRecord 写入存储前执行脱敏。
 *
 * <p>脱敏范围：
 * <ul>
 *   <li>toolCalls.arguments — 按 sensitiveFields 匹配键名脱敏</li>
 *   <li>toolCalls.result — 按 sensitiveFields 匹配 JSON 键名脱敏</li>
 *   <li>userInput — 可配置，默认不脱敏（影响回归重放）</li>
 *   <li>modelResponse — 可配置，默认不脱敏</li>
 * </ul>
 *
 * <p>null 输入原样返回，匹配/解析失败不中断流程。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class DataSanitizer {

    private final List<String> sensitiveFields;
    private final SanitizeStrategy strategy;
    private final boolean sanitizeUserInput;
    private final boolean sanitizeModelResponse;

    /**
     * 小写化的敏感字段集合，用于忽略大小写匹配
     */
    private final Set<String> sensitiveFieldsLower;

    /**
     * 从 RecorderConfig 构建脱敏器。
     */
    public DataSanitizer(RecorderConfig config) {
        if (config == null) {
            this.sensitiveFields = Collections.emptyList();
            this.sensitiveFieldsLower = Collections.emptySet();
            this.strategy = SanitizeStrategy.MASK;
            this.sanitizeUserInput = false;
            this.sanitizeModelResponse = false;
            return;
        }
        this.sensitiveFields = config.getSensitiveFields();
        Set<String> lower = new HashSet<>();
        for (String field : config.getSensitiveFields()) {
            if (field != null) {
                lower.add(field.toLowerCase());
            }
        }
        this.sensitiveFieldsLower = lower;
        this.strategy = config.getSanitizeStrategy();
        this.sanitizeUserInput = config.isSanitizeUserInput();
        this.sanitizeModelResponse = config.isSanitizeModelResponse();
    }

    /**
     * 对 InteractionRecord 执行脱敏，返回新的脱敏后的记录。
     * 原始记录不被修改。
     * null 输入返回 null。
     */
    public InteractionRecord sanitize(InteractionRecord original) {
        if (original == null) {
            return null;
        }

        // 没有敏感字段配置，且不脱敏 userInput/modelResponse，直接返回
        if (sensitiveFieldsLower.isEmpty() && !sanitizeUserInput && !sanitizeModelResponse) {
            return original;
        }

        // 复制原始记录
        InteractionRecord copy = copyRecord(original);

        // 脱敏 userInput
        if (sanitizeUserInput && copy.getUserInput() != null) {
            copy.setUserInput(applyStrategy(copy.getUserInput()));
        }

        // 脱敏 modelResponse
        if (sanitizeModelResponse && copy.getModelResponse() != null) {
            copy.setModelResponse(applyStrategy(copy.getModelResponse()));
        }

        // 脱敏 toolCalls（原地修改已深拷贝的 ToolCall）
        if (copy.getToolCalls() != null && !copy.getToolCalls().isEmpty()) {
            for (int idx = 0; idx < copy.getToolCalls().size(); idx++) {
                ToolCall tc = copy.getToolCalls().get(idx);
                if (tc == null) continue;
                sanitizeToolCallInPlace(tc);
            }
        }

        return copy;
    }

    /**
     * 原地修改 ToolCall 的 arguments 和 result，执行脱敏。
     * 调用前必须确保 ToolCall 是深拷贝的副本，不能是原始对象。
     */
    private void sanitizeToolCallInPlace(ToolCall tc) {
        // 脱敏 arguments（Map<String, Object>）
        if (tc.getArguments() != null && !sensitiveFieldsLower.isEmpty()) {
            Map<String, Object> origArgs = tc.getArguments();
            Map<String, Object> sanitizedArgs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : origArgs.entrySet()) {
                if (isSensitiveField(entry.getKey())) {
                    if (strategy != SanitizeStrategy.DROP) {
                        sanitizedArgs.put(entry.getKey(), applyStrategy(String.valueOf(entry.getValue())));
                    }
                    // DROP 策略：不 put 该键
                } else {
                    sanitizedArgs.put(entry.getKey(), entry.getValue());
                }
            }
            tc.setArguments(sanitizedArgs);
        }

        // 脱敏 result（String，可能包含 JSON）
        if (tc.getResult() != null && !sensitiveFieldsLower.isEmpty()) {
            tc.setResult(sanitizeJsonString(tc.getResult()));
        }
    }

    /**
     * 对可能是 JSON 的字符串进行脱敏。
     * 尝试从 JSON 键值对中匹配敏感字段并替换值。
     * 如果不是合法 JSON，原样返回。
     */
    String sanitizeJsonString(String json) {
        if (json == null || json.isEmpty() || sensitiveFieldsLower.isEmpty()) {
            return json;
        }

        // 简单的 JSON 键值对脱敏：遍历敏感字段名，尝试匹配 "fieldName":"value" 模式
        String result = json;
        for (String field : sensitiveFields) {
            if (field == null || field.isEmpty()) {
                continue;
            }
            // 匹配 "fieldName" : "value" 或 "fieldName":"value"（忽略大小写的键名）
            // 使用逐字符扫描避免正则性能问题
            result = replaceJsonValue(result, field);
        }
        return result;
    }

    /**
     * 在 JSON 字符串中查找指定键名（忽略大小写）并替换其值。
     */
    private String replaceJsonValue(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyLen = searchKey.length();
        StringBuilder sb = new StringBuilder(json.length());
        int i = 0;
        while (i < json.length()) {
            // 查找下一个可能的键名位置
            int keyStart = indexOfIgnoreCase(json, searchKey, i);
            if (keyStart < 0) {
                sb.append(json, i, json.length());
                break;
            }

            // 检查键名前一个字符是否是 { 或 ,（确保是 JSON 键，不是值中的子串）
            if (!isJsonKeyPosition(json, keyStart)) {
                sb.append(json, i, keyStart + 1);
                i = keyStart + 1;
                continue;
            }

            // 复制键名之前的内容
            sb.append(json, i, keyStart + keyLen);

            // 跳过键名后的空白和冒号
            int j = keyStart + keyLen;
            while (j < json.length() && json.charAt(j) <= ' ') j++;
            if (j >= json.length() || json.charAt(j) != ':') {
                // 不是键值对格式，原样保留
                i = keyStart + keyLen;
                continue;
            }
            sb.append(json, keyStart + keyLen, j + 1); // 包含冒号
            j++; // 跳过冒号

            // 跳过冒号后的空白
            while (j < json.length() && json.charAt(j) <= ' ') j++;

            if (j >= json.length()) {
                i = j;
                continue;
            }

            // 提取并替换值
            if (json.charAt(j) == '"') {
                // 字符串值：找到结束引号（处理转义）
                int valueEnd = findStringEnd(json, j + 1);
                if (valueEnd < 0) {
                    // 无法找到结束引号，放弃脱敏
                    sb.append(json, j, json.length());
                    i = json.length();
                    break;
                }
                // 替换值
                if (strategy == SanitizeStrategy.DROP) {
                    // DROP：移除整个键值对（回溯删除键名）
                    removeLastKeyAndColon(sb, searchKey);
                    i = valueEnd + 1;
                    // 跳过可能的逗号和空白
                    while (i < json.length() && (json.charAt(i) <= ' ' || json.charAt(i) == ',')) i++;
                    continue;
                } else {
                    String originalValue = json.substring(j + 1, valueEnd);
                    sb.append("\"").append(applyStrategy(originalValue)).append("\"");
                    i = valueEnd + 1;
                }
            } else {
                // 非字符串值（数字/布尔/null）：找到值结束位置
                int valueEnd = findNonStringValueEnd(json, j);
                String originalValue = json.substring(j, valueEnd);
                if (strategy == SanitizeStrategy.DROP) {
                    removeLastKeyAndColon(sb, searchKey);
                    i = valueEnd;
                    while (i < json.length() && (json.charAt(i) <= ' ' || json.charAt(i) == ',')) i++;
                    continue;
                } else {
                    sb.append("\"").append(applyStrategy(originalValue)).append("\"");
                    i = valueEnd;
                }
            }
        }
        return sb.toString();
    }

    /**
     * 检查位置 pos 是否处于 JSON 键名的合法位置（前一个非空白字符是 { 或 ,）。
     */
    private boolean isJsonKeyPosition(String json, int pos) {
        for (int k = pos - 1; k >= 0; k--) {
            char c = json.charAt(k);
            if (c <= ' ') continue;
            return c == '{' || c == ',';
        }
        return false;
    }

    /**
     * 忽略大小写查找子串位置。
     */
    private int indexOfIgnoreCase(String text, String pattern, int fromIndex) {
        int patternLen = pattern.length();
        int limit = text.length() - patternLen;
        for (int i = fromIndex; i <= limit; i++) {
            boolean match = true;
            for (int p = 0; p < patternLen; p++) {
                char tc = text.charAt(i + p);
                char pc = pattern.charAt(p);
                if (tc != pc && Character.toLowerCase(tc) != Character.toLowerCase(pc)) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * 找到 JSON 字符串值的结束双引号位置（处理 \" 转义）。
     *
     * @param start 字符串值内容的起始位置（不含开头的 "）
     * @return 结束 " 的位置，-1 表示未找到
     */
    private int findStringEnd(String json, int start) {
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i += 2; // 跳过转义字符
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * 找到非字符串值的结束位置（数字、布尔、null）。
     */
    private int findNonStringValueEnd(String json, int start) {
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']' || c <= ' ') {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * 从 StringBuilder 中删除最后一个添加的键值对（包括键名前的空白和逗号）。
     * 用于 DROP 策略回溯。处理后不会产生尾逗号或前逗号。
     */
    private void removeLastKeyAndColon(StringBuilder sb, String searchKey) {
        int keyIdx = sb.lastIndexOf(searchKey);
        if (keyIdx < 0) {
            return;
        }
        // 回溯删除键名前的空白和逗号（或 { 后的空白）
        int deleteStart = keyIdx;
        for (int k = keyIdx - 1; k >= 0; k--) {
            char c = sb.charAt(k);
            if (c <= ' ') {
                deleteStart = k;
            } else if (c == ',') {
                deleteStart = k;
                break;
            } else {
                break;
            }
        }
        sb.delete(deleteStart, sb.length());
    }

    /**
     * 判断字段名是否匹配敏感字段（忽略大小写）。
     */
    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return sensitiveFieldsLower.contains(fieldName.toLowerCase());
    }

    /**
     * 按策略替换敏感值。委托给 SanitizeStrategy.apply()。
     */
    private String applyStrategy(String value) {
        return strategy.apply(value);
    }

    /**
     * 深拷贝 InteractionRecord。
     * 必须覆盖全部字段：漏拷贝的字段会在脱敏路径上静默丢失（捕获数据不可重建）。
     */
    private InteractionRecord copyRecord(InteractionRecord original) {
        InteractionRecord copy = new InteractionRecord();
        copy.setRecordId(original.getRecordId());
        copy.setTimestamp(original.getTimestamp());
        copy.setSeq(original.getSeq());
        copy.setTemplateId(original.getTemplateId());
        copy.setTemplateHash(original.getTemplateHash());
        copy.setVariablesFingerprint(original.getVariablesFingerprint());
        copy.setApiProtocol(original.getApiProtocol());
        copy.setProvider(original.getProvider());
        copy.setModel(original.getModel());
        copy.setServedModel(original.getServedModel());
        copy.setEndpoint(original.getEndpoint());
        copy.setUserInput(original.getUserInput());
        copy.setTurnIndex(original.getTurnIndex());
        copy.setToolsDefinition(original.getToolsDefinition());
        copy.setSamplingParams(original.getSamplingParams());
        copy.setModelRequestRaw(original.getModelRequestRaw());
        copy.setFinishReason(original.getFinishReason());
        copy.setModelResponse(original.getModelResponse());
        copy.setModelResponseRaw(original.getModelResponseRaw());
        copy.setInputTokens(original.getInputTokens());
        copy.setOutputTokens(original.getOutputTokens());
        copy.setCacheReadTokens(original.getCacheReadTokens());
        copy.setCacheWriteTokens(original.getCacheWriteTokens());
        copy.setReasoningTokens(original.getReasoningTokens());
        copy.setUsageRaw(original.getUsageRaw());
        copy.setLatencyMs(original.getLatencyMs());
        copy.setTtftMs(original.getTtftMs());
        copy.setCostUsd(original.getCostUsd());
        copy.setHasToolCalls(original.isHasToolCalls());
        copy.setSessionId(original.getSessionId());
        copy.setSkillId(original.getSkillId());
        copy.setGroupKey(original.getGroupKey());
        copy.setMultimodalInput(original.isMultimodalInput());
        copy.setMultimodalContent(original.getMultimodalContent());
        copy.setMetadata(original.getMetadata());
        copy.setRecorderVersion(original.getRecorderVersion());

        // 深拷贝 toolCalls
        if (original.getToolCalls() != null) {
            List<ToolCall> callsCopy = new ArrayList<>();
            for (ToolCall tc : original.getToolCalls()) {
                ToolCall tcCopy = new ToolCall();
                tcCopy.setToolName(tc.getToolName());
                tcCopy.setToolCallId(tc.getToolCallId());
                tcCopy.setSuccess(tc.isSuccess());
                tcCopy.setArgTypes(tc.getArgTypes() != null ? new HashMap<>(tc.getArgTypes()) : null);
                tcCopy.setArguments(tc.getArguments() != null ? new LinkedHashMap<>(tc.getArguments()) : null);
                tcCopy.setResult(tc.getResult());
                callsCopy.add(tcCopy);
            }
            copy.setToolCalls(callsCopy);
        }

        // 深拷贝 previousTurns
        if (original.getPreviousTurns() != null) {
            copy.setPreviousTurns(new ArrayList<>(original.getPreviousTurns()));
        }

        return copy;
    }
}
