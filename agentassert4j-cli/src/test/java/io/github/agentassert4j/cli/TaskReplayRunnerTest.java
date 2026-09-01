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

        int exit = runner.run("查订单", false, false, "新提示词内容", "不相关的旧提示词文本", null, null);

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
        String hitKey = "invocation:verdict:" + io.github.agentassert4j.util.HashUtil.sha256(oldPrompt);
        saveRecord("b1", "s-old", 1000L, "查订单", hitKey, "verdict", io.github.agentassert4j.util.HashUtil.sha256(oldPrompt), "答A", "dev-model");
        saveRecord("b2", "s-old", 2000L, null, "invocation:other:h-other", "other", io.github.agentassert4j.util.HashUtil.sha256("别的模板"), "答B", "dev-model");
        stubClient.responseText = "答A";

        int exit = runner.run("查订单", false, false, "new prompt", oldPrompt, null, null);

        assertEquals(0, exit);
        assertEquals(1, stubClient.callCount, "只有旧提示词命中的记录真重放");
        String report = output.toString();
        assertTrue(report.contains("继承 PASS"), "异模板步骤必须标注继承: " + report);
    }

    @Test
    @DisplayName("退出码：LLM 全部执行失败无判定 → 2（证据缺口不冒充绿）")
    void frozenReplay_allErrors_exit2() {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:x:h1", "答A");
        stubClient.throwApiError = true;

        int exit = runner.run("查订单", false, false, "new prompt", null, null, null);

        assertEquals(2, exit);
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
