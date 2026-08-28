package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.*;

/**
 * 成本预估 — 执行回归测试前估算 API 调用费用。
 *
 * <p>纯静态工具类，按模型的每次调用成本估算（美元/次，基于 input+output 平均 token）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class CostEstimator {

    /**
     * 按模型的每次调用成本估算（美元/次）
     */
    private static final Map<String, Double> MODEL_COST;
    /**
     * 未知模型的默认值
     */
    private static final double DEFAULT_COST_PER_CALL = 0.003;
    /**
     * 价格表键按长度降序——模糊匹配时 "gpt-4o-mini" 必须先于 "gpt-4o" 参与
     */
    private static final List<String> KEYS_BY_LENGTH_DESC;

    static {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("gpt-4o", 0.004);
        map.put("gpt-4o-mini", 0.0004);
        map.put("gpt-4", 0.03);
        map.put("gpt-3.5-turbo", 0.0005);
        map.put("gpt-3.5", 0.0005);
        map.put("deepseek-chat", 0.001);
        map.put("deepseek", 0.001);
        map.put("qwen-plus", 0.002);
        map.put("qwen-turbo", 0.0005);
        MODEL_COST = Collections.unmodifiableMap(map);
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        KEYS_BY_LENGTH_DESC = Collections.unmodifiableList(keys);
    }

    private CostEstimator() {
    }

    /**
     * 成本预估（单次模式）。
     *
     * <p>重放一条交互记录恰好发起一次 LLM 调用：多轮上下文（previousTurns）
     * 作为请求的一部分携带，不产生额外调用。</p>
     *
     * @param testCases 待测用例列表
     * @param model     模型名称
     * @return 预估字符串，如 "预估 12 次 API 调用，约 $0.0480（模型：gpt-4o）"
     */
    public static String estimate(List<InteractionRecord> testCases, String model) {
        int totalCalls = testCases.size();
        double costPerCall = getCostPerCall(model);
        double estimatedCost = totalCalls * costPerCall;
        return String.format("预估 %d 次 API 调用，约 $%.4f（模型：%s）", totalCalls, estimatedCost, model);
    }

    /**
     * 按客户端名称（model name）估算单次调用成本。
     * 用于统计模式的 maxCostPerCase 截断计算。
     *
     * <p>模糊匹配策略：价格表键按长度降序做包含匹配（"gpt-4o-mini" 优先于 "gpt-4o"），
     * 单一价格源即 MODEL_COST，无第二套硬编码口径。</p>
     *
     * @param clientName 客户端名称（通常是 model 名称）
     * @return 单次调用成本（美元）
     */
    public static double estimateCostPerCall(String clientName) {
        if (clientName == null) return DEFAULT_COST_PER_CALL;
        String lower = clientName.toLowerCase(Locale.ROOT);
        for (String key : KEYS_BY_LENGTH_DESC) {
            if (lower.contains(key)) {
                return MODEL_COST.get(key);
            }
        }
        return MODEL_COST.getOrDefault(lower, DEFAULT_COST_PER_CALL);
    }

    /**
     * 统计模式成本预估 — 每条用例重放 sampleCount 次，每次一条恰好一次调用。
     *
     * @param testCases   待测用例列表
     * @param model       模型名称
     * @param sampleCount 采样次数
     * @return 预估字符串，如 "预估 5 用例 x 10 次 = 50 次 API 调用，约 $0.2000（模型：gpt-4o）"
     */
    public static String estimateStatistical(List<InteractionRecord> testCases, String model, int sampleCount) {
        int totalSamples = testCases.size() * sampleCount;
        double costPerCall = getCostPerCall(model);
        double estimatedCost = totalSamples * costPerCall;
        return String.format("预估 %d 用例 x %d 次 = %d 次 API 调用，约 $%.4f（模型：%s）", testCases.size(), sampleCount, totalSamples, estimatedCost, model);
    }

    /**
     * 获取模型的单次调用成本——与 {@link #estimateCostPerCall} 共用同一匹配口径，
     * 预估文案与执行器截断两个入口对同一模型名必须给出同一价格。
     */
    private static double getCostPerCall(String model) {
        return estimateCostPerCall(model);
    }
}
