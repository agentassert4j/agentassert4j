package io.github.agentassert4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillRulesConfig 单元测试 — 声明式规则配置模型。
 */
class SkillRulesConfigTest {

    @Test
    @DisplayName("addRule 可手动添加规则")
    void addRule() {
        SkillRulesConfig config = new SkillRulesConfig();
        SkillRulesConfig.SkillRule rule = new SkillRulesConfig.SkillRule();
        rule.setBehaviors(Set.of("testBehavior"));
        config.addRule("mySkill", rule);

        assertTrue(config.hasRules());
        assertEquals(1, config.getDeclaredSkillIds().size());
        assertEquals(Set.of("testBehavior"), config.getRulesForSkill("mySkill").getBehaviors());
    }

    @Test
    @DisplayName("getDeclaredSkillIds 不可修改")
    void declaredSkillIds_immutable() {
        SkillRulesConfig config = new SkillRulesConfig();
        config.addRule("skill1", new SkillRulesConfig.SkillRule());
        assertThrows(UnsupportedOperationException.class, () ->
                config.getDeclaredSkillIds().add("hacked"));
    }

    @Test
    @DisplayName("SkillRule 的集合不可修改")
    void ruleCollections_immutable() {
        String json = """
                {"skills":{"s":{"requiredKeywords":["a"],"forbiddenKeywords":["b"],"behaviors":["c"]}}}
                """;
        SkillRulesConfig config = SkillRulesConfig.fromJson(json);
        SkillRulesConfig.SkillRule rule = config.getRulesForSkill("s");

        assertThrows(UnsupportedOperationException.class, () ->
                rule.getRequiredKeywords().add("x"));
        assertThrows(UnsupportedOperationException.class, () ->
                rule.getForbiddenKeywords().add("x"));
        assertThrows(UnsupportedOperationException.class, () ->
                rule.getBehaviors().add("x"));
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
            String json = """
                    {
                      "skills": {
                        "queryOrderDB": {
                          "requiredKeywords": ["订单号", "金额"],
                          "forbiddenKeywords": ["密码", "身份证号"],
                          "regexPatterns": [
                            {"pattern": "\\\\d{4}-\\\\d{2}-\\\\d{2}", "description": "日期格式"},
                            {"pattern": "ORD-\\\\d{4}", "description": "订单号格式"}
                          ],
                          "behaviors": ["returnsEmptyOnInvalid", "truncatesLongText"]
                        }
                      }
                    }
                    """;

            SkillRulesConfig config = SkillRulesConfig.fromJson(json);

            assertTrue(config.hasRules());
            assertEquals(1, config.getDeclaredSkillIds().size());
            assertTrue(config.getDeclaredSkillIds().contains("queryOrderDB"));

            SkillRulesConfig.SkillRule rule = config.getRulesForSkill("queryOrderDB");
            assertEquals(Set.of("订单号", "金额"), rule.getRequiredKeywords());
            assertEquals(Set.of("密码", "身份证号"), rule.getForbiddenKeywords());
            assertEquals(2, rule.getRegexPatterns().size());
            assertEquals("\\d{4}-\\d{2}-\\d{2}", rule.getRegexPatterns().get(0).getPattern());
            assertEquals("日期格式", rule.getRegexPatterns().get(0).getDescription());
            assertEquals(Set.of("returnsEmptyOnInvalid", "truncatesLongText"), rule.getBehaviors());
        }

        @Test
        @DisplayName("多 Skill 规则")
        void multiSkillRules() {
            String json = """
                    {
                      "skills": {
                        "queryOrderDB": {
                          "requiredKeywords": ["订单号"],
                          "behaviors": ["returnsEmptyOnInvalid"]
                        },
                        "sendSms": {
                          "requiredKeywords": ["尊敬的用户"],
                          "forbiddenKeywords": ["password"],
                          "behaviors": ["mustUseChinese"]
                        }
                      }
                    }
                    """;

            SkillRulesConfig config = SkillRulesConfig.fromJson(json);

            assertEquals(2, config.getDeclaredSkillIds().size());
            assertEquals(Set.of("订单号"), config.getRulesForSkill("queryOrderDB").getRequiredKeywords());
            assertEquals(Set.of("尊敬的用户"), config.getRulesForSkill("sendSms").getRequiredKeywords());
            assertEquals(Set.of("password"), config.getRulesForSkill("sendSms").getForbiddenKeywords());
        }

        @Test
        @DisplayName("部分规则 — 缺失维度为空")
        void partialRule() {
            String json = """
                    {
                      "skills": {
                        "simpleSkill": {
                          "behaviors": ["requiresConfirmation"]
                        }
                      }
                    }
                    """;

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
    @DisplayName("SkillRule Setter 空值安全")
    class SkillRuleSetterSafety {

        @Test
        @DisplayName("setRequiredKeywords(null) → 空集合")
        void requiredKeywords_nullSafe() {
            SkillRulesConfig.SkillRule rule = new SkillRulesConfig.SkillRule();
            rule.setRequiredKeywords(null);
            assertNotNull(rule.getRequiredKeywords());
            assertTrue(rule.getRequiredKeywords().isEmpty());
        }

        @Test
        @DisplayName("setForbiddenKeywords(null) → 空集合")
        void forbiddenKeywords_nullSafe() {
            SkillRulesConfig.SkillRule rule = new SkillRulesConfig.SkillRule();
            rule.setForbiddenKeywords(null);
            assertNotNull(rule.getForbiddenKeywords());
            assertTrue(rule.getForbiddenKeywords().isEmpty());
        }

        @Test
        @DisplayName("setRegexPatterns(null) → 空列表")
        void regexPatterns_nullSafe() {
            SkillRulesConfig.SkillRule rule = new SkillRulesConfig.SkillRule();
            rule.setRegexPatterns(null);
            assertNotNull(rule.getRegexPatterns());
            assertTrue(rule.getRegexPatterns().isEmpty());
        }

        @Test
        @DisplayName("setBehaviors(null) → 空集合")
        void behaviors_nullSafe() {
            SkillRulesConfig.SkillRule rule = new SkillRulesConfig.SkillRule();
            rule.setBehaviors(null);
            assertNotNull(rule.getBehaviors());
            assertTrue(rule.getBehaviors().isEmpty());
        }
    }
}
