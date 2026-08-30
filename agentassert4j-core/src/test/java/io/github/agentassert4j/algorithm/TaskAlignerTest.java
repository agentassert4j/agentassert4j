package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
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
}
