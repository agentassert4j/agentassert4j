package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线侧投影组件的单元测试 — 两个工厂方法的投影属性与输入违约响亮度。
 *
 * @author axy-yxa
 * @since 2026-09-14
 */
class BaselineSidesTest {

    private static InteractionRecord record(String recordId, String key, String label, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-a");
        r.setTimestamp(1000L);
        r.setInvocationKey(key);
        r.setInvocationId(label);
        r.setModelResponse(response);
        return r;
    }

    private static InvocationProfile profile(String key, String versionTag) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(key);
        p.setInvocationName("n");
        p.setInvocationType(InvocationType.PURE_CHAT);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag(versionTag);
        p.setAlgoVersion(JudgmentSemantics.VERSION);
        return p;
    }

    @Nested
    @DisplayName("画像投影 fromProfiles（CI 基线对照）")
    class FromProfiles {

        @Test
        @DisplayName("每记录一步：组键=调用点键、指纹=画像活跃指纹、versionTag 透传、标签镜像记录")
        void perRecordStep_projectionProperties() {
            InteractionRecord a = record("r-1", "invocation:order:hash-a", "order", "{\"result\":\"ok\"}");
            InteractionRecord b = record("r-2", "skeleton:skl-1", null, "{\"result\":\"ok\"}");
            InvocationProfile pa = profile("invocation:order:hash-a", "v3");
            pa.setFingerprint(FingerprintExtractor.extract(a, new InvocationRulesConfig(), "order"));
            InvocationProfile pb = profile("skeleton:skl-1", "v1");
            pb.setFingerprint(FingerprintExtractor.extract(b, new InvocationRulesConfig(), null));

            Map<String, List<BaselineStep>> sides = BaselineSides.fromProfiles(new ArrayList<>(Arrays.asList(a, b)), key -> key.equals(pa.getInvocationKey()) ? pa : pb);

            assertEquals(2, sides.size(), "每记录一份步骤、按键分组");
            BaselineStep stepA = sides.get("invocation:order:hash-a").get(0);
            assertEquals(pa.getFingerprint(), stepA.getFingerprint(), "指纹取画像活跃指纹（定格投影）");
            assertEquals("v3", stepA.getVersionTag(), "版本标签透传画像活跃版本");
            assertEquals("order", stepA.getInvocationId(), "标签镜像记录（保证组键与新链逐字相等）");
            assertNull(stepA.getRecordId(), "画像路径无基线记录");
            BaselineStep stepB = sides.get("skeleton:skl-1").get(0);
            assertEquals("v1", stepB.getVersionTag());
            assertNull(stepB.getInvocationId(), "无标签记录镜像为无标签步骤");
        }

        @Test
        @DisplayName("同键多记录：每条各一份步骤（消灭 surplus 盲区的机制本体）")
        void sameKeyRecords_eachGetStep() {
            InteractionRecord a = record("r-1", "invocation:order:hash-a", "order", "{\"result\":\"ok\"}");
            InteractionRecord b = record("r-2", "invocation:order:hash-a", "order", "{\"changed\":true}");
            InvocationProfile p = profile("invocation:order:hash-a", "v1");
            p.setFingerprint(FingerprintExtractor.extract(a, new InvocationRulesConfig(), "order"));

            Map<String, List<BaselineStep>> sides = BaselineSides.fromProfiles(Arrays.asList(a, b), key -> p);

            assertEquals(2, sides.get("invocation:order:hash-a").size(), "每执行一份步骤，paired=记录数");
        }

        @Test
        @DisplayName("无键记录跳过（两侧分组器同款跳过，对称不判）")
        void keylessRecord_skipped() {
            InteractionRecord a = record("r-1", "invocation:order:hash-a", "order", "{\"result\":\"ok\"}");
            InteractionRecord keyless = record("r-2", null, null, "{}");
            InvocationProfile p = profile("invocation:order:hash-a", "v1");
            p.setFingerprint(FingerprintExtractor.extract(a, new InvocationRulesConfig(), "order"));

            Map<String, List<BaselineStep>> sides = BaselineSides.fromProfiles(Arrays.asList(a, keyless), key -> p);

            assertEquals(1, sides.size());
            assertFalse(sides.containsKey(null));
        }

        @Test
        @DisplayName("画像缺席或指纹为空 → 响亮失败（CI 守卫先于此入口，到达即数据违约）")
        void missingProfileOrFingerprint_throws() {
            InteractionRecord a = record("r-1", "invocation:order:hash-a", "order", "{\"result\":\"ok\"}");
            InvocationProfile empty = profile("invocation:order:hash-a", "v1");

            assertThrows(IllegalStateException.class, () -> BaselineSides.fromProfiles(Arrays.asList(a), key -> null), "画像缺席必须响亮");
            assertThrows(IllegalStateException.class, () -> BaselineSides.fromProfiles(Arrays.asList(a), key -> empty), "活跃指纹缺失必须响亮");
        }
    }

    @Nested
    @DisplayName("验收包投影 fromPackSteps")
    class FromPackSteps {

        @Test
        @DisplayName("按完整键分组并从键回填声明标签（verify 路径的收编实现）")
        void groupsAndBackfillsLabel() {
            BaselineStep stepA = new BaselineStep();
            stepA.setInvocationKey("invocation:order:hash-a");
            BaselineStep stepB = new BaselineStep();
            stepB.setInvocationKey("invocation:order:hash-b");
            BaselineStep stepC = new BaselineStep();
            stepC.setInvocationKey("skeleton:skl-1");

            Map<String, List<BaselineStep>> sides = BaselineSides.fromPackSteps(new ArrayList<>(Arrays.asList(stepA, stepB, stepC)));

            assertEquals(3, sides.size(), "按完整键分组（同标签跨版本分桶）");
            assertEquals("order", sides.get("invocation:order:hash-a").get(0).getInvocationId(), "声明标签从键解析回填");
            assertEquals("order", sides.get("invocation:order:hash-b").get(0).getInvocationId());
            assertNull(sides.get("skeleton:skl-1").get(0).getInvocationId(), "无声明形态不回填");
            assertNull(sides.get("invocation:order:hash-a").get(0).getVersionTag(), "包路径不携带版本标签");
        }
    }
}
