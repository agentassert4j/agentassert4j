package io.github.agentassert4j.cli;

import io.github.agentassert4j.result.ComparisonResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * comparisonMetricsFragment 的 wire 契约：task-report 与 verify-report 两份报告
 * 共用同一 dims 词表与 contentRules 合成规则，片段形态以本测试锁定——维度键名
 * 或合成规则变化属于机器契约变更，须同步 spec 并评审。
 * 五键一律 Matched 后缀（true = 该维一致无差异）——键名自述极性，
 * 纯 JSON 消费者不再依赖人类摘要行反推（round8 双宿主发现 D-dims）。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
class CliSupportComparisonMetricsTest {

    @Test
    @DisplayName("dims 五键 Matched 形态与 contentRules 合成规则")
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

        assertEquals(",\"similarity\":0.91" + ",\"dims\":{\"toolSetMatched\":true,\"paramTypesMatched\":true,\"outputStructureMatched\":false,\"contentRulesMatched\":false,\"behaviorsMatched\":true}" + ",\"summary\":\"tool calls match\"", CliSupport.comparisonMetricsFragment(comparison));
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

        assertEquals(",\"similarity\":1.0" + ",\"dims\":{\"toolSetMatched\":true,\"paramTypesMatched\":true,\"outputStructureMatched\":true,\"contentRulesMatched\":true,\"behaviorsMatched\":true}", CliSupport.comparisonMetricsFragment(comparison));
    }
}
