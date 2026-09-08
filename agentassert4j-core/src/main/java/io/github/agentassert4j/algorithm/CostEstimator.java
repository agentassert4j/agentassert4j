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
 * <p>两个入口共用同一张表：{@link #estimate} 用「假设 1000 输入
 * 500 输出 token」的固定口径做执行前预估文案；{@link #estimateCallCostUsd}
 * 用调用实际 token 数在捕获时刻计价，冻结进记录的成本列。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class CostEstimator {

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
     * 作为请求的一部分携带，不产生额外调用。模型不在价格快照中时只报调用
     * 次数、不编造费用——预估文案与捕获计价遵守同一条「无价格不出货币数」原则。</p>
     *
     * @param testCases 待测用例列表
     * @param model     模型名称
     * @return 预估字符串，如 "Estimated 12 API calls, approx. $0.0480 (model: gpt-4o)"
     */
    public static String estimate(List<InteractionRecord> testCases, String model) {
        int totalCalls = testCases.size();
        Double costPerCall = estimateCallCostUsd(model, PREVIEW_INPUT_TOKENS, PREVIEW_OUTPUT_TOKENS);
        String calls = totalCalls + " API call" + (totalCalls == 1 ? "" : "s");
        if (costPerCall == null) {
            return String.format("Estimated %s (model %s not in the price snapshot; cost unknown)", calls, model);
        }
        double estimatedCost = totalCalls * costPerCall;
        return String.format("Estimated %s, approx. $%.4f (model: %s)", calls, estimatedCost, model);
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
            throw new IllegalStateException("Failed to read model_prices.json", e);
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
