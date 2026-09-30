package io.github.agentassert4j.util;

import io.github.agentassert4j.model.AcceptancePack;
import io.github.agentassert4j.model.BaselineStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验收包编解码 — AcceptancePack 与 `agentassert4j.acceptance-pack/1` JSON 的双向转换。
 *
 * <p>反序列化即版本守卫的一半：schema 字段不符抛 IllegalArgumentException（调用方
 * 转译为退出码 2）；判定语义版本的守卫在 verify 侧（需要当前引擎版本对照）。
 * 键名与嵌套形态固定，字面输出由测试锁定。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public final class PackCodec {

    private PackCodec() {
    }

    public static String toJson(AcceptancePack pack) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", AcceptancePack.SCHEMA);
        Map<String, Object> meta = new LinkedHashMap<>();
        AcceptancePack.PackMeta m = pack.getMeta();
        meta.put("exportedAt", m.getExportedAt());
        meta.put("exportedBy", m.getExportedBy());
        meta.put("judgmentSemantics", m.getJudgmentSemantics());
        meta.put("storageSchemaVersion", m.getStorageSchemaVersion());
        meta.put("frameworkVersion", m.getFrameworkVersion());
        meta.put("servedModel", m.getServedModel());
        meta.put("codeRef", m.getCodeRef());
        root.put("meta", meta);
        List<Object> tasks = new ArrayList<>();
        for (AcceptancePack.PackTask task : pack.getTasks()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskKey", task.getTaskKey());
            t.put("requestText", task.getRequestText());
            t.put("declared", task.isDeclared());
            t.put("baselineTime", task.getBaselineTime());
            t.put("unadjudicatedSteps", task.getUnadjudicatedSteps());
            List<Object> steps = new ArrayList<>();
            int order = 1;
            for (BaselineStep step : task.getSteps()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("order", order++);
                s.put("invocationKey", step.getInvocationKey());
                s.put("recordId", step.getRecordId());
                // 形态集合数组载荷：首元素 = establish 种子锚，其余为 accept 追加的认可形态
                s.put("fingerprint", FingerprintJson.shapesToMapList(step.getFingerprints()));
                if (step.getBaselineVersion() != null) {
                    s.put("baselineVersion", step.getBaselineVersion());
                }
                if (step.getSampleInput() != null) {
                    s.put("sampleInput", step.getSampleInput());
                }
                if (step.getSampleOutput() != null) {
                    s.put("sampleOutput", step.getSampleOutput());
                }
                steps.add(s);
            }
            t.put("steps", steps);
            tasks.add(t);
        }
        root.put("tasks", tasks);
        if (pack.getRules() != null && !pack.getRules().isEmpty()) {
            root.put("rules", pack.getRules());
        }
        // 完整性锚最后写入：对「meta(除锚)+tasks+rules」的规范序列化取摘要——
        // 锚自身不能进被摘要的载荷，否则自引用
        meta.put("integrityHash", computeIntegrityHash(root));
        return RecursiveJsonParser.serialize(root);
    }

    /**
     * 完整性锚 = 载荷（meta 除 integrityHash + tasks + rules）按写入序规范序列化后的
     * SHA-256。防的是「从包里删任务/改指纹」这类静默篡改；导出方与验收方用同一算法
     * 复算，不符即拒。JSON 解析丢类型信息（如整型变浮点）会造成误报，故比较发生在
     * 字符串层（serialize 输出稳定）。
     */
    /**
     * 解析并校验包根形态（JSON 对象），供 fromJson 与完整性锚复算共用同一解析——
     * 两条路径对同一字节串必须看到同一棵树。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseRoot(String json) {
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("The acceptance pack is not a JSON object.");
        }
        return (Map<String, Object>) parsed;
    }

    public static String computeIntegrityHash(Map<String, Object> root) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) canonicalized(root);
        if (payload.get("meta") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metaCopy = new LinkedHashMap<>((Map<String, Object>) payload.get("meta"));
            metaCopy.remove("integrityHash");
            payload.put("meta", metaCopy);
        }
        return HashUtil.sha256(RecursiveJsonParser.serialize(payload));
    }

    /**
     * 规范化：键全排序 + 数值统一 BigDecimal 表示——JSON 工具链对同一文档的
     * 重序列化（键序倒置、1 与 1.0、空白差异）是等值变换，不得当成篡改；
     * 数组序保留（步骤顺序是业务语义）。
     */
    private static Object canonicalized(Object node) {
        if (node instanceof Map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalized(entry.getValue()));
            }
            return sorted;
        }
        if (node instanceof List) {
            List<Object> items = new ArrayList<>();
            for (Object item : (List<?>) node) {
                items.add(canonicalized(item));
            }
            return items;
        }
        if (node instanceof Number && !(node instanceof java.math.BigDecimal)) {
            return new java.math.BigDecimal(node.toString());
        }
        return node;
    }

    public static AcceptancePack fromJson(String json) {
        Map<?, ?> root = parseRoot(json);
        String schema = asString(root.get("schema"));
        if (!AcceptancePack.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported acceptance pack schema: " + schema + " (expected " + AcceptancePack.SCHEMA + ").");
        }
        AcceptancePack pack = new AcceptancePack();
        if (root.get("rules") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> rules = (Map<String, Object>) root.get("rules");
            pack.setRules(rules);
        }
        Map<?, ?> meta = root.get("meta") instanceof Map ? (Map<?, ?>) root.get("meta") : null;
        if (meta != null) {
            AcceptancePack.PackMeta m = new AcceptancePack.PackMeta();
            m.setExportedAt(meta.get("exportedAt") instanceof Number ? ((Number) meta.get("exportedAt")).longValue() : 0L);
            m.setExportedBy(asString(meta.get("exportedBy")));
            m.setJudgmentSemantics(asString(meta.get("judgmentSemantics")));
            m.setStorageSchemaVersion(meta.get("storageSchemaVersion") instanceof Number ? ((Number) meta.get("storageSchemaVersion")).intValue() : 0);
            m.setFrameworkVersion(asString(meta.get("frameworkVersion")));
            m.setServedModel(asString(meta.get("servedModel")));
            m.setCodeRef(asString(meta.get("codeRef")));
            m.setIntegrityHash(asString(meta.get("integrityHash")));
            pack.setMeta(m);
        }
        if (root.get("tasks") instanceof List) {
            for (Object t : (List<?>) root.get("tasks")) {
                if (!(t instanceof Map)) {
                    continue;
                }
                Map<?, ?> tm = (Map<?, ?>) t;
                AcceptancePack.PackTask task = new AcceptancePack.PackTask();
                task.setTaskKey(asString(tm.get("taskKey")));
                task.setRequestText(asString(tm.get("requestText")));
                task.setDeclared(Boolean.TRUE.equals(tm.get("declared")));
                task.setBaselineTime(tm.get("baselineTime") instanceof Number ? ((Number) tm.get("baselineTime")).longValue() : 0L);
                task.setUnadjudicatedSteps(tm.get("unadjudicatedSteps") instanceof Number ? ((Number) tm.get("unadjudicatedSteps")).intValue() : 0);
                if (tm.get("steps") instanceof List) {
                    for (Object s : (List<?>) tm.get("steps")) {
                        if (!(s instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> sm = (Map<?, ?>) s;
                        BaselineStep step = new BaselineStep();
                        step.setInvocationKey(asString(sm.get("invocationKey")));
                        step.setRecordId(asString(sm.get("recordId")));
                        step.setFingerprints(FingerprintJson.shapesFromMapList(sm.get("fingerprint")));
                        step.setBaselineVersion(asString(sm.get("baselineVersion")));
                        step.setSampleInput(asString(sm.get("sampleInput")));
                        step.setSampleOutput(asString(sm.get("sampleOutput")));
                        task.getSteps().add(step);
                    }
                }
                pack.getTasks().add(task);
            }
        }
        return pack;
    }

    private static String asString(Object v) {
        return v != null ? String.valueOf(v) : null;
    }
}
