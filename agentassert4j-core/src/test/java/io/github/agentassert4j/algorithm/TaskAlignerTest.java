package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.BaselineStep;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
import io.github.agentassert4j.result.TaskRuleViolation;
import io.github.agentassert4j.result.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskAligner 的单元测试 — 对齐语义黄金测试：对齐键=invocationKey、
 * 缺/新增步骤=行为差异、matched 1:1 规范序配对、两侧指纹现场重提；
 * 链末判定域（每调用点只判组末执行、任务纪律与前缀看全链）。
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

    private InteractionRecord labeledRecord(String id, long timestamp, String label, String subdivision, String response) {
        InteractionRecord r = record(id, timestamp, "invocation:" + label + ":" + subdivision, response);
        r.setInvocationId(label);
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
    @DisplayName("配对计数：首个 CHANGED 早停后 comparedPairs=1、skippedPairs=1（同键双记录）")
    void comparedSkippedPairs_earlyStopOnFirstChanged() {
        TaskAlignment alignment = TaskAligner.align(chain(labeledRecord("b1", 1000L, "x", "h1", "{\"v\":1}"), labeledRecord("b2", 2000L, "x", "h1", "{\"v\":1}")), chain(labeledRecord("n1", 5000L, "x", "h1", "{\"v\":2,\"w\":3}"), labeledRecord("n2", 6000L, "x", "h1", "{\"v\":1}")), comparator, null);

        StepAlignment step = alignment.getSteps().get(0);
        assertEquals(StepKind.MATCHED, step.getKind());
        assertEquals(Verdict.CHANGED, step.getVerdict());
        assertEquals(1, step.getComparedPairs(), "首个 CHANGED 即停，只执行了第一对判定");
        assertEquals(1, step.getSkippedPairs(), "计划两对，早停后第二对计为 skipped");
        assertEquals(0, step.getSurplusCount());
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
        withHistory.setPreviousTurns(Arrays.asList(new TurnContext("user", "上一问")));

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
    @DisplayName("违规呈现顺序锁定：requiredSteps → requiredOrder → steps")
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

    @Test
    @DisplayName("跨版本配对：同标签异细分且行为一致 → MATCHED PASS + versionSwitch 注记")
    void crossVersion_sameLabel_sameBehavior_pass() {
        TaskAlignment alignment = TaskAligner.align(chain(labeledRecord("b1", 1000L, "audit", "h1", "答A")), chain(labeledRecord("n1", 5000L, "audit", "h2", "答A")), comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict(), "同一调用点跨模板版本，行为一致即 PASS");
        assertEquals(1, alignment.getSteps().size());
        StepAlignment step = alignment.getSteps().get(0);
        assertEquals(StepKind.MATCHED, step.getKind());
        assertEquals(Verdict.PASS, step.getVerdict());
        assertTrue(step.isVersionSwitch(), "两侧细分不同必须注记跨版本");
        assertEquals("audit", step.getInvocationLabel());
        assertEquals("h1", step.getBaselineSubdivision());
        assertEquals("h2", step.getNewSubdivision());
        assertEquals(1, alignment.getCrossVersionCount());
    }

    @Test
    @DisplayName("报告标签解码还原人读形：与 status 侧 displayKey 同形，不出现百分号编码形")
    void invocationLabel_percentDecoded() {
        TaskAlignment alignment = TaskAligner.align(chain(labeledRecord("b1", 1000L, "ord[1]", "h1", "答A")), chain(labeledRecord("n1", 5000L, "ord[1]", "h2", "答A")), comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict());
        assertEquals("ord[1]", alignment.getSteps().get(0).getInvocationLabel(), "标签必须是人读形而非编码形");
    }

    @Test
    @DisplayName("跨版本配对行为变化（工具维）→ CHANGED，注记不掩盖判定")
    void crossVersion_sameLabel_behaviorChanged() {
        InteractionRecord baseline = labeledRecord("b1", 1000L, "audit", "h1", "{\"status\":\"ok\"}");
        baseline.setToolCalls(Collections.singletonList(toolCall("getOrder")));
        baseline.setHasToolCalls(true);
        InteractionRecord latest = labeledRecord("n1", 5000L, "audit", "h2", "{\"status\":\"ok\"}");
        latest.setToolCalls(Collections.singletonList(toolCall("getInvoice")));
        latest.setHasToolCalls(true);
        TaskAlignment alignment = TaskAligner.align(chain(baseline), chain(latest), comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertTrue(alignment.getSteps().get(0).isVersionSwitch());
        assertEquals(Verdict.CHANGED, alignment.getSteps().get(0).getVerdict());
    }

    private ToolCall toolCall(String name) {
        ToolCall call = new ToolCall();
        call.setToolName(name);
        call.setSuccess(true);
        return call;
    }

    @Test
    @DisplayName("零声明跨版本：无业务身份则版本即身份，缺/新增不猜配对")
    void zeroDeclaration_crossVersion_forksMissingAdded() {
        TaskAlignment alignment = TaskAligner.align(chain(record("b1", 1000L, "skeleton:aaa", "答A")), chain(record("n1", 5000L, "skeleton:bbb", "答A")), comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertEquals(StepKind.MISSING, alignment.getSteps().get(0).getKind());
        assertEquals(StepKind.ADDED, alignment.getSteps().get(1).getKind());
        assertEquals(0, alignment.getCrossVersionCount(), "零声明不产生跨版本配对");
    }

    @Test
    @DisplayName("混合链：有标签步跨版本配对，无标签步按完整键分组，互不串扰")
    void mixedChain_labeledAndUnlabeled() {
        TaskAlignment alignment = TaskAligner.align(chain(labeledRecord("b1", 1000L, "audit", "h1", "答A"), record("b2", 2000L, "skeleton:aaa", "答B")), chain(labeledRecord("n1", 5000L, "audit", "h2", "答A"), record("n2", 6000L, "skeleton:bbb", "答B")), comparator, null);

        assertEquals(3, alignment.getSteps().size(), "audit 配对 + skeleton 缺 + skeleton 增");
        long matched = alignment.getSteps().stream().filter(s -> s.getKind() == StepKind.MATCHED).count();
        assertEquals(1, matched);
        assertTrue(alignment.getSteps().get(0).isVersionSwitch());
    }

    @Test
    @DisplayName("declaredLabelOfKey：从键解析声明标签，无声明形态返回 null")
    void declaredLabelOfKey_parses() {
        assertEquals("audit", TaskAligner.declaredLabelOfKey("invocation:audit:h1"));
        assertEquals("a:b", TaskAligner.declaredLabelOfKey("invocation:a%3Ab:h1"), "编码标签解码还原");
        assertNull(TaskAligner.declaredLabelOfKey("skeleton:abc"));
        assertNull(TaskAligner.declaredLabelOfKey("template:abc"));
        assertNull(TaskAligner.declaredLabelOfKey(null));
    }

    private List<String> recordIds(List<InteractionRecord> records) {
        return records.stream().map(InteractionRecord::getRecordId).collect(Collectors.toList());
    }

    /**
     * 基线侧步骤手工装配（fromProfiles 形态：按完整键每记录一步，指纹现场提取）——
     * 链末判定的基线侧入参同构。
     */
    private Map<String, List<BaselineStep>> profileSteps(InteractionRecord... records) {
        Map<String, List<BaselineStep>> steps = new LinkedHashMap<>();
        for (InteractionRecord r : records) {
            BaselineStep step = new BaselineStep();
            step.setInvocationKey(r.getInvocationKey());
            step.setInvocationId(r.getInvocationId());
            step.setRecordId(r.getRecordId());
            step.setFingerprints(Collections.singletonList(FingerprintExtractor.extract(r, null, r.getInvocationId())));
            steps.put(r.getInvocationKey(), Collections.singletonList(step));
        }
        return steps;
    }

    @Test
    @DisplayName("invocationGroups：标签优先跨模板版本一组、无标签按完整键、组内保链序")
    void invocationGroups_labelFirst_chainOrder() {
        TaskChain chain = chain(
                labeledRecord("r1", 1000L, "order", "h1", "{\"v\":1}"),
                record("r2", 2000L, "skeleton:aaa", "答B"),
                labeledRecord("r3", 3000L, "order", "h2", "{\"v\":1}"));

        Map<String, List<InteractionRecord>> groups = TaskAligner.invocationGroups(chain);

        assertEquals(2, groups.size(), "两标签同组 + 无标签独立一组");
        assertEquals(Arrays.asList("r1", "r3"), recordIds(groups.get("L:order")), "同标签跨模板版本同组且保链序");
        assertEquals(Collections.singletonList("r2"), recordIds(groups.get("K:skeleton:aaa")));
    }

    @Test
    @DisplayName("trimToLatestPerInvocation：每调用点只留组末记录，链元数据原样携带")
    void trimToLatest_keepsLastPerGroupAndMetadata() {
        TaskChain chain = chain(
                labeledRecord("r1", 1000L, "order", "h1", "{\"v\":1}"),
                labeledRecord("r2", 2000L, "order", "h2", "{\"v\":1}"),
                record("r3", 3000L, "skeleton:aaa", "答B"));
        chain.setSessionId("session-x");

        TaskChain trimmed = TaskAligner.trimToLatestPerInvocation(chain);

        assertEquals(Arrays.asList("r2", "r3"), recordIds(trimmed.getRecords()), "组末记录按组首现序输出");
        assertEquals("session-x", trimmed.getSessionId());
        assertEquals("查订单", trimmed.getRequestText());
        assertFalse(trimmed.isDeclared());
    }

    @Test
    @DisplayName("链末判定：坏草稿在前、好链末在后 → PASS 且 earlierRecords=1（草稿不挡门）")
    void alignLatest_badDraftFirst_passesWithNote() {
        TaskChain mixed = chain(
                labeledRecord("bad", 4000L, "order", "h1", "{\"v\":1,\"w\":3}"),
                labeledRecord("good", 5000L, "order", "h1", "{\"v\":1}"));

        TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(
                profileSteps(labeledRecord("g", 1000L, "order", "h1", "{\"v\":1}")), mixed, comparator, null);

        assertEquals(Verdict.PASS, alignment.getVerdict(), "判定对象=链末执行（与画像一致），草稿不进判定");
        StepAlignment step = alignment.getSteps().get(0);
        assertEquals("good", step.getNewRecordId());
        assertEquals(1, step.getEarlierRecords(), "草稿计数进透明层字段");
    }

    @Test
    @DisplayName("链末判定：好草稿在前、坏链末在后 → CHANGED（链末回归仍挡门）")
    void alignLatest_badChainFinal_changed() {
        TaskChain mixed = chain(
                labeledRecord("good", 4000L, "order", "h1", "{\"v\":1}"),
                labeledRecord("bad", 5000L, "order", "h1", "{\"v\":1,\"w\":3}"));

        TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(
                profileSteps(labeledRecord("g", 1000L, "order", "h1", "{\"v\":1}")), mixed, comparator, null);

        assertEquals(Verdict.CHANGED, alignment.getVerdict());
        assertEquals("bad", alignment.getSteps().get(0).getNewRecordId());
    }

    @Test
    @DisplayName("链末判定任务纪律看全链：同标签恰两次满足 min=2，裁剪链会少数误判")
    void alignLatest_taskRulesSeeFullChain() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"查订单\":{\"steps\":{\"order\":{\"min\":2,\"max\":2}}}}}");
        TaskChain newChain = declaredChain("查订单",
                labeledRecord("n1", 4000L, "order", "h1", "{\"v\":1}"),
                labeledRecord("n2", 5000L, "order", "h1", "{\"v\":1}"));

        TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(
                profileSteps(labeledRecord("g", 1000L, "order", "h1", "{\"v\":1}")), newChain, comparator, rules);

        assertTrue(alignment.getRuleViolations().isEmpty(), "次数纪律在全链上评估（order 出现 2 次满足 min=2），裁剪链只剩 1 次会误判");
        assertEquals(Verdict.PASS, alignment.getVerdict());
    }

    @Test
    @DisplayName("链末判定 prefixDependent 看全链：前缀标记挂在被裁剪的草稿记录上仍标注")
    void alignLatest_prefixFromFullChain() {
        InteractionRecord draft = labeledRecord("n1", 4000L, "order", "h1", "{\"v\":1}");
        draft.setPreviousTurns(Collections.singletonList(new TurnContext("user", "上一问")));
        TaskChain newChain = chain(draft, labeledRecord("n2", 5000L, "order", "h1", "{\"v\":1}"));

        TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(
                profileSteps(labeledRecord("g", 1000L, "order", "h1", "{\"v\":1}")), newChain, comparator, null);

        assertTrue(alignment.isPrefixDependent(), "前缀标记可能只在草稿记录上，必须看全链");
    }

    @Nested
    @DisplayName("多形态基线判定 - 认可集合的链末成员判定")
    class MultiShapeBaseline {

        /**
         * 构造带认可形态集合的基线步骤（同一调用点，responses 每个串产出一个形态）。
         */
        private Map<String, List<BaselineStep>> shapeSteps(String label, String hash, String... responses) {
            List<DeterministicFingerprint> shapes = new ArrayList<>();
            for (String response : responses) {
                shapes.add(FingerprintExtractor.extract(labeledRecord("seed", 1000L, label, hash, response), null, label));
            }
            BaselineStep step = new BaselineStep();
            step.setInvocationKey(labeledRecord("seed", 1000L, label, hash, responses[0]).getInvocationKey());
            step.setInvocationId(label);
            step.setFingerprints(shapes);
            Map<String, List<BaselineStep>> steps = new LinkedHashMap<>();
            steps.put(step.getInvocationKey(), Collections.singletonList(step));
            return steps;
        }

        @Test
        @DisplayName("跨链混形双绿：链末为集合任一认可形态 → PASS（共享调用点多形态的根治语义）")
        void chainEndOfAnyApprovedShape_passes() {
            Map<String, List<BaselineStep>> shapes = shapeSteps("order", "h1", "{\"v\":1}", "{\"v\":1,\"w\":{}}");

            TaskAlignment endsWithA = TaskAligner.alignLatestPerInvocation(shapes,
                    chain(labeledRecord("final-a", 5000L, "order", "h1", "{\"v\":1}")), comparator, null);
            assertEquals(Verdict.PASS, endsWithA.getVerdict(), "链末=首形态 → PASS");

            TaskAlignment endsWithB = TaskAligner.alignLatestPerInvocation(shapes,
                    chain(labeledRecord("final-b", 5000L, "order", "h1", "{\"v\":1,\"w\":{}}")), comparator, null);
            assertEquals(Verdict.PASS, endsWithB.getVerdict(), "链末=次形态（accept 追加认可）→ 同样 PASS");
        }

        @Test
        @DisplayName("集合外形态 → CHANGED（成员判定不稀释检出力），差异挂最近似成员")
        void chainEndOutsideSet_changed() {
            Map<String, List<BaselineStep>> shapes = shapeSteps("order", "h1", "{\"v\":1}", "{\"v\":1,\"w\":{}}");

            TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(shapes,
                    chain(labeledRecord("final-c", 5000L, "order", "h1", "{\"v\":1,\"w\":{},\"extra\":{}}")), comparator, null);

            assertEquals(Verdict.CHANGED, alignment.getVerdict(), "任何未被认可的形态照常被抓");
            assertNotNull(alignment.getSteps().get(0).getComparison(), "差异明细对最近似成员计算，信号不降级");
        }

        @Test
        @DisplayName("命中序号注记：集合大小>1 时步骤携带命中形态的序号与集合大小")
        void matchedShapePosition_recorded() {
            Map<String, List<BaselineStep>> shapes = shapeSteps("order", "h1", "{\"v\":1}", "{\"v\":1,\"w\":{}}");

            TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(shapes,
                    chain(labeledRecord("final-b", 5000L, "order", "h1", "{\"v\":1,\"w\":{}}")), comparator, null);

            StepAlignment step = alignment.getSteps().get(0);
            assertEquals(2, step.getBaselineShapeCount(), "集合大小注记");
            assertEquals(2, step.getBaselineShapeIndex(), "命中第 2 形态（1 基）");
        }

        @Test
        @DisplayName("空集合是上游契约违约 → 显式失败（不被误判为行为差异）")
        void emptyShapes_failLoudly() {
            BaselineStep step = new BaselineStep();
            step.setInvocationKey(labeledRecord("seed", 1000L, "order", "h1", "{}").getInvocationKey());
            step.setInvocationId("order");
            step.setFingerprints(new ArrayList<DeterministicFingerprint>());
            Map<String, List<BaselineStep>> steps = new LinkedHashMap<>();
            steps.put(step.getInvocationKey(), Collections.singletonList(step));

            assertThrows(IllegalStateException.class, () -> TaskAligner.alignLatestPerInvocation(
                    steps, chain(labeledRecord("final", 5000L, "order", "h1", "{\"v\":1}")), comparator, null));
        }
    }

    @Test
    @DisplayName("纯配对拆分回归：公开 align(Map) 仍在传入链上评任务纪律并折叠")
    void align_mapEntry_foldsRulesOnPassedChain() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"t1\":{\"requiredSteps\":[\"A\",\"B\"]}}}");
        Map<String, List<BaselineStep>> steps = profileSteps(
                labeled("g1", 1000L, "invocation:a:h1", "A", "答A"),
                labeled("g2", 2000L, "invocation:b:h2", "B", "答B"));

        TaskAlignment complete = TaskAligner.align(steps, declaredChain("t1",
                labeled("n1", 5000L, "invocation:a:h1", "A", "答A"),
                labeled("n2", 6000L, "invocation:b:h2", "B", "答B")), comparator, rules);
        assertEquals(Verdict.PASS, complete.getVerdict(), "requiredSteps A/B 均在场 → 无违规");
        assertTrue(complete.getRuleViolations().isEmpty());

        TaskAlignment missingB = TaskAligner.align(steps, declaredChain("t1",
                labeled("n1", 5000L, "invocation:a:h1", "A", "答A")), comparator, rules);
        assertEquals(Verdict.CHANGED, missingB.getVerdict());
        assertEquals(1, missingB.getRuleViolations().size(), "map 入口的纪律折叠未随拆分丢失");
    }
}
