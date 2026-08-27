package io.github.agentassert4j.model;

import io.github.agentassert4j.result.StatisticalVerdict;
import io.github.agentassert4j.result.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * StatisticalRegressionResult 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class StatisticalRegressionResultTest {

    @Test
    void aggregate_allPass_stable() {
        List<SampleResult> samples = makeSamples(10, Verdict.PASS, 1.0);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
        assertEquals(10, result.getVerdictCounts().get(Verdict.PASS));
        assertEquals(1.0, result.getVerdictRates().get(Verdict.PASS), 0.001);
    }

    @Test
    void aggregate_passRate90_threshold90_stable() {
        // 9 PASS + 1 DIFF
        List<SampleResult> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) samples.add(new SampleResult(i + 1, Verdict.PASS, 1.0, null, 100));
        samples.add(new SampleResult(10, Verdict.DIFF, 0.85, "field changed", 100));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 0.9, 0.0);

        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
        assertEquals(0.9, result.getVerdictRates().get(Verdict.PASS), 0.001);
    }

    @Test
    void aggregate_passRate80_threshold90_unstable() {
        List<SampleResult> samples = new ArrayList<>();
        for (int i = 0; i < 8; i++) samples.add(new SampleResult(i + 1, Verdict.PASS, 1.0, null, 100));
        for (int i = 0; i < 2; i++) samples.add(new SampleResult(9 + i, Verdict.DIFF, 0.8, "diff", 100));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 0.9, 0.0);

        assertEquals(StatisticalVerdict.UNSTABLE, result.getStatisticalVerdict());
    }

    @Test
    void aggregate_regressionOverTolerance_flaky() {
        List<SampleResult> samples = new ArrayList<>();
        for (int i = 0; i < 7; i++) samples.add(new SampleResult(i + 1, Verdict.PASS, 1.0, null, 100));
        for (int i = 0; i < 3; i++) samples.add(new SampleResult(8 + i, Verdict.REGRESSION, 0.3, "regression", 100));

        // regressionTolerance = 0.2, 但 REGRESSION 占 30%
        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 0.9, 0.2);

        assertEquals(StatisticalVerdict.FLAKY, result.getStatisticalVerdict());
        assertEquals(0.3, result.getVerdictRates().get(Verdict.REGRESSION), 0.001);
    }

    @Test
    void aggregate_regressionWithinTolerance_notFlaky() {
        List<SampleResult> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) samples.add(new SampleResult(i + 1, Verdict.PASS, 1.0, null, 100));
        samples.add(new SampleResult(10, Verdict.REGRESSION, 0.3, "reg", 100));

        // 10% REGRESSION, tolerance 20%
        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 0.9, 0.2);

        // PASS 率 90% >= 0.9 → STABLE
        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
    }

    @Test
    void aggregate_emptySamples_insufficientNotStable() {
        // 原断言钉住的是 fail-open 缺陷（零样本默认稳定会静默放行 CI），随修复改写
        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", Collections.emptyList(), 0.9, 0.0);

        assertEquals(StatisticalVerdict.INSUFFICIENT_SAMPLES, result.getStatisticalVerdict());
        assertEquals(0, result.getActualSampleCount());
    }

    @Test
    void aggregate_errorSamples_excludedFromRates() {
        SampleResult timeout = new SampleResult(1, null, 0, "upstream timeout", 50);
        SampleResult pass = new SampleResult(2, Verdict.PASS, 1.0, null, 50);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", java.util.Arrays.asList(timeout, pass), 0.9, 0.0);

        assertEquals(1, result.getErrorSampleCount(), "超时样本计入错误数");
        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict(), "基础设施错误不进分母，单条 PASS 判稳定而非 FLAKY");
    }

    @Test
    void aggregate_allErrorSamples_insufficient() {
        SampleResult timeout = new SampleResult(1, null, 0, "upstream timeout", 50);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", Collections.singletonList(timeout), 0.9, 0.0);

        assertEquals(StatisticalVerdict.INSUFFICIENT_SAMPLES, result.getStatisticalVerdict(), "全部样本为基础设施错误时不可判定，不得按回归计");
    }

    @Test
    void aggregate_singleSample_correct() {
        List<SampleResult> samples = Collections.singletonList(new SampleResult(1, Verdict.PASS, 0.95, null, 50));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
        assertEquals(1, result.getActualSampleCount());
        assertEquals(0.95, result.getAverageScore(), 0.001);
        assertEquals(0.0, result.getScoreStdDev(), 0.001);
        assertEquals(0.95, result.getMinScore(), 0.001);
    }

    @Test
    void samples_isImmutable() {
        List<SampleResult> samples = makeSamples(3, Verdict.PASS, 1.0);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertThrows(UnsupportedOperationException.class, () -> result.getSamples().add(new SampleResult()));
    }

    @Test
    void verdictCounts_isImmutable() {
        List<SampleResult> samples = makeSamples(3, Verdict.PASS, 1.0);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertThrows(UnsupportedOperationException.class, () -> result.getVerdictCounts().put(Verdict.DIFF, 1));
    }

    @Test
    void verdictRates_isImmutable() {
        List<SampleResult> samples = makeSamples(3, Verdict.PASS, 1.0);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertThrows(UnsupportedOperationException.class, () -> result.getVerdictRates().put(Verdict.DIFF, 0.5));
    }

    @Test
    void aggregate_scoreStatistics() {
        // scores: 1.0, 0.9, 0.8 → mean=0.9, stddev=0.1, min=0.8
        List<SampleResult> samples = Arrays.asList(new SampleResult(1, Verdict.PASS, 1.0, null, 100), new SampleResult(2, Verdict.PASS, 0.9, null, 100), new SampleResult(3, Verdict.PASS, 0.8, null, 100));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertEquals(0.9, result.getAverageScore(), 0.001);
        assertEquals(0.1, result.getScoreStdDev(), 0.001);
        assertEquals(0.8, result.getMinScore(), 0.001);
    }

    @Test
    void aggregate_frequentDiffPatterns_top5() {
        List<SampleResult> samples = new ArrayList<>();
        // 6 PASS
        for (int i = 0; i < 6; i++) samples.add(new SampleResult(i + 1, Verdict.PASS, 1.0, null, 100));
        // 2x "tool A missing"
        samples.add(new SampleResult(7, Verdict.DIFF, 0.7, "tool A missing", 100));
        samples.add(new SampleResult(8, Verdict.DIFF, 0.7, "tool A missing", 100));
        // 1x "field changed"
        samples.add(new SampleResult(9, Verdict.DIFF, 0.8, "field changed", 100));
        // 1x "extra param"
        samples.add(new SampleResult(10, Verdict.REGRESSION, 0.3, "extra param", 100));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 0.9, 0.2);

        assertEquals(3, result.getFrequentDiffPatterns().size());
        assertEquals("tool A missing", result.getFrequentDiffPatterns().get(0));
    }

    @Test
    void aggregate_nullVerdict_treatedAsInfrastructureError() {
        // 原断言把基础设施错误样本计入 REGRESSION（网络抖动被当成行为回归），随修复改写
        List<SampleResult> samples = Arrays.asList(new SampleResult(1, Verdict.PASS, 1.0, null, 100), new SampleResult(2, null, 0.0, "error", 100));

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate("rec-1", "skill-1", samples, 1.0, 0.0);

        assertEquals(0, result.getVerdictCounts().get(Verdict.REGRESSION).intValue(), "基础设施错误不进回归计数");
        assertEquals(1, result.getErrorSampleCount());
        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
    }

    private List<SampleResult> makeSamples(int count, Verdict verdict, double score) {
        List<SampleResult> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new SampleResult(i + 1, verdict, score, null, 100));
        }
        return list;
    }
}
