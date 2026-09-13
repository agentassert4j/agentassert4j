package io.github.agentassert4j.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 价格覆盖文件的合并语义：并集覆盖快照（同族改价、新族补充、元信息键跳过），
 * 损坏形态安全退化（不抛异常、原表不动）。
 *
 * @author axy-yxa
 * @since 2026-09-11
 */
class CostEstimatorPriceOverrideTest {

    private static Map<String, double[]> baseTable() {
        Map<String, double[]> prices = new LinkedHashMap<>();
        prices.put("gpt-4o", new double[]{0.0000025, 0.00001});
        return prices;
    }

    @Test
    @DisplayName("同族改价 + 新族补充 + 元信息键跳过")
    void overrideMergesUnion() {
        Map<String, double[]> prices = baseTable();
        String override = "{\"_meta\":{\"note\":\"user file\"}," + "\"gpt-4o\":{\"input\":0.000001,\"output\":0.000004}," + "\"my-private-model\":{\"input\":0.0000001,\"output\":0.0000004}}";

        CostEstimator.applyPriceOverrides(prices, override);

        assertEquals(2, prices.size());
        assertArrayEquals(new double[]{0.000001, 0.000004}, prices.get("gpt-4o"), "同族必须覆盖快照价");
        assertArrayEquals(new double[]{0.0000001, 0.0000004}, prices.get("my-private-model"), "新族必须补充进表");
    }

    @Test
    @DisplayName("损坏形态安全退化：非法 JSON /非对象/无可用行均不动原表")
    void malformedOverridesAreNoOps() {
        Map<String, double[]> prices = baseTable();

        CostEstimator.applyPriceOverrides(prices, "not json at all");
        assertEquals(1, prices.size(), "非法 JSON 不得动原表");

        CostEstimator.applyPriceOverrides(prices, "[\"gpt-4o\"]");
        assertEquals(1, prices.size(), "非对象形态不得动原表");

        CostEstimator.applyPriceOverrides(prices, "{\"broken\":{\"input\":1}}");
        assertEquals(1, prices.size(), "缺 output 的行跳过——无可用行不得动原表");

        CostEstimator.applyPriceOverrides(prices, "   ");
        assertEquals(1, prices.size(), "空白视为无覆盖文件");
    }

    @Test
    @DisplayName("覆盖价进入计价口径（合并语义端到端）")
    void overrideFeedsCostEstimation() {
        Map<String, double[]> prices = baseTable();
        CostEstimator.applyPriceOverrides(prices, "{\"gpt-4o\":{\"input\":0.000001,\"output\":0.000002}}");

        double[] rates = prices.get("gpt-4o");
        assertEquals(1000 * rates[0] + 500 * rates[1], 1000 * 0.000001 + 500 * 0.000002, 1e-12, "覆盖后的单价必须直接参与 token 计价");
    }
}
