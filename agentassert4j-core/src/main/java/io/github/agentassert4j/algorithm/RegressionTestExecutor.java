package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.util.HashUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 回归测试执行器 — 用新 Prompt + 历史输入重放 LLM 调用，对比决策行为。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>buildReplayRequest — 构建重放请求</li>
 *   <li>llmClient.chat — 调用 LLM</li>
 *   <li>buildCurrentRecord — 从响应构建当前交互记录</li>
 *   <li>FingerprintExtractor.extract — 提取指纹</li>
 *   <li>DeterministicComparator.compare — 与基线对比</li>
 * </ol>
 */
public class RegressionTestExecutor {

    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final BaselineManager baselineManager;

    /**
     * 完整构造器。
     *
     * @param llmClient       LLM 客户端
     * @param comparator      确定性对比器
     * @param baselineManager 基线管理器（可选，传 null 跳过基线操作）
     */
    public RegressionTestExecutor(LlmClient llmClient, DeterministicComparator comparator, BaselineManager baselineManager) {
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.baselineManager = baselineManager;
    }

    /**
     * 执行单次回归测试。
     *
     * @param baseline        历史交互（基线）
     * @param newSystemPrompt 新 System Prompt
     * @param config          执行配置
     * @return 回归测试结果
     */
    public RegressionTestResult execute(InteractionRecord baseline, String newSystemPrompt, TestExecutionConfig config) {

        // dryRun 模式：不调 LLM
        if (config.isDryRun()) {
            RegressionTestResult result = new RegressionTestResult();
            result.setBaselineRecordId(baseline.getRecordId());
            result.setSkillId(baseline.getSkillId());
            result.setStatus(TestResultStatus.SKIP);
            return result;
        }

        // 1. 构建重放请求
        LlmRequest replayRequest = buildReplayRequest(baseline, newSystemPrompt, config);

        // 2. 调用 LLM
        LlmResponse response;
        try {
            response = llmClient.chat(replayRequest, config.getTimeoutMs());
        } catch (LlmTimeoutException e) {
            return RegressionTestResult.timeout(baseline.getRecordId());
        } catch (LlmApiException e) {
            return RegressionTestResult.apiError(baseline.getRecordId(), e.getMessage());
        }

        // 3. 构建当前交互记录
        InteractionRecord current = buildCurrentRecord(baseline, response, newSystemPrompt);

        // 4. 提取指纹（FingerprintExtractor 是静态工具类）
        DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline);
        DeterministicFingerprint currentFp = FingerprintExtractor.extract(current);

        // 5. 对比
        ComparisonResult comparison = comparator.compare(baselineFp, currentFp, response.getContent());

        // 6. 封装结果
        RegressionTestResult result = new RegressionTestResult();
        result.setBaselineRecordId(baseline.getRecordId());
        result.setSkillId(baseline.getSkillId());
        result.setComparison(comparison);
        result.setCandidateFingerprint(currentFp);
        return result;
    }

    /**
     * 构建重放请求 — 用历史输入 + 新 Prompt。
     *
     * <p>关键设计：</p>
     * <ul>
     *   <li>替换 System Prompt（这是要测的变量）</li>
     *   <li>复用历史 User Input（这是控制变量）</li>
     *   <li>注入 previousTurns（多轮对话上下文）</li>
     *   <li>多模态原样复用</li>
     * </ul>
     */
    LlmRequest buildReplayRequest(InteractionRecord baseline, String newSystemPrompt, TestExecutionConfig config) {
        LlmRequest request = new LlmRequest();

        // 替换 System Prompt
        request.setSystemPrompt(newSystemPrompt);

        // 复用历史用户输入
        request.setUserInput(baseline.getUserInput());

        // 多模态原样复用
        request.setMultimodalInput(baseline.isMultimodalInput());

        // 多轮对话：注入前序轮次
        if (baseline.getTurnIndex() > 0 && baseline.getPreviousTurns() != null) {
            for (TurnContext turn : baseline.getPreviousTurns()) {
                request.addTurn(turn.getRole(), turn.getContent());
            }
        }

        // 温度和模型
        request.setTemperature(config.getTemperature());
        if (config.getModel() != null) {
            request.setModel(config.getModel());
        }

        return request;
    }

    /**
     * 从 LLM 响应构建当前交互记录（不持久化，仅用于指纹提取和对比）。
     */
    InteractionRecord buildCurrentRecord(InteractionRecord baseline, LlmResponse response, String newPrompt) {
        InteractionRecord current = new InteractionRecord();
        current.setRecordId(UUID.randomUUID().toString());
        current.setTimestamp(System.currentTimeMillis());
        current.setTemplateHash(HashUtil.sha256(newPrompt));
        current.setUserInput(baseline.getUserInput());
        current.setTurnIndex(baseline.getTurnIndex());
        current.setSessionId(baseline.getSessionId());

        // 从 LLM 响应提取工具调用
        List<ToolCall> currentToolCalls = new ArrayList<>();
        if (response.getToolCalls() != null) {
            currentToolCalls = response.getToolCalls().stream().map(this::toolCallResultToToolCall).collect(Collectors.toList());
        }
        current.setToolCalls(currentToolCalls);
        current.setHasToolCalls(!currentToolCalls.isEmpty());

        // 文本响应
        current.setModelResponse(response.getContent());
        current.setInputTokens(response.getInputTokens());
        current.setOutputTokens(response.getOutputTokens());

        // 调用时刻遥测——只在此刻可知，事后无法重建。
        // 模型身份（provider/endpoint 等）由持有客户端配置的持久化管道回填，
        // 算法层不持有供应商方言知识，归一在捕获层完成
        current.setServedModel(response.getServedModel());
        current.setFinishReason(response.getFinishReason());
        current.setUsageRaw(response.getUsageRaw());
        current.setCacheReadTokens(response.getCacheReadTokens());
        current.setCacheWriteTokens(response.getCacheWriteTokens());
        current.setReasoningTokens(response.getReasoningTokens());
        current.setLatencyMs(response.getLatencyMs());

        // 多模态复用
        current.setMultimodalInput(baseline.isMultimodalInput());
        current.setMultimodalContent(baseline.getMultimodalContent());

        return current;
    }

    private ToolCall toolCallResultToToolCall(ToolCallResult tc) {
        ToolCall call = new ToolCall();
        call.setToolName(tc.getToolName());
        call.setToolCallId(tc.getToolCallId());
        call.setArguments(tc.getArguments());
        call.setSuccess(true); // 重放不执行工具，默认成功
        return call;
    }
}
