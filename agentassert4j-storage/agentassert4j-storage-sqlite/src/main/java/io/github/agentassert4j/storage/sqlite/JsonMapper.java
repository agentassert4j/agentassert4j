package io.github.agentassert4j.storage.sqlite;

import io.github.agentassert4j.model.*;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 模型 ↔ SQLite JSON 列 的映射层。
 *
 * <p>字符串转义与 JSON 语法的唯一真源是 core 的 RecursiveJsonParser（RFC 8259 完整转义，
 * 含 &lt;0x20 控制字符）；本类只在标准 Java 类型（Map/List/String/Number/Boolean）与模型字段
 * 之间搬运，不含手写转义/解析逻辑。</p>
 *
 * <p>序列化确定性：集合按自然序（TreeSet/TreeMap）落库，同内容指纹产生逐字节相同的 JSON。
 * 解析失败/空值退化为空集合或 null，不抛异常。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
final class JsonMapper {

    private JsonMapper() {
    }

    static String toolCallsToJson(List<ToolCall> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        List<Object> out = new ArrayList<>(list.size());
        for (ToolCall tc : list) {
            out.add(toolCallToMap(tc));
        }
        return RecursiveJsonParser.serialize(out);
    }

    static String turnsToJson(List<TurnContext> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        List<Object> out = new ArrayList<>(list.size());
        for (TurnContext tc : list) {
            out.add(turnContextToMap(tc));
        }
        return RecursiveJsonParser.serialize(out);
    }

