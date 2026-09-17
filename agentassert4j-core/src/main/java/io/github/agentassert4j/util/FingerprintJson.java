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
     * 形态集合序列化（存储层 fingerprint 列的载荷契约）：有序数组，每元素一个指纹
     * 的规范 map；null/空集合序列化为 "[]"（读侧把 "[]" 映射回 null，与单指纹时代
     * "{}"↔null 的对称约定同构）。
     */
    public static String shapesToJson(List<DeterministicFingerprint> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return "[]";
        }
        List<Object> array = new ArrayList<>();
        for (DeterministicFingerprint fp : shapes) {
            array.add(toMap(fp));
        }
        return RecursiveJsonParser.serialize(array);
    }

    /**
     * 形态集合反序列化："[]" 或非法载荷返回 null（无基线）。单对象载荷是形态集合
     * 语义之前的旧行——按开发期「删库重建」纪律不兼容读取，就地响亮失败并指路，
     * 不静默误读。
     */
    public static List<DeterministicFingerprint> shapesFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Object parsed = RecursiveJsonParser.parse(json);
        if (parsed instanceof Map) {
            throw new IllegalStateException("Legacy single-shape fingerprint payload found in storage (written before shape-set baselines); reset the database and re-run establish to rebuild baselines.");
        }
        if (!(parsed instanceof List)) {
            return null;
        }
        List<DeterministicFingerprint> out = shapesFromMapList(parsed);
        return out.isEmpty() ? null : out;
    }

    /**
     * 验收包步骤的形态集合 map 形态（键 "fingerprint" 的数组载荷）。
     */
    public static List<Object> shapesToMapList(List<DeterministicFingerprint> shapes) {
        List<Object> array = new ArrayList<>();
        if (shapes == null) {
            return array;
        }
        for (DeterministicFingerprint fp : shapes) {
            array.add(toMap(fp));
        }
        return array;
    }

    /**
     * 验收包步骤的形态集合装载：数组载荷逐元素解析；单对象载荷是旧版包，
     * 响亮拒绝并指路重导出（预发布无兼容义务）。
     */
    public static List<DeterministicFingerprint> shapesFromMapList(Object value) {
        List<DeterministicFingerprint> out = new ArrayList<>();
        if (value instanceof Map) {
            throw new IllegalArgumentException("Legacy single-shape pack fingerprint (exported before shape-set baselines); re-export the pack under the current semantics.");
        }
        if (!(value instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                DeterministicFingerprint fp = fromMap((Map<?, ?>) item);
                if (fp != null) {
                    out.add(fp);
                }
            }
        }
        return out;
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
        fp.setToolParamTypes(RecursiveJsonParser.asStringMap(m.get("toolParamTypes")));
        fp.setOutputContentType(asString(m.get("outputContentType")));
        fp.setOutputFieldPaths(stringSet(m.get("outputFieldPaths")));
        fp.setOutputFieldTypeMap(RecursiveJsonParser.asStringMap(m.get("outputFieldTypeMap")));
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
