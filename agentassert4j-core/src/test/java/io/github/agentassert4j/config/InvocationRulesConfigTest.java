package io.github.agentassert4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InvocationRulesConfig 单元测试 — 声明式规则配置模型。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class InvocationRulesConfigTest {

    @Test
    @DisplayName("addRule 可手动添加规则")
    void addRule() {
        InvocationRulesConfig config = new InvocationRulesConfig();
        config.addRule("mySkill", InvocationRulesConfig.fromJson("{\"invocations\":{\"tmp\":{\"behaviors\":[\"testBehavior\"]}}}").getRulesForInvocation("tmp"));

        assertTrue(config.hasRules());
        assertEquals(1, config.getDeclaredInvocationIds().size());
        assertEquals(Collections.singleton("testBehavior"), config.getRulesForInvocation("mySkill").getBehaviors());
    }

    @Test
    @DisplayName("getDeclaredInvocationIds 不可修改")
    void declaredInvocationIds_immutable() {
        InvocationRulesConfig config = new InvocationRulesConfig();
        config.addRule("skill1", new InvocationRulesConfig.InvocationRule());
        assertThrows(UnsupportedOperationException.class, () -> config.getDeclaredInvocationIds().add("hacked"));
    }

    @Test
    @DisplayName("InvocationRule 的集合不可修改")
    void ruleCollections_immutable() {
        String json = "{\"invocations\":{\"s\":{\"requiredKeywords\":[\"a\"],\"forbiddenKeywords\":[\"b\"],\"behaviors\":[\"c\"]}}}";
        InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);
        InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("s");

        assertThrows(UnsupportedOperationException.class, () -> rule.getRequiredKeywords().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> rule.getForbiddenKeywords().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> rule.getBehaviors().add("x"));
    }

    @Nested
    @DisplayName("空配置行为")
    class Empty {

        @Test
        @DisplayName("空配置 hasRules=false")
        void emptyConfig_noRules() {
            InvocationRulesConfig config = new InvocationRulesConfig();
            assertFalse(config.hasRules());
            assertTrue(config.getDeclaredInvocationIds().isEmpty());
        }

        @Test
        @DisplayName("getRulesForInvocation 返回空规则（非 null）")
        void getRulesForInvocation_emptyRule() {
            InvocationRulesConfig config = new InvocationRulesConfig();
            InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("nonexistent");
            assertNotNull(rule);
            assertTrue(rule.getRequiredKeywords().isEmpty());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
            assertTrue(rule.getRegexPatterns().isEmpty());
            assertTrue(rule.getBehaviors().isEmpty());
        }
    }

    @Nested
    @DisplayName("JSON 解析")
    class FromJson {

        @Test
        @DisplayName("完整规则解析")
        void fullRules() {
            String json = "{\n" + "  \"invocations\": {\n" + "    \"queryOrderDB\": {\n" + "      \"requiredKeywords\": [\"订单号\", \"金额\"],\n" + "      \"forbiddenKeywords\": [\"密码\", \"身份证号\"],\n" + "      \"regexPatterns\": [\n" + "        {\"pattern\": \"\\\\d{4}-\\\\d{2}-\\\\d{2}\", \"description\": \"日期格式\"},\n" + "        {\"pattern\": \"ORD-\\\\d{4}\", \"description\": \"订单号格式\"}\n" + "      ],\n" + "      \"behaviors\": [\"returnsEmptyOnInvalid\", \"truncatesLongText\"]\n" + "    }\n" + "  }\n" + "}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);

            assertTrue(config.hasRules());
            assertEquals(1, config.getDeclaredInvocationIds().size());
            assertTrue(config.getDeclaredInvocationIds().contains("queryOrderDB"));

            InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("queryOrderDB");
            assertEquals(new HashSet<>(Arrays.asList("订单号", "金额")), rule.getRequiredKeywords());
            assertEquals(new HashSet<>(Arrays.asList("密码", "身份证号")), rule.getForbiddenKeywords());
            assertEquals(2, rule.getRegexPatterns().size());
            assertEquals("\\d{4}-\\d{2}-\\d{2}", rule.getRegexPatterns().get(0).getPattern());
            assertEquals("日期格式", rule.getRegexPatterns().get(0).getDescription());
            assertEquals(new HashSet<>(Arrays.asList("returnsEmptyOnInvalid", "truncatesLongText")), rule.getBehaviors());
        }

        @Test
        @DisplayName("多 Skill 规则")
        void multiInvocationRules() {
            String json = "{\n" + "  \"invocations\": {\n" + "    \"queryOrderDB\": {\n" + "      \"requiredKeywords\": [\"订单号\"],\n" + "      \"behaviors\": [\"returnsEmptyOnInvalid\"]\n" + "    },\n" + "    \"sendSms\": {\n" + "      \"requiredKeywords\": [\"尊敬的用户\"],\n" + "      \"forbiddenKeywords\": [\"password\"],\n" + "      \"behaviors\": [\"mustUseChinese\"]\n" + "    }\n" + "  }\n" + "}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);

            assertEquals(2, config.getDeclaredInvocationIds().size());
            assertEquals(Collections.singleton("订单号"), config.getRulesForInvocation("queryOrderDB").getRequiredKeywords());
            assertEquals(Collections.singleton("尊敬的用户"), config.getRulesForInvocation("sendSms").getRequiredKeywords());
            assertEquals(Collections.singleton("password"), config.getRulesForInvocation("sendSms").getForbiddenKeywords());
        }

        @Test
        @DisplayName("部分规则 — 缺失维度为空")
        void partialRule() {
            String json = "{\n" + "  \"invocations\": {\n" + "    \"simpleSkill\": {\n" + "      \"behaviors\": [\"requiresConfirmation\"]\n" + "    }\n" + "  }\n" + "}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);
            InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("simpleSkill");

            assertTrue(rule.getRequiredKeywords().isEmpty());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
            assertTrue(rule.getRegexPatterns().isEmpty());
            assertEquals(1, rule.getBehaviors().size());
        }

        @Test
        @DisplayName("null 输入 → 空配置")
        void nullInput() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson(null);
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("空字符串 → 空配置")
        void blankInput() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("   ");
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("无效 JSON → 空配置（退化不中断）")
        void invalidJson() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("not json");
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("无 invocations 键 → 空配置")
        void noSkillsKey() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"other\": 123}");
            assertFalse(config.hasRules());
        }
    }

    @Nested
    @DisplayName("InvocationRule 不可变契约")
    class InvocationRuleImmutability {

        // 旧断言钉住 setter 的空值安全——setters 已随不可变化改造移除
        // （共享 EMPTY 单例的污染风险大于 setter 便利性），改钉不可变契约

        @Test
        @DisplayName("解析产物集合不可修改")
        void parsedCollections_unmodifiable() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"],\"behaviors\":[\"mustUseChinese\"]}}}");
            InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("skill-1");
            assertThrows(UnsupportedOperationException.class, () -> rule.getRequiredKeywords().add("x"));
            assertThrows(UnsupportedOperationException.class, () -> rule.getBehaviors().add("x"));
        }

        @Test
        @DisplayName("共享 EMPTY 实例无声明且不可变")
        void sharedEmpty_hasNoDeclarations() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"invocations\":{}}");
            InvocationRulesConfig.InvocationRule rule = config.getRulesForInvocation("any-skill");
            assertTrue(rule.getRequiredKeywords().isEmpty());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
            assertTrue(rule.getRegexPatterns().isEmpty());
            assertTrue(rule.getBehaviors().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> rule.getRequiredKeywords().add("x"));
        }
    }

    @Nested
    @DisplayName("规则合并（场景断言叠加站内规则）")
    class Merging {

        @Test
        @DisplayName("同键合并逐集合取并集")
        void merging_sameKey_unionsSets() {
            InvocationRulesConfig base = InvocationRulesConfig.fromJson("{\"invocations\":{\"s\":{" + "\"requiredKeywords\":[\"订单号\"],\"forbiddenKeywords\":[\"密码\"]," + "\"behaviors\":[\"mustUseChinese\"]}}}");
            InvocationRulesConfig.InvocationRule extra = InvocationRulesConfig.fromJson("{\"invocations\":{\"tmp\":{" + "\"requiredKeywords\":[\"金额\"],\"forbiddenKeywords\":[\"身份证\"]," + "\"regexPatterns\":[{\"pattern\":\"ORD-\\\\d{4}\"}]," + "\"behaviors\":[\"nonEmptyOutput\"]}}}").getRulesForInvocation("tmp");

            InvocationRulesConfig merged = base.merging("s", extra);
            InvocationRulesConfig.InvocationRule rule = merged.getRulesForInvocation("s");

            assertEquals(new HashSet<>(Arrays.asList("订单号", "金额")), rule.getRequiredKeywords());
            assertEquals(new HashSet<>(Arrays.asList("密码", "身份证")), rule.getForbiddenKeywords());
            assertEquals(new HashSet<>(Arrays.asList("mustUseChinese", "nonEmptyOutput")), rule.getBehaviors());
            assertEquals(1, rule.getRegexPatterns().size());
            assertEquals("ORD-\\d{4}", rule.getRegexPatterns().get(0).getPattern());
        }

        @Test
        @DisplayName("无同键则新增条目")
        void merging_newKey_added() {
            InvocationRulesConfig base = InvocationRulesConfig.fromJson("{\"invocations\":{\"s\":{\"requiredKeywords\":[\"订单号\"]}}}");
            InvocationRulesConfig.InvocationRule extra = InvocationRulesConfig.fromJson("{\"invocations\":{\"other\":{\"behaviors\":[\"mustUseChinese\"]}}}").getRulesForInvocation("other");

            InvocationRulesConfig merged = base.merging("other", extra);

            assertEquals(new HashSet<>(Arrays.asList("s", "other")), merged.getDeclaredInvocationIds());
            assertEquals(Collections.singleton("mustUseChinese"), merged.getRulesForInvocation("other").getBehaviors());
        }

        @Test
        @DisplayName("基础配置在合并后保持不变")
        void merging_baseUntouched() {
            InvocationRulesConfig base = InvocationRulesConfig.fromJson("{\"invocations\":{\"s\":{\"requiredKeywords\":[\"订单号\"]}}}");
            InvocationRulesConfig.InvocationRule extra = InvocationRulesConfig.fromJson("{\"invocations\":{\"tmp\":{\"requiredKeywords\":[\"金额\"]}}}").getRulesForInvocation("tmp");

            base.merging("s", extra);

            assertEquals(Collections.singleton("订单号"), base.getRulesForInvocation("s").getRequiredKeywords(), "多条场景共享基础配置各自合并，不得互相串味");
        }

        @Test
        @DisplayName("合并空规则等于原样复制")
        void merging_emptyRule_noOp() {
            InvocationRulesConfig base = InvocationRulesConfig.fromJson("{\"invocations\":{\"s\":{\"requiredKeywords\":[\"订单号\"]}}}");

            InvocationRulesConfig merged = base.merging("s", InvocationRulesConfig.InvocationRule.EMPTY);

            assertEquals(Collections.singleton("订单号"), merged.getRulesForInvocation("s").getRequiredKeywords());
        }
    }
}
