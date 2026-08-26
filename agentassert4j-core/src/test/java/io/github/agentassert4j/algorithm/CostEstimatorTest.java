package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostEstimatorTest {

    private InteractionRecord makeRecord(int turnIndex) {
        InteractionRecord r = new InteractionRecord();
        r.setTurnIndex(turnIndex);
        return r;
    }

    @Test
    void estimate_singleTestCase_turnIndex0() {
        List<InteractionRecord> cases = List.of(makeRecord(0));
        String result = CostEstimator.estimate(cases, "gpt-4o");

        assertTrue(result.contains("预估 1 次 API 调用"));
        assertTrue(result.contains("$0.0040"));
        assertTrue(result.contains("gpt-4o"));
    }

    @Test
    void estimate_multipleCasesWithTurns() {
        List<InteractionRecord> cases = List.of(
                makeRecord(0),  // 1 call
                makeRecord(2)   // 3 calls
        );
        String result = CostEstimator.estimate(cases, "gpt-4o");

        assertTrue(result.contains("预估 4 次 API 调用"));
        assertTrue(result.contains("$0.0160"));
    }

    @Test
    void estimate_deepseekModel() {
        List<InteractionRecord> cases = List.of(makeRecord(0));
        String result = CostEstimator.estimate(cases, "deepseek-chat");

        assertTrue(result.contains("$0.0010"));
    }

    @Test
    void estimate_unknownModel_usesDefault() {
        List<InteractionRecord> cases = List.of(makeRecord(0));
        String result = CostEstimator.estimate(cases, "unknown-model");

        assertTrue(result.contains("$0.0030"));
    }

    @Test
    void estimate_nullModel_usesDefault() {
        List<InteractionRecord> cases = List.of(makeRecord(0));
        String result = CostEstimator.estimate(cases, null);

        assertTrue(result.contains("$0.0030"));
    }

    @Test
    void estimateCostPerCall_knownModels() {
        assertEquals(0.004, CostEstimator.estimateCostPerCall("gpt-4o"));
        assertEquals(0.0004, CostEstimator.estimateCostPerCall("gpt-4o-mini"));
        assertEquals(0.03, CostEstimator.estimateCostPerCall("gpt-4"));
        assertEquals(0.0005, CostEstimator.estimateCostPerCall("gpt-3.5-turbo"));
        assertEquals(0.001, CostEstimator.estimateCostPerCall("deepseek-chat"));
        assertEquals(0.002, CostEstimator.estimateCostPerCall("qwen-plus"));
        assertEquals(0.0005, CostEstimator.estimateCostPerCall("qwen-turbo"));
    }

    @Test
    void estimateCostPerCall_fuzzyMatch_longFirst() {
        // gpt-4o-mini 应匹配 0.0004，而非 gpt-4o 的 0.004
        assertEquals(0.0004, CostEstimator.estimateCostPerCall("gpt-4o-mini"));
        // 确认 gpt-4o 仍然是 0.004
        assertEquals(0.004, CostEstimator.estimateCostPerCall("gpt-4o"));
    }

    @Test
    void estimateCostPerCall_caseInsensitive() {
        assertEquals(0.004, CostEstimator.estimateCostPerCall("GPT-4O"));
        assertEquals(0.001, CostEstimator.estimateCostPerCall("DeepSeek-Chat"));
    }

    @Test
    void estimateCostPerCall_unknownModel() {
        assertEquals(0.003, CostEstimator.estimateCostPerCall("llama-3"));
    }

    @Test
    void estimateCostPerCall_null() {
        assertEquals(0.003, CostEstimator.estimateCostPerCall(null));
    }

    @Test
    void estimateStatistical_correctCalculation() {
        List<InteractionRecord> cases = List.of(makeRecord(0), makeRecord(0));
        String result = CostEstimator.estimateStatistical(cases, "gpt-4o", 10);

        assertTrue(result.contains("预估 2 用例 x 10 次 = 20 次 API 调用"));
        assertTrue(result.contains("$0.0800"));
    }

    @Test
    void estimateStatistical_withTurns() {
        List<InteractionRecord> cases = List.of(makeRecord(1));  // turnIndex=1 → 2 calls
        String result = CostEstimator.estimateStatistical(cases, "deepseek-chat", 5);

        // 2 calls x 5 samples = 10 total
        assertTrue(result.contains("预估 1 用例 x 5 次 = 10 次 API 调用"));
        assertTrue(result.contains("$0.0100"));
    }

    @Test
    void estimateStatistical_format() {
        List<InteractionRecord> cases = List.of(makeRecord(0));
        String result = CostEstimator.estimateStatistical(cases, "gpt-4", 3);

        // 格式："预估 N 用例 x M 次 = T 次 API 调用，约 $X.XXXX（模型：xxx）"
        assertTrue(result.startsWith("预估"));
        assertTrue(result.contains("API 调用"));
        assertTrue(result.contains("$"));
        assertTrue(result.contains("（模型：gpt-4）"));
    }
}
