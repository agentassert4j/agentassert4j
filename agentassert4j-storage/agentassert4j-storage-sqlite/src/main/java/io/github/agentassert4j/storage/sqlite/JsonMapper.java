package io.github.agentassert4j.storage.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.BaselineStatus;
import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;

/**
 * JSON 序列化工具 — 手写简易实现，零外部依赖。
 *
 * <p>仅用于 SQLite 存储层的字段映射。</p>
 *
 * <p><b>技术债</b>：本类实现了完整的手写 JSON 序列化/反序列化逻辑（~570行）。
 * core util 包中的 RecursiveJsonParser 已完成（支持完整 RFC 8259），
 * 后续应重构为使用 RecursiveJsonParser.parse()/serialize() 替代本类的手写解析，
 * 大幅减少代码量并消除 SqliteStorageRepository 中的重复 JSON 解析代码。
 * 重构时机：Phase 1 核心算法链路完成后统一进行。</p>
 */
final class JsonMapper {

    private JsonMapper() {}

    // ======================== InteractionRecord ========================

    static String toJson(InteractionRecord r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        stringField(sb, "recordId", r.getRecordId());
        sb.append(","); longField(sb, "timestamp", r.getTimestamp());
        sb.append(","); longField(sb, "seq", r.getSeq());
        sb.append(","); stringField(sb, "templateHash", r.getTemplateHash());
        sb.append(","); stringField(sb, "userInput", r.getUserInput());
        sb.append(","); intField(sb, "turnIndex", r.getTurnIndex());
        sb.append(","); stringField(sb, "modelResponse", r.getModelResponse());
        sb.append(","); boolField(sb, "hasToolCalls", r.isHasToolCalls());
        sb.append(","); longField(sb, "latencyMs", r.getLatencyMs());
        sb.append(","); stringField(sb, "sessionId", r.getSessionId());
        sb.append(","); stringField(sb, "skillId", r.getSkillId());
        sb.append(","); boolField(sb, "multimodalInput", r.isMultimodalInput());
        sb.append(","); stringField(sb, "multimodalContent", r.getMultimodalContent());
        sb.append(",\"toolCalls\":").append(toJsonArray(r.getToolCalls(), JsonMapper::toJson));
        sb.append(",\"previousTurns\":").append(toJsonArray(r.getPreviousTurns(), JsonMapper::toJson));
        sb.append("}");
        return sb.toString();
    }

    static InteractionRecord fromJsonToRecord(String json) {
        if (json == null || json.isEmpty()) return new InteractionRecord();
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(getString(json, "recordId"));
        r.setTimestamp(getLong(json, "timestamp"));
        r.setSeq(getLong(json, "seq"));
        r.setTemplateHash(getString(json, "templateHash"));
        r.setUserInput(getString(json, "userInput"));
        r.setTurnIndex(getInt(json, "turnIndex"));
        r.setModelResponse(getString(json, "modelResponse"));
        r.setHasToolCalls(getBool(json, "hasToolCalls"));
        r.setLatencyMs(getLong(json, "latencyMs"));
        r.setSessionId(getString(json, "sessionId"));
        r.setSkillId(getString(json, "skillId"));
        r.setMultimodalInput(getBool(json, "multimodalInput"));
        r.setMultimodalContent(getString(json, "multimodalContent"));
        r.setToolCalls(parseToolCalls(json));
        r.setPreviousTurns(parseTurnContexts(json));
        return r;
    }

    // ======================== ToolCall ========================

    private static String toJson(ToolCall tc) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{");
        stringField(sb, "toolName", tc.getToolName());
        sb.append(","); stringField(sb, "toolCallId", tc.getToolCallId());
        sb.append(","); boolField(sb, "success", tc.isSuccess());
        sb.append(","); stringField(sb, "result", tc.getResult());
        sb.append(",\"arguments\":").append(mapToStringObject(tc.getArguments()));
        sb.append(",\"argTypes\":").append(mapToStringString(tc.getArgTypes()));
        sb.append("}");
        return sb.toString();
    }

    private static List<ToolCall> parseToolCalls(String json) {
        String arr = extractArray(json, "toolCalls");
        if (arr == null) return new ArrayList<>();
        List<String> items = splitArrayItems(arr);
        List<ToolCall> result = new ArrayList<>();
        for (String item : items) {
            ToolCall tc = new ToolCall();
            tc.setToolName(getString(item, "toolName"));
            tc.setToolCallId(getString(item, "toolCallId"));
            tc.setSuccess(getBool(item, "success"));
            tc.setResult(getString(item, "result"));
            tc.setArguments(stringObjectMapFromString(extractObject(item, "arguments")));
            tc.setArgTypes(stringStringMapFromString(extractObject(item, "argTypes")));
            result.add(tc);
        }
        return result;
    }