    static List<ToolCall> toolCallsFromDb(String json) {
        List<ToolCall> result = new ArrayList<>();
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            ToolCall tc = new ToolCall();
            tc.setToolName(asString(m.get("toolName")));
            tc.setToolCallId(asString(m.get("toolCallId")));
            tc.setSuccess(asBool(m.get("success")));
            tc.setResult(asString(m.get("result")));
            tc.setArguments(asObjectMap(m.get("arguments")));
            tc.setArgTypes(asStringMap(m.get("argTypes")));
            result.add(tc);
        }
        return result;
    }

    static List<TurnContext> turnsFromDb(String json) {
        List<TurnContext> result = new ArrayList<>();
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            TurnContext tc = new TurnContext(asString(m.get("role")), asString(m.get("content")));
            tc.setToolCallId(asString(m.get("toolCallId")));
            tc.setToolName(asString(m.get("toolName")));
            result.add(tc);
        }
        return result;
    }

    /**
     * 指纹序列化；null 指纹写 "{}"（fingerprint 列有 NOT NULL 约束，
     * 读侧把 "{}" 映射回 null）。
     */
    static String fingerprintToJson(DeterministicFingerprint fp) {
        if (fp == null) {
            return "{}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolCallSet", sortedList(fp.getToolCallSet()));
        m.put("toolParamTypes", sortedStringMap(fp.getToolParamTypes()));
        m.put("outputContentType", fp.getOutputContentType());
        m.put("outputFieldPaths", sortedList(fp.getOutputFieldPaths()));
        m.put("outputFieldTypeMap", sortedStringMap(fp.getOutputFieldTypeMap()));
        m.put("textLengthMagnitude", fp.getTextLengthMagnitude());
        m.put("requiredKeywords", sortedList(fp.getRequiredKeywords()));
        m.put("forbiddenKeywords", sortedList(fp.getForbiddenKeywords()));
        m.put("regexPatterns", regexPatternsToList(fp.getRegexPatterns()));
        m.put("declaredBehaviors", sortedList(fp.getDeclaredBehaviors()));
        m.put("hasError", fp.isHasError());
        return RecursiveJsonParser.serialize(m);
    }

    static DeterministicFingerprint fingerprintFromDb(String json) {
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        if (m.isEmpty()) {
            return null;
        }
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(stringSet(m.get("toolCallSet")));
        fp.setToolParamTypes(asStringMap(m.get("toolParamTypes")));
        fp.setOutputContentType(asString(m.get("outputContentType")));
        fp.setOutputFieldPaths(stringSet(m.get("outputFieldPaths")));
        fp.setOutputFieldTypeMap(asStringMap(m.get("outputFieldTypeMap")));
        fp.setTextLengthMagnitude(asInt(m.get("textLengthMagnitude")));
        fp.setRequiredKeywords(stringSet(m.get("requiredKeywords")));
        fp.setForbiddenKeywords(stringSet(m.get("forbiddenKeywords")));
        fp.setRegexPatterns(regexPatternsFromDb(m.get("regexPatterns")));
        fp.setDeclaredBehaviors(stringSet(m.get("declaredBehaviors")));
        fp.setHasError(asBool(m.get("hasError")));
        return fp;
    }

    static SkillProfile toSkillProfile(ResultSet rs) throws SQLException {
        SkillProfile p = new SkillProfile();
        p.setSkillId(rs.getString("skill_id"));
        p.setGroupKey(rs.getString("group_key"));
        p.setSkillName(rs.getString("skill_name"));
        p.setSkillType(SkillType.valueOf(rs.getString("skill_type")));
        p.setFingerprint(fingerprintFromDb(rs.getString("fingerprint")));
        p.setCandidateFingerprint(fingerprintFromDb(rs.getString("candidate_fingerprint")));
        String status = rs.getString("baseline_status");
        p.setBaselineStatus(status != null ? BaselineStatus.valueOf(status) : BaselineStatus.BASELINE);
        p.setVersionTag(rs.getString("version_tag"));
        p.setAlgoVersion(rs.getString("algo_version"));
        p.setParamSignature(rs.getString("param_signature"));
        int sampleCount = rs.getInt("sample_count");
        p.setSampleCount(rs.wasNull() ? 0 : sampleCount);
        p.setApprovedBy(rs.getString("approved_by"));
        long approvedAt = rs.getLong("approved_at");
        p.setApprovedAt(rs.wasNull() ? null : approvedAt);
        p.setTotalRecords(rs.getInt("total_records"));
        return p;
    }

    static ArchivedBaseline toArchivedBaseline(ResultSet rs) throws SQLException {
        ArchivedBaseline ab = new ArchivedBaseline();
        ab.setSkillId(rs.getString("skill_id"));
        ab.setFingerprint(fingerprintFromDb(rs.getString("fingerprint")));
        ab.setVersionTag(rs.getString("version_tag"));
        ab.setAlgoVersion(rs.getString("algo_version"));
        ab.setApprovedBy(rs.getString("approved_by"));
        long approvedAt = rs.getLong("approved_at");
        ab.setApprovedAt(rs.wasNull() ? null : approvedAt);
        ab.setArchivedAt(rs.getLong("archived_at"));
        return ab;
    }

    private static Map<String, Object> toolCallToMap(ToolCall tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolName", tc.getToolName());
        m.put("toolCallId", tc.getToolCallId());
        m.put("success", tc.isSuccess());
        m.put("result", tc.getResult());
        m.put("arguments", tc.getArguments());
        m.put("argTypes", tc.getArgTypes());
        return m;
    }

    private static Map<String, Object> turnContextToMap(TurnContext tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", tc.getRole());
        m.put("content", tc.getContent());
        m.put("toolCallId", tc.getToolCallId());
        m.put("toolName", tc.getToolName());
        return m;
    }

    private static List<Object> regexPatternsToList(List<RegexPattern> patterns) {
        List<Object> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (RegexPattern p : patterns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pattern", p.getPattern());
            m.put("description", p.getDescription());
            out.add(m);
        }
        return out;
    }

    private static List<RegexPattern> regexPatternsFromDb(Object value) {
        List<RegexPattern> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            result.add(new RegexPattern(asString(m.get("pattern")), asString(m.get("description"))));
        }
        return result;
    }

    private static String asString(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static boolean asBool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static Map<String, Object> asObjectMap(Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Map<String, String> asStringMap(Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : null);
        }
        return out;
    }

    private static Set<String> stringSet(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (!(v instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) v) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static List<Object> sortedList(Set<String> set) {
        return set == null ? new ArrayList<>() : new ArrayList<>(new TreeSet<>(set));
    }

    private static Map<String, Object> sortedStringMap(Map<String, String> map) {
        return map == null ? new TreeMap<>() : new TreeMap<>(map);
    }

}
