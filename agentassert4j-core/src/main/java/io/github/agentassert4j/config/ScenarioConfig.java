package io.github.agentassert4j.config;

import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景配置 — agentassert4j-scenarios.json 的解析模型。
 *
 * <p>文件结构（嵌套分组，缺省字段走安全默认）：</p>
 * <pre>{@code
 * { "scenarios": [ {
 *     "scenarioId": "refund-soft", "name": "退款话术柔和",
 *     "skillId": "refund-flow",                       // 绑定：业务标签（或 templateHash）
 *     "userInput": "申请退款但订单已签收",
 *     "variables": { "order_id": "ORD-001" },
 *     "assertions": { "requiredKeywords": ["退款"], "behaviors": ["nonEmptyOutput"] },
 *     "sampling":   { "sampleCount": 5, "concurrency": 1, "maxTotalCalls": 0, "maxTotalTokens": 0 },
 *     "thresholds": { "passThreshold": 1.0, "regressionTolerance": 0.0 }
 * } ] }
 * }</pre>
 *
 * <p>断言集直接解析为 {@link SkillRulesConfig.SkillRule}——场景断言与规则词表同源复用，
 * 不引入第二套断言语言。解析失败安全退化为空配置（与 rules.json 同哲学）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
public final class ScenarioConfig {

    /**
     * 采样轮数默认值：一轮不足以观测概率行为，5 轮是成本与功效的起步平衡点
     */
    public static final int DEFAULT_SAMPLE_COUNT = 5;

    private final List<Scenario> scenarios;

    private ScenarioConfig(List<Scenario> scenarios) {
        this.scenarios = scenarios;
    }

    public static ScenarioConfig empty() {
        return new ScenarioConfig(new ArrayList<>());
    }

    public static ScenarioConfig fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return empty();

        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) return empty();

        Object scenariosObj = ((Map<?, ?>) parsed).get("scenarios");
        if (!(scenariosObj instanceof List)) return empty();

        List<Scenario> scenarios = new ArrayList<>();
        for (Object entry : (List<?>) scenariosObj) {
            if (!(entry instanceof Map)) continue;
            Scenario scenario = parseScenario((Map<?, ?>) entry);
            if (scenario != null) {
                scenarios.add(scenario);
            }
        }
        return new ScenarioConfig(scenarios);
    }

    private static Scenario parseScenario(Map<?, ?> map) {
        String scenarioId = stringValue(map.get("scenarioId"));
        if (scenarioId.isEmpty()) {
            return null; // 无身份的场景无法定位基线与落库，静默跳过无效条目
        }
        Scenario scenario = new Scenario();
        scenario.scenarioId = scenarioId;
        scenario.name = orDefault(stringValue(map.get("name")), scenarioId);
        scenario.skillId = stringValue(map.get("skillId"));
        scenario.templateHash = stringValue(map.get("templateHash"));
        scenario.userInput = stringValue(map.get("userInput"));
        scenario.variables = stringMap(map.get("variables"));

        Object assertions = map.get("assertions");
        scenario.assertions = assertions instanceof Map ? SkillRulesConfig.SkillRule.fromJson((Map<String, Object>) assertions) : SkillRulesConfig.SkillRule.EMPTY;

        Object sampling = map.get("sampling");
        if (sampling instanceof Map) {
            Map<?, ?> s = (Map<?, ?>) sampling;
            scenario.sampleCount = intValue(s.get("sampleCount"), DEFAULT_SAMPLE_COUNT);
            scenario.concurrency = intValue(s.get("concurrency"), 1);
            scenario.maxTotalCalls = intValue(s.get("maxTotalCalls"), 0);
            scenario.maxTotalTokens = longValue(s.get("maxTotalTokens"), 0L);
        }

        Object thresholds = map.get("thresholds");
        if (thresholds instanceof Map) {
            Map<?, ?> t = (Map<?, ?>) thresholds;
            scenario.passThreshold = doubleValue(t.get("passThreshold"), 1.0);
            scenario.regressionTolerance = doubleValue(t.get("regressionTolerance"), 0.0);
        }
        return scenario;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String orDefault(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new HashMap<>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
        }
        return result;
    }

    public List<Scenario> getScenarios() {
        return scenarios;
    }

    /**
     * 单个场景定义（解析后的声明模型）。
     */
    public static final class Scenario {

        private String scenarioId = "";
        private String name;
        private String skillId = "";
        private String templateHash = "";
        private String userInput = "";
        private Map<String, String> variables = new HashMap<>();
        private SkillRulesConfig.SkillRule assertions = SkillRulesConfig.SkillRule.EMPTY;
        private int sampleCount = DEFAULT_SAMPLE_COUNT;
        private int concurrency = 1;
        private int maxTotalCalls = 0;
        private long maxTotalTokens = 0;
        private double passThreshold = 1.0;
        private double regressionTolerance = 0.0;

        public String getScenarioId() {
            return scenarioId;
        }

        public String getName() {
            return name;
        }

        /**
         * 绑定的业务标签（空 = 未按标签绑定）。
         */
        public String getSkillId() {
            return skillId;
        }

        /**
         * 绑定的模板 hash（空 = 未按模板绑定）。
         */
        public String getTemplateHash() {
            return templateHash;
        }

        public String getUserInput() {
            return userInput;
        }

        public Map<String, String> getVariables() {
            return variables;
        }

        public SkillRulesConfig.SkillRule getAssertions() {
            return assertions;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public int getMaxTotalCalls() {
            return maxTotalCalls;
        }

        public long getMaxTotalTokens() {
            return maxTotalTokens;
        }

        public double getPassThreshold() {
            return passThreshold;
        }

        public double getRegressionTolerance() {
            return regressionTolerance;
        }
    }
}
