package io.github.agentassert4j.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScenarioConfig 解析契约测试：配置键 ↔ 字段逐项对齐。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
class ScenarioConfigTest {

    private static final String FULL = "{\n" + "  \"scenarios\": [ {\n" + "    \"scenarioId\": \"refund-soft\",\n" + "    \"name\": \"退款话术柔和\",\n" + "    \"skillId\": \"refund-flow\",\n" + "    \"userInput\": \"申请退款但订单已签收\",\n" + "    \"variables\": { \"order_id\": \"ORD-001\" },\n" + "    \"assertions\": { \"requiredKeywords\": [\"退款\"], \"behaviors\": [\"nonEmptyOutput\"] },\n" + "    \"sampling\":   { \"sampleCount\": 8, \"concurrency\": 2, \"maxTotalCalls\": 40, \"maxTotalTokens\": 50000 },\n" + "    \"thresholds\": { \"passThreshold\": 0.8, \"regressionTolerance\": 0.1 }\n" + "  } ]\n" + "}";

    @Test
    void fromJson_fullScenario_fieldByField() {
        ScenarioConfig config = ScenarioConfig.fromJson(FULL);

        assertEquals(1, config.getScenarios().size());
        ScenarioConfig.Scenario scenario = config.getScenarios().get(0);
        assertEquals("refund-soft", scenario.getScenarioId());
        assertEquals("退款话术柔和", scenario.getName());
        assertEquals("refund-flow", scenario.getSkillId());
        assertEquals("申请退款但订单已签收", scenario.getUserInput());
        assertEquals("ORD-001", scenario.getVariables().get("order_id"));
        // 断言集解析为规则词表对象（与 rules.json 同源）
        assertTrue(scenario.getAssertions().getRequiredKeywords().contains("退款"));
        assertTrue(scenario.getAssertions().getBehaviors().contains("nonEmptyOutput"));
        assertEquals(8, scenario.getSampleCount());
        assertEquals(2, scenario.getConcurrency());
        assertEquals(40, scenario.getMaxTotalCalls());
        assertEquals(50000L, scenario.getMaxTotalTokens());
        assertEquals(0.8, scenario.getPassThreshold(), 0.000001);
        assertEquals(0.1, scenario.getRegressionTolerance(), 0.000001);
    }

    @Test
    void fromJson_missingOptionalFields_safeDefaults() {
        // 只给身份与输入：采样/阈值走默认（5 轮、阈值满格）
        ScenarioConfig config = ScenarioConfig.fromJson("{\"scenarios\":[{\"scenarioId\":\"s1\",\"userInput\":\"你好\"}]}");

        ScenarioConfig.Scenario scenario = config.getScenarios().get(0);
        assertEquals("s1", scenario.getScenarioId());
        assertEquals("s1", scenario.getName(), "缺省 name 复用 scenarioId");
        assertEquals(5, scenario.getSampleCount());
        assertEquals(1, scenario.getConcurrency());
        assertEquals(0, scenario.getMaxTotalCalls());
        assertEquals(0L, scenario.getMaxTotalTokens());
        assertEquals(1.0, scenario.getPassThreshold(), 0.000001);
        assertEquals(0.0, scenario.getRegressionTolerance(), 0.000001);
        assertEquals(SkillRulesConfig.SkillRule.EMPTY.getRequiredKeywords(), scenario.getAssertions().getRequiredKeywords());
        assertTrue(scenario.getVariables().isEmpty());
    }

    @Test
    void fromJson_blankScenarioId_skipped() {
        // 无身份的场景无法定位基线与落库——静默跳过无效条目
        ScenarioConfig config = ScenarioConfig.fromJson("{\"scenarios\":[{\"userInput\":\"没有身份\"},{\"scenarioId\":\"s2\",\"userInput\":\"有身份\"}]}");

        assertEquals(1, config.getScenarios().size());
        assertEquals("s2", config.getScenarios().get(0).getScenarioId());
    }

    @Test
    void fromJson_malformedJson_safeEmpty() {
        // 解析失败安全退化为空配置，与 rules.json 同哲学：坏文件不中断流程
        assertTrue(ScenarioConfig.fromJson("not json at all").getScenarios().isEmpty());
        assertTrue(ScenarioConfig.fromJson("{\"scenarios\": 42}").getScenarios().isEmpty());
        assertTrue(ScenarioConfig.fromJson(null).getScenarios().isEmpty());
        assertTrue(ScenarioConfig.fromJson("").getScenarios().isEmpty());
    }

    @Test
    void fromJson_variables_nonStringValues_normalized() {
        // 结构化变量的值可能来自 JSON 数字/布尔——统一字符串化
        ScenarioConfig config = ScenarioConfig.fromJson("{\"scenarios\":[{\"scenarioId\":\"s1\",\"variables\":{\"count\":3,\"vip\":true}}]}");

        Map<String, String> variables = config.getScenarios().get(0).getVariables();
        assertEquals("3", variables.get("count"));
        assertEquals("true", variables.get("vip"));
    }
}