    // ======================== TurnContext ========================

    private static String toJson(TurnContext tc) {
        return "{\"role\":\"" + escape(tc.getRole()) + "\"," +
               "\"content\":\"" + escape(tc.getContent()) + "\"," +
               "\"toolCallId\":" + (tc.getToolCallId() != null ? "\"" + escape(tc.getToolCallId()) + "\"" : "null") + "," +
               "\"toolName\":" + (tc.getToolName() != null ? "\"" + escape(tc.getToolName()) + "\"" : "null") + "}";
    }

    private static List<TurnContext> parseTurnContexts(String json) {
        String arr = extractArray(json, "previousTurns");
        if (arr == null) return new ArrayList<>();
        List<String> items = splitArrayItems(arr);
        List<TurnContext> result = new ArrayList<>();
        for (String item : items) {
            TurnContext tc = new TurnContext();
            tc.setRole(getString(item, "role"));
            tc.setContent(getString(item, "content"));
            tc.setToolCallId(getNullableString(item, "toolCallId"));
            tc.setToolName(getNullableString(item, "toolName"));
            result.add(tc);
        }
        return result;
    }

    // ======================== DeterministicFingerprint ========================

    static String toJson(DeterministicFingerprint fp) {
        if (fp == null) return "{}";
        StringBuilder sb = new StringBuilder(256);
        sb.append("{");
        sb.append("\"toolCallSet\":").append(setToArray(fp.getToolCallSet()));
        sb.append(",\"toolParamTypes\":").append(mapToStringString(fp.getToolParamTypes()));
        sb.append(",\"toolParamRequired\":").append(mapToBoolValues(fp.getToolParamRequired()));
        sb.append(","); stringField(sb, "outputContentType", fp.getOutputContentType());
        sb.append(",\"outputFieldPaths\":").append(setToArray(fp.getOutputFieldPaths()));
        sb.append(",\"outputFieldTypeMap\":").append(mapToStringString(fp.getOutputFieldTypeMap()));
        sb.append(","); intField(sb, "textLengthMagnitude", fp.getTextLengthMagnitude());
        sb.append(",\"requiredKeywords\":").append(setToArray(fp.getRequiredKeywords()));
        sb.append(",\"forbiddenKeywords\":").append(setToArray(fp.getForbiddenKeywords()));
        sb.append(",\"regexPatterns\":").append(regexPatternsToArray(fp.getRegexPatterns()));
        sb.append(",\"declaredBehaviors\":").append(setToArray(fp.getDeclaredBehaviors()));
        sb.append(","); boolField(sb, "hasError", fp.isHasError());
        sb.append("}");
        return sb.toString();
    }

    static DeterministicFingerprint fromJsonToFingerprint(String json) {
        if (json == null || json.trim().isEmpty() || json.equals("{}")) return null;
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(parseStringSet(json, "toolCallSet"));
        fp.setToolParamTypes(stringStringMapFromString(extractObject(json, "toolParamTypes")));
        fp.setToolParamRequired(boolMapFromString(extractObject(json, "toolParamRequired")));
        fp.setOutputContentType(getString(json, "outputContentType"));
        fp.setOutputFieldPaths(parseStringSet(json, "outputFieldPaths"));
        fp.setOutputFieldTypeMap(stringStringMapFromString(extractObject(json, "outputFieldTypeMap")));
        fp.setTextLengthMagnitude(getInt(json, "textLengthMagnitude"));
        fp.setRequiredKeywords(parseStringSet(json, "requiredKeywords"));
        fp.setForbiddenKeywords(parseStringSet(json, "forbiddenKeywords"));
        fp.setRegexPatterns(parseRegexPatterns(json));
        fp.setDeclaredBehaviors(parseStringSet(json, "declaredBehaviors"));
        fp.setHasError(getBool(json, "hasError"));
        return fp;
    }

    // ======================== SkillProfile from ResultSet ========================

