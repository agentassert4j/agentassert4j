package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskReplayRunner 的单元测试 — 任务域行为：
 * 冻结重放分歧即停、预算恰发、真实对比配对、退出码 0/1/2。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class TaskReplayRunnerTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private StubLlmClient stubClient;
    private ByteArrayOutputStream output;
    private TaskReplayRunner runner;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("task.db").toString());
        repository.initialize();
        stubClient = new StubLlmClient();
        output = new ByteArrayOutputStream();
        runner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private void saveRecord(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey(invocationKey);
        r.setTemplateHash("h-" + invocationKey);
        r.setModelResponse(response);
        repository.saveInteraction(r);
    }

    private void saveRecord(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String label, String templateHash, String response, String servedModel) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey(invocationKey);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setModelResponse(response);
        r.setServedModel(servedModel);
        repository.saveInteraction(r);
    }

    @Test
    @DisplayName("真实对比：两条同名链按调用点对齐，缺步骤 → 退出码 1 并报告")
    void alignment_missingStep_exit1() {
        // 基线链：两步；新链：只做了第一步
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, null, null, null, null);

        assertEquals(1, exit, "缺步骤是行为差异，退出码 1");
        String report = output.toString();
        assertTrue(report.contains("缺步骤"), "缺步骤必须可见: " + report);
        assertTrue(report.contains("对齐汇总"), report);
    }

    @Test
    @DisplayName("任务选择器：请求文本含控制字符时可见化回显（CR 不再静默吞字）")
    void taskSelector_controlCharsVisible() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单\r", false, false, null, null, null, null);

        assertEquals(2, exit);
        String report = output.toString();
        assertTrue(report.contains("<CR>"), "控制字符必须转义回显，否则用户看不见差异来源: " + report);
        assertTrue(report.contains("未找到请求文本匹配"), report);
    }

    @Test
    @DisplayName("冻结重放：--old-prompt 无命中时诊断提供文本哈希与靶链现存模板变体")
    void oldPromptMiss_diagnosticListsVariants() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, "新提示词内容", HashUtil.sha256("不相关的旧提示词文本"), null, null);

        assertEquals(2, exit);
        String report = output.toString();
        assertTrue(report.contains("sha256="), "必须回显提供文本的哈希供与归档对照: " + report);
        assertTrue(report.contains("invocation:x:h1@"), "必须列出靶链现存模板变体: " + report);
        assertTrue(report.contains("完整 system 模板全文"), "必须指明旧提示词的真相源要求: " + report);
    }

    @Test
    @DisplayName("真实对比：同构链 → 退出码 0")
    void alignment_identicalChains_exit0() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:x:h1", "答A");

        assertEquals(0, runner.run("查订单", false, false, null, null, null, null));
    }

    @Test
    @DisplayName("选链器：精确命中优先——短前缀不再静默扩选到前缀家族的其他任务")
    void taskSelector_exactBeatsPrefix() {
        saveRecord("v1", "s1", 1000L, "V1", "invocation:x:h1", "答1");
        saveRecord("v10", "s2", 9000L, "V10", "invocation:x:h1", "答10");

        int exit = runner.run("V1", false, false, null, null, null, null);

        assertEquals(0, exit);
        String report = output.toString();
        assertTrue(report.contains("任务「V1」"), "必须选中精确命中的 V1: " + report);
        assertFalse(report.contains("任务「V10」"), "前缀家族的 V10 不得被静默选中: " + report);
    }

    @Test
    @DisplayName("选链器：精确未命中且前缀命中多个不同任务文本 → 歧义报错列候选，退出码 2")
    void taskSelector_prefixAmbiguous_exit2() {
        saveRecord("v1", "s1", 1000L, "V1", "invocation:x:h1", "答1");
        saveRecord("v10", "s2", 2000L, "V10", "invocation:x:h1", "答10");

        int exit = runner.run("V", false, false, null, null, null, null);

        assertEquals(2, exit, "歧义前缀必须报错");
        String report = output.toString();
        assertTrue(report.contains("V1") && report.contains("V10"), "必须列出全部候选: " + report);
        assertTrue(report.contains("更长前缀"), "必须指引提供更长前缀: " + report);
    }

    @Test
    @DisplayName("选链器：精确未命中且前缀唯一 → 采用唯一候选")
    void taskSelector_uniquePrefix_adopted() {
        saveRecord("v10", "s2", 1000L, "V10", "invocation:x:h1", "答10");

        int exit = runner.run("V1", false, false, null, null, null, null);

        assertEquals(0, exit);
        assertTrue(output.toString().contains("任务「V10」"), "前缀唯一候选应被采用: " + output);
    }

    @Test
    @DisplayName("选链器：冻结重放路径同款歧义守卫")
    void taskSelector_frozenReplay_ambiguous_exit2() {
        saveRecord("v1", "s1", 1000L, "V1", "invocation:x:h1", "答1");
        saveRecord("v10", "s2", 2000L, "V10", "invocation:x:h1", "答10");

        int exit = runner.run("V", false, false, "新提示词", null, null, null);

        assertEquals(2, exit, "冻结重放与真实对比共用选链器语义");
    }

    @Test
    @DisplayName("选链器：同文本多链是任务多轮而非歧义 → 取最新对齐")
    void taskSelector_sameTextMultiChains_notAmbiguous() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-mid", 2000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:x:h1", "答A");

        assertEquals(0, runner.run("查订单", false, false, null, null, null, null), "同文本多链取最新 vs 次新，不触发歧义");
    }

    @Test
    @DisplayName("真实对比：仅一条链 → 自建基线，退出码 0 并标注")
    void alignment_singleChain_selfEstablish_exit0() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, null, null, null, null);

        assertEquals(0, exit);
        assertTrue(output.toString().contains("首录即基线"), "自建基线必须标注: " + output);
    }

    @Test
    @DisplayName("真实对比：前缀无匹配 → 退出码 2")
    void alignment_noMatch_exit2() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        assertEquals(2, runner.run("不存在的任务", false, false, null, null, null, null));
    }

    @Test
    @DisplayName("冻结重放：全链 PASS → 退出码 0，逐记录真重放")
    void frozenReplay_allPass_exit0() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");
        stubClient.responseText = "答A";

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null);

        assertEquals(0, exit);
        assertEquals(2, stubClient.callCount, "无影响裁剪时全链真重放");
    }

    @Test
    @DisplayName("分歧即停：首个 CHANGED 后续记录不再真重放（task 级推广）")
    void frozenReplay_divergenceStopsChain() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "基线回答一");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "基线回答二");
        stubClient.responseText = "完全不同的新回答，而且这一次回答被模型大幅扩写成了超过一百个字符的长文本，输出长度数量级从个位跳到百位，输出结构维度必然判定差异。";

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null);

        assertEquals(1, exit);
        assertEquals(1, stubClient.callCount, "分歧即停：CHANGED 之后不得继续真重放");
        assertTrue(output.toString().contains("分歧后下游"), "分歧后下游必须标注条件态: " + output);
    }

    @Test
    @DisplayName("预算恰发：max-total-calls=1 的两步链 → 1 次真重放 + 1 步跳过，退出码 2")
    void frozenReplay_budgetPool_exactCalls() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");
        stubClient.responseText = "答A";

        int exit = runner.run("查订单", false, false, "new prompt", null, 1, null);

        assertEquals(2, exit, "有跳过且无 CHANGED → 证据不完整退出码 2");
        assertEquals(1, stubClient.callCount, "预算恰发 N 次");
        assertTrue(output.toString().contains("预算耗尽"), report(output));
    }

    @Test
    @DisplayName("预算池内 CHANGED 优先于跳过：exit 1 而非 2")
    void frozenReplay_changedBeatsSkipped() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "基线回答一");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "基线回答二");
        stubClient.responseText = "完全不同的新回答，而且这一次回答被模型大幅扩写成了超过一百个字符的长文本，输出长度数量级从个位跳到百位，输出结构维度必然判定差异。";

        int exit = runner.run("查订单", false, false, "new prompt", null, 1, null);

        assertEquals(1, exit, "已有 CHANGED 判定时预算跳过不得稀释为 2");
    }

    @Test
    @DisplayName("链式半重放记录走既有链式引擎：编排分歧也触发分歧即停")
    void frozenReplay_chainedRecord_divergenceStops() {
        // 一步 = 一次带完整工具编排的记录（链式半重放资格），基线编排 getOrder；
        // 桩让重放决策 getInvoice → 第 1 轮即分歧，不产生第二次调用
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", null);
        InteractionRecord record = repository.findBySessionId("s-old").get(0);
        ToolCall call = new ToolCall();
        call.setToolName("getOrder");
        call.setArguments(Collections.singletonMap("orderId", "ORD-001"));
        call.setArgTypes(Collections.singletonMap("orderId", "string"));
        call.setSuccess(true);
        call.setResult("{\"status\":\"shipped\"}");
        record.setToolCalls(Collections.singletonList(call));
        record.setHasToolCalls(true);
        repository.saveInteraction(record);
        stubClient.toolCallName = "getInvoice";
        stubClient.toolCallResponse = true;

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null);

        assertEquals(1, exit);
        assertEquals(1, stubClient.callCount, "链式分歧即停：仅一次决策轮调用");
        assertTrue(output.toString().contains("CHANGED"), report(output));
    }

    @Test
    @DisplayName("JSON 模式：task-report/1 单行输出且 stdout 无进度")
    void jsonMode_singleLineReport() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:x:h1", "答A");
        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        ByteArrayOutputStream sharedErr = new ByteArrayOutputStream();
        TaskReplayRunner jsonRunner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(sharedErr, true), true);

        int exit = jsonRunner.run("查订单", false, false, null, null, null, null);

        assertEquals(0, exit);
        String json = jsonOut.toString().trim();
        assertTrue(json.startsWith("{\"schema\":\"agentassert4j.task-report/1\""), "单行报告以 schema 开头: " + json);
        assertTrue(json.contains("\"mode\":\"task-align\""), json);
        assertFalse(json.contains("\n"), "必须单行");
    }

    @Test
    @DisplayName("冻结重放：仅模板与旧提示词一致的记录真重放，异模板步骤继承 PASS")
    void frozenReplay_directTemplateOnly() {
        String oldPrompt = "旧版系统提示词全文";
        String hitKey = "invocation:verdict:" + HashUtil.sha256(oldPrompt);
        saveRecord("b1", "s-old", 1000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");
        saveRecord("b2", "s-old", 2000L, null, "invocation:other:h-other", "other", HashUtil.sha256("别的模板"), "答B", "dev-model");
        stubClient.responseText = "答A";

        int exit = runner.run("查订单", false, false, "new prompt", HashUtil.sha256(oldPrompt), null, null);

        assertEquals(0, exit);
        assertEquals(1, stubClient.callCount, "只有旧提示词命中的记录真重放");
        String report = output.toString();
        assertTrue(report.contains("继承 PASS"), "异模板步骤必须标注继承: " + report);
        assertFalse(report.contains("最新链"), "单链输出不得带链角色噪声: " + report);
    }

    @Test
    @DisplayName("多链冻结重放：链块带历史链/最新链定性，汇总带链数口径")
    void frozenReplay_multiChain_annotatesRoles() {
        String oldPrompt = "旧版系统提示词全文";
        String hitKey = "invocation:verdict:" + HashUtil.sha256(oldPrompt);
        saveRecord("b1", "s-old", 1000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");
        saveRecord("n1", "s-new", 9000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");
        stubClient.responseText = "答A";

        int exit = runner.run("查订单", false, false, "new prompt", HashUtil.sha256(oldPrompt), null, null);

        assertEquals(0, exit);
        String report = output.toString();
        assertTrue(report.contains("，历史链）："), "早链必须标历史链: " + report);
        assertTrue(report.contains("，最新链）："), "晚链必须标最新链: " + report);
        assertTrue(report.contains("2 条链共"), "多链汇总必须带链数口径: " + report);
    }

    @Test
    @DisplayName("多链干跑计划：链块同款定性标注")
    void taskDryRun_multiChain_annotatesRoles() {
        String oldPrompt = "旧版系统提示词全文";
        String hitKey = "invocation:verdict:" + HashUtil.sha256(oldPrompt);
        saveRecord("b1", "s-old", 1000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");
        saveRecord("n1", "s-new", 9000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");

        int exit = runner.run("查订单", false, false, "new prompt", HashUtil.sha256(oldPrompt), null, null, true);

        assertEquals(0, exit);
        assertEquals(0, stubClient.callCount);
        String plan = output.toString();
        assertTrue(plan.contains("，历史链）："), plan);
        assertTrue(plan.contains("，最新链）："), plan);
    }

    @Test
    @DisplayName("退出码：LLM 全部执行失败无判定 → 2（证据缺口不冒充绿）")
    void frozenReplay_allErrors_exit2() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        stubClient.throwApiError = true;

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null);

        assertEquals(2, exit);
    }

    @Test
    @DisplayName("任务域干跑：只出执行计划与成本预估，零调用零建档")
    void taskDryRun_planOnly_noCallsNoProfiles() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null, true);

        assertEquals(0, exit);
        assertEquals(0, stubClient.callCount, "干跑不得发起任何真实调用");
        assertTrue(repository.findAllInvocations().isEmpty(), "干跑不得自动建档");
        String plan = output.toString();
        assertTrue(plan.contains("真重放（受影响）"), plan);
        assertTrue(plan.contains("dry-run：共 1 条任务链，真重放 2 步 / 继承 0 步"), plan);
        assertTrue(plan.contains("预估 2 次 API 调用"), plan);
    }

    @Test
    @DisplayName("任务域干跑：--old-prompt 命中才计划真重放，其余标继承")
    void taskDryRun_oldPromptAffectedOnly() {
        String oldPrompt = "旧版系统提示词全文";
        String hitKey = "invocation:verdict:" + HashUtil.sha256(oldPrompt);
        saveRecord("b1", "s-old", 1000L, "查订单", hitKey, "verdict", HashUtil.sha256(oldPrompt), "答A", "dev-model");
        saveRecord("b2", "s-old", 2000L, null, "invocation:other:h-other", "other", HashUtil.sha256("别的模板"), "答B", "dev-model");

        int exit = runner.run("查订单", false, false, "new prompt", HashUtil.sha256(oldPrompt), null, null, true);

        assertEquals(0, exit);
        assertEquals(0, stubClient.callCount, "干跑不得发起任何真实调用");
        String plan = output.toString();
        assertTrue(plan.contains("真重放（受影响）"), plan);
        assertTrue(plan.contains("继承 PASS（未受影响）"), plan);
        assertTrue(plan.contains("真重放 1 步 / 继承 1 步"), plan);
    }

    @Test
    @DisplayName("任务域干跑 JSON：单行 task-report/1，mode=task-dry-run")
    void taskDryRun_jsonSingleLine() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");
        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        TaskReplayRunner jsonRunner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);

        int exit = jsonRunner.run("查订单", false, false, "new prompt", null, null, null, true);

        assertEquals(0, exit);
        assertEquals(0, stubClient.callCount);
        String json = jsonOut.toString().trim();
        assertTrue(json.startsWith("{\"schema\":\"agentassert4j.task-report/1\""), json);
        assertTrue(json.contains("\"mode\":\"task-dry-run\""), json);
        assertTrue(json.contains("\"plannedReplay\":2"), json);
        assertFalse(json.contains("\n"), "必须单行");
    }

    @Test
    @DisplayName("对齐干跑：输出链配对计划、规则适用性与零调用声明")
    void taskDryRun_alignPlanPrinted() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("n1", "s-new", 5000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, null, null, null, null, true);

        assertEquals(0, exit);
        String report = output.toString();
        assertTrue(report.contains("对齐计划"), "干跑输出对齐计划: " + report);
        assertTrue(report.contains("将配对：基线链 session s-old"), "计划载明基线链: " + report);
        assertTrue(report.contains("零 LLM 调用"), "计划载明零调用: " + report);
    }

    @Test
    @DisplayName("对齐干跑：首录链标注将自建基线且未执行")
    void taskDryRun_alignSelfEstablishNoted() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, null, null, null, null, true);

        assertEquals(0, exit);
        assertTrue(output.toString().contains("将自建基线（dry-run 未执行）"), output.toString());
    }

    @Test
    @DisplayName("跨版本配对：同标签异版本且行为一致 → PASS exit 0 + 版本注记")
    void align_crossVersion_sameBehavior_exit0() {
        saveDeclaredRecord("b1", "s-old", 1000L, "refund-flow", "invocation:A:h1", "A", "答A", "dev-model", 100, 20);
        saveDeclaredRecord("n1", "s-new", 9000L, "refund-flow", "invocation:A:h2", "A", "答A", "dev-model", 100, 20);

        int exit = runner.run("refund-flow", false, false, null, null, null, null);

        assertEquals(0, exit, "同调用点跨模板版本且行为一致，不再是缺/新增洪水");
        String report = output.toString();
        assertTrue(report.contains("跨版本配对——PASS"), "步骤行带版本注记: " + report);
        assertTrue(report.contains("| 跨版本 1"), "汇总载明跨版本计数: " + report);
        assertTrue(report.contains("受控实验用 --old-prompt 冻结重放"), "混杂变量提示指向冻结重放: " + report);
    }

    @Test
    @DisplayName("跨版本配对 JSON：versionSwitch/invocationLabel/crossVersion additive 字段")
    void align_crossVersion_jsonFields() {
        saveDeclaredRecord("b1", "s-old", 1000L, "refund-flow", "invocation:A:h1", "A", "答A", "dev-model", 100, 20);
        saveDeclaredRecord("n1", "s-new", 9000L, "refund-flow", "invocation:A:h2", "A", "答A", "dev-model", 100, 20);
        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        TaskReplayRunner jsonRunner = runnerWithRules(new InvocationRulesConfig(), true, jsonOut);

        int exit = jsonRunner.run("refund-flow", false, false, null, null, null, null);

        assertEquals(0, exit);
        String json = jsonOut.toString().trim();
        assertTrue(json.contains("\"versionSwitch\":true"), json);
        assertTrue(json.contains("\"invocationLabel\":\"A\""), json);
        assertTrue(json.contains("\"baselineSubdivision\":\"h1\""), json);
        assertTrue(json.contains("\"newSubdivision\":\"h2\""), json);
        assertTrue(json.contains("\"crossVersion\":1"), json);
    }

    @Test
    @DisplayName("P6 注记：规则必备步骤缺失时，缺步骤行交叉引用任务规则")
    void taskRule_missingStep_annotation() {
        saveDeclaredRecord("b1", "s-old", 1000L, "refund-flow", "invocation:A:h1", "A", "答A", "dev-model", 0, 0);
        saveDeclaredRecord("b2", "s-old", 2000L, "refund-flow", "invocation:B:h1", "B", "答B", "dev-model", 0, 0);
        saveDeclaredRecord("n1", "s-new", 9000L, "refund-flow", "invocation:A:h1", "A", "答A", "dev-model", 0, 0);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\",\"B\"]}}}"), false, target);

        int exit = ruleRunner.run("refund-flow", false, false, null, null, null, null);

        assertEquals(1, exit);
        String report = target.toString();
        assertTrue(report.contains("缺步骤——基线执行了「B@h1」，新链未调用（违反任务规则：必备步骤）"), "缺步骤行交叉引用规则: " + report);
    }

    @Test
    @DisplayName("门控失配根因分支：库内无任何 template_hash 时如实指出录制侧缺口")
    void oldPromptMiss_rootCauseHint() {
        saveRawRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRawRecord("n1", "s-new", 5000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查订单", false, false, "新提示词全文", HashUtil.sha256("旧提示词全文"), null, null, false);

        assertEquals(2, exit);
        assertTrue(output.toString().contains("未携带 template_hash"), "诊断指出真实根因而非只怀疑文本不等: " + output);
    }

    private void saveRawRecord(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey(invocationKey);
        r.setModelResponse(response);
        repository.saveInteraction(r);
    }

    private void saveDeclaredRecord(String recordId, String sessionId, long timestamp, String taskKey, String invocationKey, String label, String response, String servedModel, int inputTokens, int outputTokens) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setInvocationKey(invocationKey);
        r.setInvocationId(label);
        r.setModelResponse(response);
        r.setServedModel(servedModel);
        r.setInputTokens(inputTokens);
        r.setOutputTokens(outputTokens);
        r.setMetadata("{\"taskKey\":\"" + taskKey + "\"}");
        repository.saveInteraction(r);
    }

    private TaskReplayRunner runnerWithRules(InvocationRulesConfig rules, boolean jsonMode, ByteArrayOutputStream target) {
        return new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), rules, TestExecutionConfig.defaults(), new PrintStream(target, true), new PrintStream(target, true), jsonMode);
    }

    @Test
    @DisplayName("任务规则：缺必备步骤 → exit 1 + 违规行 + 汇总违规计数")
    void taskRule_violation_exit1() {
        saveDeclaredRecord("b1", "s-old", 1000L, "refund-flow", "invocation:a:h1", "A", "答A", "dev-model", 0, 0);
        saveDeclaredRecord("n1", "s-new", 9000L, "refund-flow", "invocation:a:h1", "A", "答A", "dev-model", 0, 0);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\",\"B\"]}}}"), false, target);

        int exit = ruleRunner.run("refund-flow", false, false, null, null, null, null);

        assertEquals(1, exit, "任务规则违规折叠进链级 CHANGED → exit 1");
        String reportLine = target.toString();
        assertTrue(reportLine.contains("违反任务规则: 缺少必备步骤「B」"), reportLine);
        assertTrue(reportLine.contains("| 违规 1"), reportLine);
    }

    @Test
    @DisplayName("任务规则：未声明 taskKey 的链不评，出诊断行防「配了没生效」")
    void taskRule_undeclaredChain_diagnostic() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:a:h1", "答A");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:a:h1", "答A");
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\"]}}}"), false, target);

        int exit = ruleRunner.run("查订单", false, false, null, null, null, null);

        assertEquals(0, exit);
        assertTrue(target.toString().contains("本任务未声明 taskKey，任务规则不适用"), target.toString());
    }

    @Test
    @DisplayName("任务规则 JSON：summary 违规计数 + ruleViolations 数组，仍单行")
    void taskRule_json_violations() {
        saveDeclaredRecord("b1", "s-old", 1000L, "refund-flow", "invocation:a:h1", "A", "答A", "dev-model", 0, 0);
        saveDeclaredRecord("n1", "s-new", 9000L, "refund-flow", "invocation:a:h1", "A", "答A", "dev-model", 0, 0);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(InvocationRulesConfig.fromJson("{\"tasks\":{\"refund-flow\":{\"requiredSteps\":[\"A\",\"B\"]}}}"), true, target);

        int exit = ruleRunner.run("refund-flow", false, false, null, null, null, null);

        assertEquals(1, exit);
        String json = target.toString().trim();
        assertTrue(json.startsWith("{\"schema\":\"agentassert4j.task-report/1\""), json);
        assertTrue(json.contains("\"ruleViolations\":1"), "summary 违规计数（CI 免于数数组）: " + json);
        assertTrue(json.contains("\"ruleViolations\":[{\"type\":\"REQUIRED_STEP_MISSING\",\"label\":\"B\""), "违规数组含类型与标签: " + json);
        assertFalse(json.contains("\n"), "必须单行");
    }

    @Test
    @DisplayName("成本对照：token 合计恒显示；有价模型附货币数，无价模型整项省略")
    void costComparison_tokensAlways_costWhenPriced() {
        saveDeclaredRecord("b1", "s-old", 1000L, "t1", "invocation:a:h1", "A", "答A", "unknown-model-x", 1000, 500);
        saveDeclaredRecord("n1", "s-new", 9000L, "t1", "invocation:a:h1", "A", "答A", "gpt-4o", 1000, 500);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(new InvocationRulesConfig(), false, target);

        int exit = ruleRunner.run("t1", false, false, null, null, null, null);

        assertEquals(0, exit);
        assertTrue(target.toString().contains("成本对照: 基线 1500 → 当前 1500/$0.0075"), target.toString());
    }

    @Test
    @DisplayName("成本对照 JSON：tokens 恒在；任一记录无价即整项省略 costUsd")
    void costJson_unknownModel_costOmitted() {
        saveDeclaredRecord("b1", "s-old", 1000L, "t1", "invocation:a:h1", "A", "答A", "unknown-model-x", 1000, 500);
        saveDeclaredRecord("n1", "s-new", 9000L, "t1", "invocation:a:h1", "A", "答A", "unknown-model-x", 1000, 500);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(new InvocationRulesConfig(), true, target);

        int exit = ruleRunner.run("t1", false, false, null, null, null, null);

        assertEquals(0, exit);
        String json = target.toString().trim();
        assertTrue(json.contains("\"baseline\":{\"tokens\":1500},\"current\":{\"tokens\":1500}"), json);
        assertFalse(json.contains("costUsd"), "无价格不出货币数: " + json);
    }

    @Test
    @DisplayName("成本对照 JSON：费用为定点十进制，无科学计数法")
    void costJson_plainDecimal() {
        saveDeclaredRecord("b1", "s-old", 1000L, "t1", "invocation:a:h1", "A", "答A", "gpt-4o", 1000, 500);
        saveDeclaredRecord("n1", "s-new", 9000L, "t1", "invocation:a:h1", "A", "答A", "gpt-4o", 1000, 500);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner ruleRunner = runnerWithRules(new InvocationRulesConfig(), true, target);

        int exit = ruleRunner.run("t1", false, false, null, null, null, null);

        assertEquals(0, exit);
        String json = target.toString().trim();
        assertTrue(json.contains("\"costUsd\":0.0075"), "定点形态，不得出现 E 计数: " + json);
        assertFalse(json.contains("E-") || json.contains("E+"), json);
    }

    @Test
    @DisplayName("干跑预算：调用数预算的截断点在计划中如实模拟（skipped，零调用）")
    void taskDryRun_budgetCutSimulated() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s-old", 2000L, null, "invocation:y:h2", "答B");

        int exit = runner.run("查订单", false, false, "new prompt", null, 1, null, true);

        assertEquals(0, exit);
        assertEquals(0, stubClient.callCount, "干跑零真实调用");
        String plan = output.toString();
        assertTrue(plan.contains("skipped（预算耗尽 budget_exhausted）"), plan);
        assertTrue(plan.contains("真重放 1 步 / 继承 0 步 / 预算截断 1 步"), plan);
        assertTrue(plan.contains("预估 1 次 API 调用"), "成本预估只算截断后仍真重放的步: " + plan);
    }

    @Test
    @DisplayName("干跑 JSON：多链各一行携带 chainIndex/chainCount；token 截断点不冒充可模拟")
    void taskDryRun_jsonMultiChain_indexed() {
        saveRecord("a1", "s1", 1000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b1", "s2", 9000L, "查订单", "invocation:x:h1", "答A");
        saveRecord("b2", "s2", 9500L, null, "invocation:y:h2", "答B");
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        TaskReplayRunner jsonRunner = runnerWithRules(new InvocationRulesConfig(), true, target);

        int exit = jsonRunner.run("查订单", false, false, "new prompt", null, null, 5000, true);

        assertEquals(0, exit);
        String[] lines = target.toString().trim().split("\n");
        assertEquals(2, lines.length, "每链一行: " + target);
        assertTrue(lines[0].contains("\"chainIndex\":0") && lines[0].contains("\"chainCount\":2"), lines[0]);
        assertTrue(lines[1].contains("\"chainIndex\":1") && lines[1].contains("\"chainCount\":2"), lines[1]);
        assertTrue(lines[0].contains("\"skipped\":0"), lines[0]);
    }

    @Test
    @DisplayName("任务选择器：零宽等不可见 Unicode 字符可见化回显")
    void taskSelector_zeroWidthVisible() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");

        int exit = runner.run("查\u200B订单", false, false, null, null, null, null);

        assertEquals(2, exit);
        assertTrue(output.toString().contains("<U+200B>"), "零宽空格必须可见化，否则匹配失败无从解释: " + output);
    }

    private static String report(ByteArrayOutputStream output) {
        return output.toString();
    }

    /**
     * 脚本化 LLM 桩（与 ReplayFlowTest 同款语义：文本应答 / 工具应答二态）
     */
    static class StubLlmClient implements LlmClient {
        String responseText;
        boolean toolCallResponse;
        String toolCallName = "queryOrder";
        int callCount;
        boolean throwApiError;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            if (throwApiError) {
                throw new LlmApiException("endpoint down");
            }
            LlmResponse response = new LlmResponse();
            response.setInputTokens(10);
            response.setOutputTokens(5);
            if (toolCallResponse) {
                ToolCallResult tc = new ToolCallResult();
                tc.setToolCallId("call-1");
                tc.setToolName(toolCallName);
                tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
                response.setToolCalls(Collections.singletonList(tc));
            } else {
                response.setContent(responseText);
                response.setToolCalls(Collections.emptyList());
            }
            return response;
        }

        @Override
        public String name() {
            return "stub-model";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
