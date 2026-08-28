package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CostEstimator 的单元测试 — 价格快照驱动的预估与捕获计价契约。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class CostEstimatorTest {

    private static final double DELTA = 1e-9;

    private InteractionRecord makeRecord(int turnIndex) {
        InteractionRecord r = new InteractionRecord();
        r.setTurnIndex(turnIndex);
        return r;
    }

    @Test
    void estimateCallCostUsd_exactFamily_ratesFromSnapshot() {
        // gpt-4o: 2.5e-6/输入 + 1e-5/输出（快照现值）
        Double cost = CostEstimator.estimateCallCostUsd("gpt-4o", 1_000_000L, 500_000L);
        assertEquals(2.5 + 5.0, cost, 1e-6);
    }

    @Test
    void estimateCallCostUsd_datedVariant_fallsIntoFamilyPrice() {
        // 带日期的部署变体按最长包含匹配归入族价
        Double dated = CostEstimator.estimateCallCostUsd("gpt-4o-2024-08-06", 1_000_000L, 500_000L);
        assertEquals(7.5, dated, 1e-6);
    }

    @Test
    void estimateCallCostUsd_unknownModel_returnsNull() {
        // 价格只是装饰层：查不到不编造费用
        assertNull(CostEstimator.estimateCallCostUsd("my-local-llama", 1000L, 500L));
        assertNull(CostEstimator.estimateCallCostUsd(null, 1000L, 500L));
    }

    @Test
    void estimateCallCostUsd_caseInsensitive() {
        Double upper = CostEstimator.estimateCallCostUsd("GPT-4O", 1_000_000L, 0L);
        assertEquals(2.5, upper, 1e-6);
    }

    @Test
    void estimateCostPerCall_previewUsesAssumptionTokens() {
        // 预估口径固定 1000 输入 / 500 输出：gpt-4o → 2.5e-3 + 5e-3
        assertEquals(0.0075, CostEstimator.estimateCostPerCall("gpt-4o"), DELTA);
    }

    @Test
    void estimateCostPerCall_ladder_longerKeyWins() {
        // 长键优先：mini 不得落进 gpt-4o 族价
        assertEquals(0.00045, CostEstimator.estimateCostPerCall("gpt-4o-mini"), DELTA);
        // gpt-4o 比 gpt-4 更长，先参与匹配
        assertEquals(0.0075, CostEstimator.estimateCostPerCall("gpt-4o"), DELTA);
        assertEquals(0.06, CostEstimator.estimateCostPerCall("gpt-4"), DELTA);
    }

    @Test
    void estimateCostPerCall_deepseekFamily() {
        assertEquals(0.00049, CostEstimator.estimateCostPerCall("deepseek-chat"), DELTA);
        assertEquals(0.00049, CostEstimator.estimateCostPerCall("deepseek-reasoner"), DELTA);
    }

    @Test
    void estimateCostPerCall_unknownModel_fallbackPrice() {
        assertEquals(0.003, CostEstimator.estimateCostPerCall("llama-3"), DELTA);
        assertEquals(0.003, CostEstimator.estimateCostPerCall(null), DELTA);
    }

    @Test
    void estimate_previewLineContainsCallCountAndModel() {
        List<InteractionRecord> cases = Collections.singletonList(makeRecord(0));
        String result = CostEstimator.estimate(cases, "gpt-4o");

        assertTrue(result.contains("预估 1 次 API 调用"));
        assertTrue(result.contains("gpt-4o"));
        assertTrue(result.contains("$0.0075"), "预估文案按 1000/500 token 口径计价: " + result);
    }

    @Test
    void estimate_multiTurnRecord_countsAsSingleCall() {
        // 重放一条记录恰好一次调用：多轮上下文在同一次请求内携带
        List<InteractionRecord> cases = Arrays.asList(makeRecord(0), makeRecord(2));
        String result = CostEstimator.estimate(cases, "gpt-4o");

        assertTrue(result.contains("预估 2 次 API 调用"));
    }

    @Test
    void estimateStatistical_multiTurnRecord_countsAsSingleCall() {
        List<InteractionRecord> cases = Collections.singletonList(makeRecord(1));
        String result = CostEstimator.estimateStatistical(cases, "deepseek-chat", 5);

        // 1 用例 x 5 采样 = 5 次调用（轮次不放大调用数）
        assertTrue(result.contains("预估 1 用例 x 5 次 = 5 次 API 调用"));
        assertTrue(result.contains("$0.0025"), "5 x 0.00049 = 0.00245 四舍五入到 0.0025: " + result);
    }

    @Test
    void estimate_unknownModel_noFabricatedCurrency() {
        // 无价格不出货币数：预估文案对快照外模型只报调用次数（内网私有模型是常态客群）
        List<InteractionRecord> cases = Collections.singletonList(makeRecord(0));
        String result = CostEstimator.estimate(cases, "my-private-model");

        assertTrue(result.contains("预估 1 次 API 调用"));
        assertTrue(result.contains("费用未知"), "无价格模型必须明示费用未知: " + result);
        assertFalse(result.contains("$"), "无价格不得出现任何货币金额: " + result);
    }

    @Test
    void estimateStatistical_unknownModel_noFabricatedCurrency() {
        List<InteractionRecord> cases = Collections.singletonList(makeRecord(0));
        String result = CostEstimator.estimateStatistical(cases, "my-private-model", 5);

        assertTrue(result.contains("预估 1 用例 x 5 次 = 5 次 API 调用"));
        assertTrue(result.contains("费用未知"));
        assertFalse(result.contains("$"));
    }
}
