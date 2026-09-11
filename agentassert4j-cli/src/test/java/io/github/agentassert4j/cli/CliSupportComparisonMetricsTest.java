package io.github.agentassert4j.cli;

import io.github.agentassert4j.result.ComparisonResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * comparisonMetricsFragment 的 wire 契约：task-report 与 verify-report 两个报告面
 * 共用同一 dims 词表与 contentRules 合成规则，片段形态以本测试钉死——维度键名
 * 或合成规则变化属于机器契约变更，须同步 spec 并评审。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
class CliSupportComparisonMetricsTest {

    @Test
    @DisplayName("dims 五键形态与 contentRules 合成规则")
    void fragmentShape() {
        ComparisonResult comparison = new ComparisonResult();
        comparison.setScore(0.91);
        comparison.setToolCallMatch(true);
        comparison.setParamTypeMatch(true);
        comparison.setStructureMatch(false);
        comparison.setKeywordMatch(true);
        comparison.setRegexMatch(false);
        comparison.setBehaviorMatch(true);
        comparison.setSummary("tool calls match");

        assertEquals(",\"similarity\":0.91" + ",\"dims\":{\"toolSet\":true,\"paramTypes\":true,\"outputStructure\":false,\"contentRules\":false,\"behaviors\":true}" + ",\"summary\":\"tool calls match\"", CliSupport.comparisonMetricsFragment(comparison));
    }

    @Test
    @DisplayName("summary 缺席时片段不含 summary 键")
    void summaryOmittedWhenAbsent() {
        ComparisonResult comparison = new ComparisonResult();
        comparison.setScore(1.0);
        comparison.setToolCallMatch(true);
        comparison.setParamTypeMatch(true);
        comparison.setStructureMatch(true);
        comparison.setKeywordMatch(true);
        comparison.setRegexMatch(true);
        comparison.setBehaviorMatch(true);

        assertEquals(",\"similarity\":1.0" + ",\"dims\":{\"toolSet\":true,\"paramTypes\":true,\"outputStructure\":true,\"contentRules\":true,\"behaviors\":true}", CliSupport.comparisonMetricsFragment(comparison));
    }
}