    static SkillProfile toSkillProfile(ResultSet rs) throws SQLException {
        SkillProfile p = new SkillProfile();
        p.setSkillId(rs.getString("skill_id"));
        p.setGroupKey(rs.getString("group_key"));
        p.setSkillName(rs.getString("skill_name"));
        p.setSkillType(SkillType.valueOf(rs.getString("skill_type")));
        p.setFingerprint(fromJsonToFingerprint(rs.getString("fingerprint")));
        p.setCandidateFingerprint(fromJsonToFingerprint(rs.getString("candidate_fingerprint")));
        String status = rs.getString("baseline_status");
        p.setBaselineStatus(status != null ? BaselineStatus.valueOf(status) : BaselineStatus.BASELINE);
        p.setVersionTag(rs.getString("version_tag"));
        p.setAlgoVersion(rs.getString("algo_version"));
        p.setParamSignature(rs.getString("param_signature"));
        int sampleCount = rs.getInt("sample_count");
        p.setSampleCount(rs.wasNull() ? null : sampleCount);
        p.setApprovedBy(rs.getString("approved_by"));
        long approvedAt = rs.getLong("approved_at");
        p.setApprovedAt(rs.wasNull() ? null : approvedAt);
        p.setTotalRecords(rs.getInt("total_records"));
        return p;
    }

    // ======================== ArchivedBaseline from ResultSet ========================

    static ArchivedBaseline toArchivedBaseline(ResultSet rs) throws SQLException {
        ArchivedBaseline ab = new ArchivedBaseline();
        ab.setSkillId(rs.getString("skill_id"));
        ab.setFingerprint(fromJsonToFingerprint(rs.getString("fingerprint")));
        ab.setVersionTag(rs.getString("version_tag"));
        ab.setArchivedAt(rs.getLong("archived_at"));
        return ab;
    }

    // ======================== RegexPattern ========================

    private static String regexPatternsToArray(List<RegexPattern> patterns) {
        if (patterns == null || patterns.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sb.append(",");
            RegexPattern p = patterns.get(i);
            sb.append("{\"pattern\":\"").append(escape(p.getPattern())).append("\",");
            sb.append("\"description\":\"").append(escape(p.getDescription())).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<RegexPattern> parseRegexPatterns(String json) {
        String arr = extractArray(json, "regexPatterns");
        if (arr == null) return new ArrayList<>();
        List<String> items = splitArrayItems(arr);
        List<RegexPattern> result = new ArrayList<>();
        for (String item : items) {
            result.add(new RegexPattern(getString(item, "pattern"), getString(item, "description")));
        }
        return result;
    }

    // ======================== 基础 JSON 辅助方法 ========================

    private static void stringField(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":");
        sb.append(value != null ? "\"" + escape(value) + "\"" : "null");
    }

    private static void intField(StringBuilder sb, String key, int value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void longField(StringBuilder sb, String key, long value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void boolField(StringBuilder sb, String key, boolean value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static String setToArray(Set<String> set) {
        if (set == null || set.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (String s : set) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(s)).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static String mapToStringString(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String mapToStringObject(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(escape(v.toString())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String mapToBoolValues(Map<String, Boolean> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> String toJsonArray(List<T> list, java.util.function.Function<T, String> serializer) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(serializer.apply(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // ======================== JSON 解析辅助 ========================

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    static String getString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '\\' && end + 1 < json.length()) { end += 2; continue; }
            if (json.charAt(end) == '"') break;
            end++;
        }
        return unescape(json.substring(start, end));
    }

    private static String getNullableString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        if (start < json.length() && json.startsWith("null", start)) return null;
        if (start < json.length() && json.charAt(start) == '"') {
            start++;
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) { end += 2; continue; }
                if (json.charAt(end) == '"') break;
                end++;
            }
            return unescape(json.substring(start, end));
        }
        return null;
    }

    private static int getInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }

    private static long getLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0L;
        start += pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return 0L; }
    }

    private static boolean getBool(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return false;
        start += pattern.length();
        return json.startsWith("true", start);
    }

    private static String extractArray(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        if (start >= json.length() || json.charAt(start) != '[') return null;
        int depth = 1;
        int end = start + 1;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '"') { end++; while (end < json.length() && json.charAt(end) != '"') { if (json.charAt(end) == '\\') end++; end++; } }
            end++;
        }
        return json.substring(start + 1, end - 1);
    }

