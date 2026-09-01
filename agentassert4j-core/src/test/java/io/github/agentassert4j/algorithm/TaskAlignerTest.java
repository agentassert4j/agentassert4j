package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
import io.github.agentassert4j.result.TaskRuleViolation;
import io.github.agentassert4j.result.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskAligner 的单元测试 — 对齐语义黄金测试：对齐键=invocationKey、
 * 缺/新增步骤=行为差异、matched 1:1 规范序配对、两侧指纹现场重提。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class TaskAlignerTest {

    private final DeterministicComparator comparator = new DeterministicComparator(ComparatorConfig.defaults());

    private InteractionRecord record(String id, long timestamp, String invocationKey, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(id);
        r.setSessionId("s");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setInvocationKey(invocationKey);
        r.setModelResponse(response);
        return r;
    }

    private InteractionRecord toolRecord(String id, long timestamp, String invocationKey, String toolName) {
        InteractionRecord r = record(id, timestamp, invocationKey, "{\"status\":\"ok\"}");
        ToolCall call = new ToolCall();
        call.setToolName(toolName);
        call.setSuccess(true);
        r.setToolCalls(Collections.singletonList(call));
        r.setHasToolCalls(true);
        return r;
    }

    private TaskChain chain(InteractionRecord... records) {
        TaskChain chain = new TaskChain();
        chain.setSessionId("s");
        chain.setRequestText("查订单");
        chain.setRecords(Arrays.asList(records));
        return chain;
    }

    @Test
    @DisplayName("黄金对齐：同构链全 PASS")
    void golden_identicalChains_pass() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "答A"), record("b2", 2000L, "invocation:y:h2", "答B")), chain(record("n1", 5000L, "invocation:x:h1", "答A"), record("n2", 6000L, "invocation:y:h2", "答B")), comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict());
        assertEquals(2, alignment.getSteps().size());
        assertEquals(StepKind.MATCHED, alignment.getSteps().get(0).getKind());
        assertEquals(Verdict.PASS, alignment.getSteps().get(0).getVerdict());
        assertEquals(0, alignment.getSteps().get(0).getSurplusCount());
    }

    @Test
    @DisplayName("缺步骤：基线有新链无 → CHANGED + MISSING")
    void missingStep_changed() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "答A"), record("b2", 2000L, "invocation:y:h2", "答B")), chain(record("n1", 5000L, "invocation:x:h1", "答A")), comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertEquals(2, alignment.getSteps().size());
        StepAlignment missing = alignment.getSteps().stream().filter(s -> s.getKind() == StepKind.MISSING).findFirst().orElseThrow(() -> new AssertionError("缺步骤未报告"));
        assertEquals("invocation:y:h2", missing.getInvocationKey());
        assertEquals("b2", missing.getBaselineRecordId());
    }

    @Test
    @DisplayName("新增步骤：新链有基线无 → CHANGED + ADDED")
    void addedStep_changed() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "答A")), chain(record("n1", 5000L, "invocation:x:h1", "答A"), record("n2", 6000L, "invocation:z:h3", "答C")), comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertEquals(2, alignment.getSteps().size());
        StepAlignment added = alignment.getSteps().stream().filter(s -> s.getKind() == StepKind.ADDED).findFirst().orElseThrow(() -> new AssertionError("新增步骤未报告"));
        assertEquals("invocation:z:h3", added.getInvocationKey());
        assertEquals("n2", added.getNewRecordId());
    }

    @Test
    @DisplayName("同调用点行为变化（工具维）→ 配对 CHANGED，差异明细随步骤")
    void matchedPair_toolDimensionChanged() {
        TaskAlignment alignment = TaskAligner.align(chain(toolRecord("b1", 1000L, "invocation:x:h1", "getOrder")), chain(toolRecord("n1", 5000L, "invocation:x:h1", "getInvoice")), comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertEquals(1, alignment.getSteps().size());
        StepAlignment step = alignment.getSteps().get(0);
        assertEquals(StepKind.MATCHED, step.getKind());
        assertEquals(Verdict.CHANGED, step.getVerdict());
        assertNotNull(step.getComparison(), "差异明细必须随步骤上抛");
        assertFalse(step.getComparison().isToolCallMatch());
    }

    @Test
    @DisplayName("同调用点多条记录 1:1 规范序配对，富余计数不判差异")
    void surplusRecords_countedNotJudged() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "答A"), record("b2", 2000L, "invocation:x:h1", "答A")), chain(record("n1", 5000L, "invocation:x:h1", "答A")), comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict(), "富余记录是同调用点重复执行，不判差异");
        assertEquals(1, alignment.getSteps().get(0).getSurplusCount());
    }

    @Test
    @DisplayName("纯文本措辞差异：结构同、文本异 → PASS（文本差异低置信呈现）")
    void wordingOnlyDifference_pass() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "您的订单已发货，请注意查收。")), chain(record("n1", 5000L, "invocation:x:h1", "订单已经发出去了哦，请留意物流信息。")), comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict(), "结构指纹同 = PASS；措辞波动是跨模型预期内");
        assertEquals(Verdict.PASS, alignment.getSteps().get(0).getVerdict());
    }

    @Test
    @DisplayName("前缀依赖标注：链内任一记录携带会话前缀 → prefixDependent")
    void prefixDependent_annotated() {
        InteractionRecord withHistory = record("b1", 1000L, "invocation:x:h1", "答A");
        withHistory.setPreviousTurns(Arrays.asList(new io.github.agentassert4j.model.TurnContext("user", "上一问")));

        TaskAlignment alignment = TaskAligner.align(chain(withHistory), chain(record("n1", 5000L, "invocation:x:h1", "答A")), comparator, null);

        assertTrue(alignment.isPrefixDependent(), "携带前缀的链必须标注，防上下文缺失误报");
    }

    @Test
    @DisplayName("链级时间随对齐结果上抛（基线时间标注义务）")
    void chainTimes_propagated() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "invocation:x:h1", "答A")), chain(record("n1", 9000L, "invocation:x:h1", "答A")), comparator, null);

        assertEquals(Long.valueOf(1000L), alignment.getBaselineTime());
        assertEquals(Long.valueOf(9000L), alignment.getNewChainTime());
    }

    private InteractionRecord labeled(String id, long timestamp, String invocationKey, String label, String response) {
        InteractionRecord r = record(id, timestamp, invocationKey, response);
        r.setInvocationId(label);
        return r;
    }

    private TaskChain declaredChain(String taskKey, InteractionRecord... records) {
        TaskChain chain = chain(records);
        chain.setRequestText(taskKey);
        chain.setDeclared(true);
        return chain;
    }

    @Test
    @DisplayName("缺必备步骤：对齐全 PASS 但违反 requiredSteps → 链级 CHANGED")
    void taskRule_missingRequiredStep_chainChanged() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\",\"B\"]}}}");
        TaskAlignment alignment = TaskAligner.align(declaredChain("refund-flow", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), declaredChain("refund-flow", labeled("n1", 5000L, "invocation:a:h1", "A", "答A")), comparator, rules);

        assertEquals(Verdict.CHANGED, alignment.getVerdict(), "缺必备步骤是行为差异，折叠进链级 CHANGED");
        assertEquals(1, alignment.getRuleViolations().size());
        assertEquals(TaskRuleViolation.Type.REQUIRED_STEP_MISSING, alignment.getRuleViolations().get(0).getType());
        assertTrue(alignment.getRuleViolations().get(0).getDetail().contains("B"), "明细点名缺失标签: " + alignment.getRuleViolations().get(0).getDetail());
    }

    @Test
    @DisplayName("次数越界：富余配对不判差异，但绝对计数超 max → 违规")
    void taskRule_countOutOfRange() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"steps\":{\"A\":{\"min\":1,\"max\":1}}}}}");
        TaskAlignment alignment = TaskAligner.align(declaredChain("t1", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), declaredChain("t1", labeled("n1", 5000L, "invocation:a:h1", "A", "答A"), labeled("n2", 6000L, "invocation:a:h1", "A", "答A")), comparator, rules);

        assertEquals(1, alignment.getSteps().get(0).getSurplusCount(), "配对富余本身不判差异");
        assertEquals(Verdict.CHANGED, alignment.getVerdict(), "绝对次数声明超界 = 违规（补上富余不判的缺口）");
        assertEquals(TaskRuleViolation.Type.STEP_COUNT_OUT_OF_RANGE, alignment.getRuleViolations().get(0).getType());
    }

    @Test
    @DisplayName("乱序：标签序列非声明子序列（含未出现）→ ORDER_VIOLATION")
    void taskRule_orderViolation() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredOrder\":[\"A\",\"B\"]}}}");
        // 新链执行顺序 B→A（记录规范序），基线 A→B；两侧调用点配对仍全 PASS
        TaskAlignment alignment = TaskAligner.align(declaredChain("t1", labeled("b1", 1000L, "invocation:a:h1", "A", "答A"), labeled("b2", 2000L, "invocation:b:h2", "B", "答B")), declaredChain("t1", labeled("n1", 5000L, "invocation:b:h2", "B", "答B"), labeled("n2", 6000L, "invocation:a:h1", "A", "答A")), comparator, rules);

        assertEquals(Verdict.PASS, alignment.getSteps().stream().filter(s -> s.getInvocationKey().equals("invocation:a:h1")).findFirst().orElseThrow(() -> new AssertionError("步骤缺失")).getVerdict(), "配对本身不判差异");
        assertEquals(1, alignment.getRuleViolations().size());
        assertEquals(TaskRuleViolation.Type.ORDER_VIOLATION, alignment.getRuleViolations().get(0).getType());
        assertEquals(Verdict.CHANGED, alignment.getVerdict());
    }

    @Test
    @DisplayName("违规呈现顺序钉死：requiredSteps → requiredOrder → steps")
    void taskRule_violationOrder_deterministic() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"Z\"],\"requiredOrder\":[\"A\",\"C\"],\"steps\":{\"A\":{\"min\":5}}}}}");
        TaskAlignment alignment = TaskAligner.align(declaredChain("t1", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), declaredChain("t1", labeled("n1", 5000L, "invocation:a:h1", "A", "答A")), comparator, rules);

        assertEquals(3, alignment.getRuleViolations().size());
        assertEquals(TaskRuleViolation.Type.REQUIRED_STEP_MISSING, alignment.getRuleViolations().get(0).getType());
        assertEquals(TaskRuleViolation.Type.ORDER_VIOLATION, alignment.getRuleViolations().get(1).getType());
        assertEquals(TaskRuleViolation.Type.STEP_COUNT_OUT_OF_RANGE, alignment.getRuleViolations().get(2).getType());
    }

    @Test
    @DisplayName("未声明链不评任务规则（零声明零涉入）")
    void taskRule_undeclaredChain_notEvaluated() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"A\"]}}}");
        TaskChain undeclared = chain(labeled("n1", 5000L, "invocation:a:h1", "A", "答A"));

        TaskAlignment alignment = TaskAligner.align(declaredChain("t1", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), undeclared, comparator, rules);

        assertEquals(Verdict.PASS, alignment.getVerdict(), "新链未声明 taskKey，规则静默不适用（呈现层另有诊断行）");
        assertTrue(alignment.getRuleViolations().isEmpty());
    }

    @Test
    @DisplayName("声明键精确匹配：规则键与声明 taskKey 不同则不评")
    void taskRule_keyMismatch_notEvaluated() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\"]}}}");

        TaskAlignment alignment = TaskAligner.align(declaredChain("refund-flow", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), declaredChain("other-flow", labeled("n1", 5000L, "invocation:a:h1", "A", "答A")), comparator, rules);

        assertEquals(Verdict.PASS, alignment.getVerdict());
        assertTrue(alignment.getRuleViolations().isEmpty());
    }

    @Test
    @DisplayName("无标签步骤不参与任务规则计数")
    void taskRule_unlabeledSteps_ignored() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"A\"]}}}");
        TaskAlignment alignment = TaskAligner.align(declaredChain("t1", labeled("b1", 1000L, "invocation:a:h1", "A", "答A")), declaredChain("t1", labeled("n1", 5000L, "invocation:a:h1", "A", "答A"), record("n2", 6000L, "invocation:x:h2", "答X")), comparator, rules);

        assertTrue(alignment.getRuleViolations().isEmpty(), "无 invocationId 的记录不参与标签计数，requiredSteps 由声明标签满足");
    }
}
