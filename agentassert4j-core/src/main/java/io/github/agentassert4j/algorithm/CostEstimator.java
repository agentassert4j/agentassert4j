package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本预估 — 执行回归测试前估算 API 调用费用。
 *
 * <p>纯静态工具类，按模型的每次调用成本估算（美元/次，基于 input+output 平均 token）。</p>
 */
public final class CostEstimator {

    private CostEstimator() {}

    /** 按模型的每次调用成本估算（美元/次） */
    private static final Map<String, Double> MODEL_COST;
    /** 未知模型的默认值 */
    private static final double DEFAULT_COST_PER_CALL = 0.003;

    static {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("gpt-4o", 0.004);
        map.put("gpt-4o-mini", 0.0004);
        map.put("gpt-4", 0.03);
        map.put("gpt-3.5-turbo", 0.0005);
        map.put("deepseek-chat", 0.001);
        map.put("qwen-plus", 0.002);
        map.put("qwen-turbo", 0.0005);
        MODEL_COST = Map.copyOf(map);
    }

    /**
     * 成本预估（单次模式）。
     *
     * @param testCases 待测用例列表
     * @param model     模型名称
     * @return 预估字符串，如 "预估 12 次 API 调用，约 $0.0480（模型：gpt-4o）"
     */
    public static String estimate(List<InteractionRecord> testCases, String model) {
        int totalCalls = testCases.stream()
                .mapToInt(r -> r.getTurnIndex() + 1)
                .sum();
        double costPerCall = getCostPerCall(model);
        double estimatedCost = totalCalls * costPerCall;
        return String.format("预估 %d 次 API 调用，约 $%.4f（模型：%s）",
                totalCalls, estimatedCost, model);
    }

    /**
     * 按客户端名称（model name）估算单次调用成本。
     * 用于统计模式的 maxCostPerCase 截断计算。
     *
     * <p>模糊匹配策略：先匹配长名称（如 gpt-4o-mini），再匹配短名称（如 gpt-4o）。</p>
     *
     * @param clientName 客户端名称（通常是 model 名称）
     * @return 单次调用成本（美元）
     */
    public static double estimateCostPerCall(String clientName) {
        if (clientName == null) return DEFAULT_COST_PER_CALL;
        String lower = clientName.toLowerCase();

        // 先匹配长名称
        if (lower.contains("gpt-4o-mini")) return 0.0004;
        if (lower.contains("gpt-4o")) return 0.004;
        if (lower.contains("gpt-4")) return 0.03;
        if (lower.contains("gpt-3.5")) return 0.0005;
        if (lower.contains("deepseek")) return 0.001;
        if (lower.contains("qwen-turbo")) return 0.0005;
        if (lower.contains("qwen")) return 0.002;

        // 精确匹配已知模型表
        return MODEL_COST.getOrDefault(lower, DEFAULT_COST_PER_CALL);
    }

    /**
     * 统计模式成本预估。
     *
     * @param testCases   待测用例列表
     * @param model       模型名称
     * @param sampleCount 采样次数
     * @return 预估字符串，如 "预估 5 用例 x 10 次 = 50 次 API 调用，约 $0.2000（模型：gpt-4o）"
     */
    public static String estimateStatistical(List<InteractionRecord> testCases,
                                              String model, int sampleCount) {
        int totalCalls = testCases.stream()
                .mapToInt(r -> r.getTurnIndex() + 1)
                .sum();
        int totalSamples = totalCalls * sampleCount;
        double costPerCall = getCostPerCall(model);
        double estimatedCost = totalSamples * costPerCall;
        return String.format("预估 %d 用例 x %d 次 = %d 次 API 调用，约 $%.4f（模型：%s）",
                testCases.size(), sampleCount, totalSamples, estimatedCost, model);
    }

    /**
     * 获取模型的单次调用成本。
     */
    private static double getCostPerCall(String model) {
        if (model == null) return DEFAULT_COST_PER_CALL;
        return MODEL_COST.getOrDefault(model.toLowerCase(), DEFAULT_COST_PER_CALL);
    }
}
