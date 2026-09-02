package io.github.agentassert4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    @DisplayName("getDeclaredInvocationIds 不可修改")
    void declaredInvocationIds_immutable() {
        InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill1\":{\"behaviors\":[\"mustUseChinese\"]}}}");
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
    @DisplayName("任务规则（tasks 段）")
    class TaskRules {

        @Test
        @DisplayName("tasks 段解析往返：三类约束逐字段对齐")
        void tasksSection_roundTrip() {
            String json = "{\"tasks\":{\"refund-flow\":{" + "\"requiredSteps\":[\"视觉检查\", \"终检\"]," + "\"requiredOrder\":[\"意图识别\", \"查询订单\", \"提交退款\"]," + "\"steps\":{\"视觉检查\":{\"min\":1,\"max\":3}}}}}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);

            assertTrue(config.hasTaskRules());
            assertEquals(Collections.singleton("refund-flow"), config.getDeclaredTaskKeys());
            InvocationRulesConfig.TaskRule rule = config.getTaskRule("refund-flow");
            assertEquals(Arrays.asList("视觉检查", "终检"), rule.getRequiredSteps());
            assertEquals(Arrays.asList("意图识别", "查询订单", "提交退款"), rule.getRequiredOrder());
            assertEquals(1, rule.getSteps().size());
            assertEquals(Integer.valueOf(1), rule.getSteps().get("视觉检查").getMin());
            assertEquals(Integer.valueOf(3), rule.getSteps().get("视觉检查").getMax());
            assertFalse(rule.isEmpty());
        }

        @Test
        @DisplayName("仅 tasks 段无 invocations 键也解析成功")
        void tasksOnly_noInvocationsKey() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"A\"]}}}");

            assertTrue(config.hasTaskRules(), "tasks 段必须独立于 invocations 段被解析");
            assertFalse(config.hasRules());
            assertEquals(Arrays.asList("A"), config.getTaskRule("t1").getRequiredSteps());
        }

        @Test
        @DisplayName("min/max 缺省为 null（缺 min=0、缺 max=不设上限）")
        void steps_missingBounds_null() {
            String json = "{\"tasks\":{\"t1\":{\"steps\":{\"A\":{\"min\":2},\"B\":{\"max\":1},\"C\":{}}}}}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);
            InvocationRulesConfig.TaskRule rule = config.getTaskRule("t1");

            assertNull(rule.getSteps().get("A").getMax());
            assertNull(rule.getSteps().get("B").getMin());
            assertTrue(rule.getSteps().get("C").isUnbounded(), "min/max 双缺 = 无约束力，供加载侧告警");
            assertFalse(rule.getSteps().get("A").isUnbounded());
        }

        @Test
        @DisplayName("getTaskRule 未命中返回空规则（非 null），且空规则无约束")
        void getTaskRule_miss_emptyRule() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"A\"]}}}");

            InvocationRulesConfig.TaskRule rule = config.getTaskRule("不存在的任务");
            assertNotNull(rule);
            assertTrue(rule.isEmpty());
        }

        @Test
        @DisplayName("任务键保持声明序，集合不可修改")
        void taskKeys_orderedAndImmutable() {
            String json = "{\"tasks\":{\"b-task\":{\"requiredSteps\":[\"A\"]},\"a-task\":{\"requiredSteps\":[\"B\"]}}}";

            InvocationRulesConfig config = InvocationRulesConfig.fromJson(json);

            assertEquals(Arrays.asList("b-task", "a-task"), new ArrayList<>(config.getDeclaredTaskKeys()), "键序 = 文件声明序（violation 呈现顺序的确定性基础）");
            assertThrows(UnsupportedOperationException.class, () -> config.getDeclaredTaskKeys().add("hacked"));
            assertThrows(UnsupportedOperationException.class, () -> config.getTaskRule("b-task").getRequiredSteps().add("x"));
        }

        @Test
        @DisplayName("畸形 tasks 条目（值非对象）安全跳过")
        void malformedTaskEntry_skipped() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"tasks\":{\"good\":{\"requiredSteps\":[\"A\"]},\"bad\":\"not-a-map\"}}");

            assertEquals(Collections.singleton("good"), config.getDeclaredTaskKeys());
        }

        @Test
        @DisplayName("类型错值留解析注记并按缺省处理——静默弱化约束不可接受")
        void typedWrongBounds_noteAndDegrade() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"steps\":{" + "\"A\":{\"min\":\"3\"}," + "\"B\":{\"min\":1.5}," + "\"C\":\"not-a-map\"}}}}");

            InvocationRulesConfig.TaskRule rule = config.getTaskRule("t1");
            assertNull(rule.getSteps().get("A").getMin(), "字符串数值不静默转数");
            assertNull(rule.getSteps().get("B").getMin(), "非整数不静默截断");
            assertTrue(rule.getSteps().containsKey("A") && rule.getSteps().containsKey("B"), "类型错值只弃边界、不弃整个步骤声明");

            boolean stringNoted = false;
            boolean nonIntegerNoted = false;
            boolean nonMapNoted = false;
            for (String note : config.getParseNotes()) {
                if (note.contains("t1") && note.contains("A") && note.contains("min") && note.contains("不是数字")) {
                    stringNoted = true;
                }
                if (note.contains("B") && note.contains("不是整数")) {
                    nonIntegerNoted = true;
                }
                if (note.contains("C") && note.contains("已整体忽略")) {
                    nonMapNoted = true;
                }
            }
            assertTrue(stringNoted, "字符串边界必须留注记供加载侧告警: " + config.getParseNotes());
            assertTrue(nonIntegerNoted, "非整数边界必须留注记: " + config.getParseNotes());
            assertTrue(nonMapNoted, "非对象步骤声明必须留注记: " + config.getParseNotes());
        }

        @Test
        @DisplayName("解析注记不可修改")
        void parseNotes_immutable() {
            InvocationRulesConfig config = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"steps\":{\"A\":{\"min\":\"x\"}}}}}");

            assertFalse(config.getParseNotes().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> config.getParseNotes().add("hack"));
        }
    }
}