    private static String extractObject(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        if (start >= json.length() || json.charAt(start) != '{') return null;
        int depth = 1;
        int end = start + 1;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') { end++; while (end < json.length() && json.charAt(end) != '"') { if (json.charAt(end) == '\\') end++; end++; } }
            end++;
        }
        return json.substring(start + 1, end - 1);
    }

    private static List<String> splitArrayItems(String arrayContent) {
        List<String> items = new ArrayList<>();
        if (arrayContent == null || arrayContent.trim().isEmpty()) return items;
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String item = arrayContent.substring(start, i).trim();
                if (!item.isEmpty()) items.add(item);
                start = i + 1;
            }
        }
        String last = arrayContent.substring(start).trim();
        if (!last.isEmpty()) items.add(last);
        return items;
    }

    private static Set<String> parseStringSet(String json, String key) {
        String arr = extractArray(json, key);
        if (arr == null || arr.trim().isEmpty()) return new HashSet<>();
        List<String> items = splitArrayItems(arr);
        Set<String> result = new LinkedHashSet<>();
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            result.add(unescape(trimmed));
        }
        return result;
    }

    private static Map<String, String> stringStringMapFromString(String objectContent) {
        Map<String, String> map = new LinkedHashMap<>();
        if (objectContent == null || objectContent.trim().isEmpty()) return map;
        // 解析 "key":"value" 对
        int i = 0;
        while (i < objectContent.length()) {
            // 找 key
            int keyStart = objectContent.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = objectContent.indexOf('"', keyStart + 1);
            // 处理转义
            while (keyEnd > 0 && objectContent.charAt(keyEnd - 1) == '\\') keyEnd = objectContent.indexOf('"', keyEnd + 1);
            if (keyEnd < 0) break;
            String mapKey = unescape(objectContent.substring(keyStart + 1, keyEnd));

            // 找 value（跳过冒号）
            int colon = objectContent.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int valStart = colon + 1;
            while (valStart < objectContent.length() && objectContent.charAt(valStart) == ' ') valStart++;
            if (valStart >= objectContent.length()) break;

            String mapVal;
            if (objectContent.charAt(valStart) == '"') {
                int valEnd = valStart + 1;
                while (valEnd < objectContent.length()) {
                    if (objectContent.charAt(valEnd) == '\\' && valEnd + 1 < objectContent.length()) { valEnd += 2; continue; }
                    if (objectContent.charAt(valEnd) == '"') break;
                    valEnd++;
                }
                mapVal = unescape(objectContent.substring(valStart + 1, valEnd));
                i = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < objectContent.length() && objectContent.charAt(valEnd) != ',' && objectContent.charAt(valEnd) != '}') valEnd++;
                mapVal = objectContent.substring(valStart, valEnd).trim();
                i = valEnd;
            }
            map.put(mapKey, mapVal);
        }
        return map;
    }

    private static Map<String, Boolean> boolMapFromString(String objectContent) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        if (objectContent == null || objectContent.trim().isEmpty()) return map;
        int i = 0;
        while (i < objectContent.length()) {
            int keyStart = objectContent.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = objectContent.indexOf('"', keyStart + 1);
            while (keyEnd > 0 && objectContent.charAt(keyEnd - 1) == '\\') keyEnd = objectContent.indexOf('"', keyEnd + 1);
            if (keyEnd < 0) break;
            String mapKey = unescape(objectContent.substring(keyStart + 1, keyEnd));
            int colon = objectContent.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int valStart = colon + 1;
            while (valStart < objectContent.length() && objectContent.charAt(valStart) == ' ') valStart++;
            if (valStart >= objectContent.length()) break;
            boolean val = objectContent.startsWith("true", valStart);
            map.put(mapKey, val);
            i = valStart + (val ? 4 : 5);
        }
        return map;
    }

    private static Map<String, Object> stringObjectMapFromString(String objectContent) {
        // TODO: [简化实现] Map<String, Object> 的反序列化是简化版本，仅支持 String/Boolean/Long，
        //       不支持嵌套对象或 Double。待重构为 RecursiveJsonParser 后自动支持完整类型推断
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, String> stringMap = stringStringMapFromString(objectContent);
        for (Map.Entry<String, String> e : stringMap.entrySet()) {
            String v = e.getValue();
            if ("null".equals(v)) map.put(e.getKey(), null);
            else if ("true".equals(v)) map.put(e.getKey(), Boolean.TRUE);
            else if ("false".equals(v)) map.put(e.getKey(), Boolean.FALSE);
            else {
                try { map.put(e.getKey(), Long.parseLong(v)); }
                catch (NumberFormatException ex) { map.put(e.getKey(), v); }
            }
        }
        return map;
    }
}
