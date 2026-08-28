package io.github.agentassert4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillRulesConfig 单元测试 — 声明式规则配置模型。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class SkillRulesConfigTest {

    @Test
    @DisplayName("addRule 可手动添加规则")
    void addRule() {
        SkillRulesConfig config = new SkillRulesConfig();
        config.addRule("mySkill", SkillRulesConfig.fromJson("{\"skills\":{\"tmp\":{\"behaviors\":[\"testBehavior\"]}}}").getRulesForSkill("tmp"));

        assertTrue(config.hasRules());
        assertEquals(1, config.getDeclaredSkillIds().size());
        assertEquals(Collections.singleton("testBehavior"), config.getRulesForSkill("mySkill").getBehaviors());
    }

    @Test
    @DisplayName("getDeclaredSkillIds 不可修改")
    void declaredSkillIds_immutable() {
        SkillRulesConfig config = new SkillRulesConfig();
        config.addRule("skill1", new SkillRulesConfig.SkillRule());
        assertThrows(UnsupportedOperationException.class, () -> config.getDeclaredSkillIds().add("hacked"));
    }

    @Test
    @DisplayName("SkillRule 的集合不可修改")
    void ruleCollections_immutable() {
        String json = "{\"skills\":{\"s\":{\"requiredKeywords\":[\"a\"],\"forbiddenKeywords\":[\"b\"],\"behaviors\":[\"c\"]}}}";
        SkillRulesConfig config = SkillRulesConfig.fromJson(json);
        SkillRulesConfig.SkillRule rule = config.getRulesForSkill("s");

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
            SkillRulesConfig config = new SkillRulesConfig();
            assertFalse(config.hasRules());
            assertTrue(config.getDeclaredSkillIds().isEmpty());
        }

        @Test
        @DisplayName("getRulesForSkill 返回空规则（非 null）")
        void getRulesForSkill_emptyRule() {
            SkillRulesConfig config = new SkillRulesConfig();
            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("nonexistent");
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
            String json = "{\n" + "  \"skills\": {\n" + "    \"queryOrderDB\": {\n" + "      \"requiredKeywords\": [\"订单号\", \"金额\"],\n" + "      \"forbiddenKeywords\": [\"密码\", \"身份证号\"],\n" + "      \"regexPatterns\": [\n" + "        {\"pattern\": \"\\\\d{4}-\\\\d{2}-\\\\d{2}\", \"description\": \"日期格式\"},\n" + "        {\"pattern\": \"ORD-\\\\d{4}\", \"description\": \"订单号格式\"}\n" + "      ],\n" + "      \"behaviors\": [\"returnsEmptyOnInvalid\", \"truncatesLongText\"]\n" + "    }\n" + "  }\n" + "}";

            SkillRulesConfig config = SkillRulesConfig.fromJson(json);

            assertTrue(config.hasRules());
            assertEquals(1, config.getDeclaredSkillIds().size());
            assertTrue(config.getDeclaredSkillIds().contains("queryOrderDB"));

            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("queryOrderDB");
            assertEquals(new HashSet<>(Arrays.asList("订单号", "金额")), rule.getRequiredKeywords());
            assertEquals(new HashSet<>(Arrays.asList("密码", "身份证号")), rule.getForbiddenKeywords());
            assertEquals(2, rule.getRegexPatterns().size());
            assertEquals("\\d{4}-\\d{2}-\\d{2}", rule.getRegexPatterns().get(0).getPattern());
            assertEquals("日期格式", rule.getRegexPatterns().get(0).getDescription());
            assertEquals(new HashSet<>(Arrays.asList("returnsEmptyOnInvalid", "truncatesLongText")), rule.getBehaviors());
        }

        @Test
        @DisplayName("多 Skill 规则")
        void multiSkillRules() {
            String json = "{\n" + "  \"skills\": {\n" + "    \"queryOrderDB\": {\n" + "      \"requiredKeywords\": [\"订单号\"],\n" + "      \"behaviors\": [\"returnsEmptyOnInvalid\"]\n" + "    },\n" + "    \"sendSms\": {\n" + "      \"requiredKeywords\": [\"尊敬的用户\"],\n" + "      \"forbiddenKeywords\": [\"password\"],\n" + "      \"behaviors\": [\"mustUseChinese\"]\n" + "    }\n" + "  }\n" + "}";

            SkillRulesConfig config = SkillRulesConfig.fromJson(json);

            assertEquals(2, config.getDeclaredSkillIds().size());
            assertEquals(Collections.singleton("订单号"), config.getRulesForSkill("queryOrderDB").getRequiredKeywords());
            assertEquals(Collections.singleton("尊敬的用户"), config.getRulesForSkill("sendSms").getRequiredKeywords());
            assertEquals(Collections.singleton("password"), config.getRulesForSkill("sendSms").getForbiddenKeywords());
        }

        @Test
        @DisplayName("部分规则 — 缺失维度为空")
        void partialRule() {
            String json = "{\n" + "  \"skills\": {\n" + "    \"simpleSkill\": {\n" + "      \"behaviors\": [\"requiresConfirmation\"]\n" + "    }\n" + "  }\n" + "}";

            SkillRulesConfig config = SkillRulesConfig.fromJson(json);
            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("simpleSkill");

            assertTrue(rule.getRequiredKeywords().isEmpty());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
            assertTrue(rule.getRegexPatterns().isEmpty());
            assertEquals(1, rule.getBehaviors().size());
        }

        @Test
        @DisplayName("null 输入 → 空配置")
        void nullInput() {
            SkillRulesConfig config = SkillRulesConfig.fromJson(null);
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("空字符串 → 空配置")
        void blankInput() {
            SkillRulesConfig config = SkillRulesConfig.fromJson("   ");
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("无效 JSON → 空配置（退化不中断）")
        void invalidJson() {
            SkillRulesConfig config = SkillRulesConfig.fromJson("not json");
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("无 skills 键 → 空配置")
        void noSkillsKey() {
            SkillRulesConfig config = SkillRulesConfig.fromJson("{\"other\": 123}");
            assertFalse(config.hasRules());
        }
    }

    @Nested
    @DisplayName("SkillRule 不可变契约")
    class SkillRuleImmutability {

        // 旧断言钉住 setter 的空值安全——setters 已随不可变化改造移除
        // （共享 EMPTY 单例的污染风险大于 setter 便利性），改钉不可变契约

        @Test
        @DisplayName("解析产物集合不可修改")
        void parsedCollections_unmodifiable() {
            SkillRulesConfig config = SkillRulesConfig.fromJson("{\"skills\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"],\"behaviors\":[\"mustUseChinese\"]}}}");
            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("skill-1");
            assertThrows(UnsupportedOperationException.class, () -> rule.getRequiredKeywords().add("x"));
            assertThrows(UnsupportedOperationException.class, () -> rule.getBehaviors().add("x"));
        }

        @Test
        @DisplayName("共享 EMPTY 实例无声明且不可变")
        void sharedEmpty_hasNoDeclarations() {
            SkillRulesConfig config = SkillRulesConfig.fromJson("{\"skills\":{}}");
            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("any-skill");
            assertTrue(rule.getRequiredKeywords().isEmpty());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
            assertTrue(rule.getRegexPatterns().isEmpty());
            assertTrue(rule.getBehaviors().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> rule.getRequiredKeywords().add("x"));
        }
    }
}
