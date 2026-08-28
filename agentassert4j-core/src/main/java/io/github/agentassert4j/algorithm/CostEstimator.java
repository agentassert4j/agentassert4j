package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 成本估算 — 价格快照驱动的执行前预估与调用计价。
 *
 * <p>价格真源是随 jar 分发的精选快照（model_prices.json，按主流模型族裁剪，
 * 发布前从 LiteLLM 的 MIT 价格库重新生成），键为模型族名，查找按最长包含
 * 匹配把带日期的变体归入族价。查不到的模型不做货币估算——价格只是 token
 * 统计之上的装饰层，缺失时只报 token 消耗，不编造费用，也永不参与判定。</p>
 *
 * <p>两个入口共用同一张表：{@link #estimateCostPerCall} 用「假设 1000 输入
 * 500 输出 token」的固定口径做执行前预估文案与截断计算；{@link #estimateCallCostUsd}
 * 用调用实际 token 数在捕获时刻计价，冻结进记录的成本列。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class CostEstimator {

    /**
     * 未知模型的预估兜底单价（美元/次）——仅用于执行前预估文案；
     * 捕获计价对未知模型返回 null，绝不编造费用
     */
    private static final double UNKNOWN_MODEL_PREVIEW_COST = 0.003;
    /**
     * 预估口径的假设 token 量：单次调用 1000 输入 / 500 输出
     */
    private static final long PREVIEW_INPUT_TOKENS = 1000;
    private static final long PREVIEW_OUTPUT_TOKENS = 500;

    /**
     * 模型族名 → [每输入 token 单价, 每输出 token 单价]（美元）
     */
    private static final Map<String, double[]> TOKEN_PRICES = loadPrices();
    /**
     * 价格表键按长度降序——包含匹配时 "gpt-4o-mini" 必须先于 "gpt-4o" 参与
     */
    private static final List<String> PRICE_KEYS_BY_LENGTH_DESC = buildKeysByLengthDesc();

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
        double costPerCall = estimateCostPerCall(model);
        double estimatedCost = totalCalls * costPerCall;
        return String.format("预估 %d 次 API 调用，约 $%.4f（模型：%s）", totalCalls, estimatedCost, model);
    }

    /**
     * 按客户端名称（通常是模型名）估算单次调用成本（美元）——固定「1000 输入
     * 500 输出 token」的预估口径，未知模型退回兜底单价。用于执行前预估文案
     * 与成本截断计算；捕获时刻的精确计价走 {@link #estimateCallCostUsd}。
     *
     * @param clientName 客户端名称（通常是模型名称）
     * @return 单次调用成本（美元）
     */
    public static double estimateCostPerCall(String clientName) {
        Double cost = estimateCallCostUsd(clientName, PREVIEW_INPUT_TOKENS, PREVIEW_OUTPUT_TOKENS);
        return cost != null ? cost : UNKNOWN_MODEL_PREVIEW_COST;
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
        double costPerCall = estimateCostPerCall(model);
        double estimatedCost = totalSamples * costPerCall;
        return String.format("预估 %d 用例 x %d 次 = %d 次 API 调用，约 $%.4f（模型：%s）", testCases.size(), sampleCount, totalSamples, estimatedCost, model);
    }

    /**
     * 按调用实际 token 量计价（美元）；模型不在价格快照中时返回 null。
     * 由捕获侧在调用时刻调用，结果冻结进记录的成本列。
     *
     * @param model        模型名称（优先 served 模型，回退请求模型）
     * @param inputTokens  输入 token 总量（归一口径，含供应商缓存语义的合成）
     * @param outputTokens 输出 token 量
     * @return 费用（美元）；模型无价格时 null
     */
    public static Double estimateCallCostUsd(String model, long inputTokens, long outputTokens) {
        double[] rates = ratesFor(model);
        if (rates == null) {
            return null;
        }
        return inputTokens * rates[0] + outputTokens * rates[1];
    }

    private static double[] ratesFor(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        double[] exact = TOKEN_PRICES.get(lower);
        if (exact != null) {
            return exact;
        }
        for (String key : PRICE_KEYS_BY_LENGTH_DESC) {
            if (lower.contains(key)) {
                return TOKEN_PRICES.get(key);
            }
        }
        return null;
    }

    private static Map<String, double[]> loadPrices() {
        InputStream in = CostEstimator.class.getResourceAsStream("model_prices.json");
        if (in == null) {
            // 快照缺席按无价格表处理：预估走兜底单价，捕获计价返回 null
            return Collections.emptyMap();
        }
        try {
            Object parsed = RecursiveJsonParser.parse(readAll(in));
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }
            Map<String, double[]> prices = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 下划线前缀键是快照元信息，非价格行
                if (key.startsWith("_") || !(entry.getValue() instanceof Map)) {
                    continue;
                }
                Double input = asDouble(((Map<?, ?>) entry.getValue()).get("input"));
                Double output = asDouble(((Map<?, ?>) entry.getValue()).get("output"));
                if (input != null && output != null) {
                    prices.put(key.toLowerCase(Locale.ROOT), new double[]{input, output});
                }
            }
            return Collections.unmodifiableMap(prices);
        } catch (RuntimeException e) {
            // 快照损坏等同缺席，退化不中断
            return Collections.emptyMap();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readAll(InputStream in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("model_prices.json 读取失败", e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private static List<String> buildKeysByLengthDesc() {
        List<String> keys = new ArrayList<>(TOKEN_PRICES.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return Collections.unmodifiableList(keys);
    }
}
