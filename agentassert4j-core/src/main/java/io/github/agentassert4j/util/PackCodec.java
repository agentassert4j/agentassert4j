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
 * 键名与嵌套形态固定，字面输出由测试钉住。</p>
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
        root.put("meta", meta);
        List<Object> tasks = new ArrayList<>();
        for (AcceptancePack.PackTask task : pack.getTasks()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskKey", task.getTaskKey());
            t.put("requestText", task.getRequestText());
            t.put("declared", task.isDeclared());
            t.put("baselineTime", task.getBaselineTime());
            List<Object> steps = new ArrayList<>();
            int order = 1;
            for (BaselineStep step : task.getSteps()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("order", order++);
                s.put("invocationKey", step.getInvocationKey());
                s.put("recordId", step.getRecordId());
                s.put("fingerprint", step.getFingerprint() == null ? new LinkedHashMap<>() : FingerprintJson.toMap(step.getFingerprint()));
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
        return RecursiveJsonParser.serialize(root);
    }

    public static AcceptancePack fromJson(String json) {
        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("验收包不是合法 JSON 对象。");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        String schema = asString(root.get("schema"));
        if (!AcceptancePack.SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("验收包 schema 不支持：" + schema + "（期望 " + AcceptancePack.SCHEMA + "）。");
        }
        AcceptancePack pack = new AcceptancePack();
        Map<?, ?> meta = root.get("meta") instanceof Map ? (Map<?, ?>) root.get("meta") : null;
        if (meta != null) {
            AcceptancePack.PackMeta m = new AcceptancePack.PackMeta();
            m.setExportedAt(meta.get("exportedAt") instanceof Number ? ((Number) meta.get("exportedAt")).longValue() : 0L);
            m.setExportedBy(asString(meta.get("exportedBy")));
            m.setJudgmentSemantics(asString(meta.get("judgmentSemantics")));
            m.setStorageSchemaVersion(meta.get("storageSchemaVersion") instanceof Number ? ((Number) meta.get("storageSchemaVersion")).intValue() : 0);
            m.setFrameworkVersion(asString(meta.get("frameworkVersion")));
            m.setServedModel(asString(meta.get("servedModel")));
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
                if (tm.get("steps") instanceof List) {
                    for (Object s : (List<?>) tm.get("steps")) {
                        if (!(s instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> sm = (Map<?, ?>) s;
                        BaselineStep step = new BaselineStep();
                        step.setInvocationKey(asString(sm.get("invocationKey")));
                        step.setRecordId(asString(sm.get("recordId")));
                        step.setFingerprint(sm.get("fingerprint") instanceof Map ? FingerprintJson.fromMap((Map<?, ?>) sm.get("fingerprint")) : null);
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
