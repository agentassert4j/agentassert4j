package io.github.agentassert4j.util;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.RegexPattern;

import java.util.*;

/**
 * 指纹 JSON 映射 — DeterministicFingerprint 与 JSON 的双向转换。
 *
 * <p>键集与排序固定：容器一律 TreeMap/TreeSet/LinkedHashSet，
 * 同一指纹的序列化字节可复现。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public final class FingerprintJson {

    private FingerprintJson() {
    }

    /**
     * 序列化为 JSON；null 指纹序列化为 "{}"（与存储层 NOT NULL 空指纹约定一致）
     */
    public static String toJson(DeterministicFingerprint fp) {
        if (fp == null) {
            return "{}";
        }
        return RecursiveJsonParser.serialize(toMap(fp));
    }

    /**
     * 反序列化；空对象/非对象返回 null（与存储层 "{}"↔null 对写读对称约定一致）
     */
    public static DeterministicFingerprint fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) {
            return null;
        }
        return fromMap((Map<?, ?>) parsed);
    }

    public static Map<String, Object> toMap(DeterministicFingerprint fp) {
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
        return m;
    }

    public static DeterministicFingerprint fromMap(Map<?, ?> m) {
        if (m == null || m.isEmpty()) {
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
        fp.setRegexPatterns(regexPatternsFromList(m.get("regexPatterns")));
        fp.setDeclaredBehaviors(stringSet(m.get("declaredBehaviors")));
        fp.setHasError(asBool(m.get("hasError")));
        return fp;
    }

    private static List<Object> sortedList(Set<String> set) {
        return set == null ? new ArrayList<>() : new ArrayList<>(new TreeSet<>(set));
    }

    private static Map<String, Object> sortedStringMap(Map<String, String> map) {
        return map == null ? new TreeMap<>() : new TreeMap<>(map);
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

    private static List<RegexPattern> regexPatternsFromList(Object v) {
        List<RegexPattern> out = new ArrayList<>();
        if (!(v instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) v) {
            if (item instanceof Map) {
                Object pattern = ((Map<?, ?>) item).get("pattern");
                if (pattern != null) {
                    Object description = ((Map<?, ?>) item).get("description");
                    out.add(new RegexPattern(String.valueOf(pattern), description != null ? String.valueOf(description) : ""));
                }
            }
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

    private static String asString(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static boolean asBool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static int asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
