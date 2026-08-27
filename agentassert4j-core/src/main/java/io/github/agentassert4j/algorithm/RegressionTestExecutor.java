package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class RegressionTestExecutor {

    private static final Logger LOG = Logger.getLogger(RegressionTestExecutor.class.getName());

    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final BaselineManager baselineManager;
    private final SkillRulesConfig rules;

    /**
     * 完整构造器。
     *
     * @param llmClient       LLM 客户端
     * @param comparator      确定性对比器
     * @param baselineManager 基线管理器：对比结果非 PASS 时把候选指纹落库供 approve/reject 裁决（传 null 跳过）
     * @param rules           声明式规则配置：维度 3-4（内容规则/约束行为）按 skillId 查找注入（传 null 跳过规则判定）
     */
    public RegressionTestExecutor(LlmClient llmClient, DeterministicComparator comparator, BaselineManager baselineManager, SkillRulesConfig rules) {
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.baselineManager = baselineManager;
        this.rules = rules;
    }

    public RegressionTestExecutor(LlmClient llmClient, DeterministicComparator comparator, BaselineManager baselineManager) {
        this(llmClient, comparator, baselineManager, null);
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

        // 3-7. LLM 调用成功后的处理（指纹提取/对比/候选落库/结果封装）。
        //    任何处理失败转为 ERROR 结果，不向批量调用方逃逸——批量回归不允许单条记录中断整体
        try {
            InteractionRecord current = buildCurrentRecord(baseline, response, newSystemPrompt);

            DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline, rules, baseline.getSkillId());
            DeterministicFingerprint currentFp = FingerprintExtractor.extract(current, rules, current.getSkillId());

            ComparisonResult comparison = comparator.compare(baselineFp, currentFp, response.getContent());

            // 候选落库：与基线存在差异的新指纹进入待裁决状态（PASS 指纹相同，无可裁决对象）。
            // 落库失败不中断批量回归——报告仍完整，仅 approve 不可用，SEVERE 留痕
            if (baselineManager != null && comparison.getVerdict() != Verdict.PASS) {
                try {
                    baselineManager.recordCandidate(baseline, currentFp);
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Failed to persist candidate fingerprint for " + baseline.getRecordId(), e);
                }
            }

            RegressionTestResult result = new RegressionTestResult();
            result.setBaselineRecordId(baseline.getRecordId());
            result.setSkillId(baseline.getSkillId());
            result.setComparison(comparison);
            result.setCandidateFingerprint(currentFp);
            return result;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Post-processing failed for " + baseline.getRecordId(), e);
            return RegressionTestResult.error(baseline.getRecordId(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 构建重放请求 — 用历史输入 + 新 Prompt。
     *
     * <p>关键设计：</p>
     * <ul>
     *   <li>替换 System Prompt（这是要测的变量）</li>
     *   <li>复用历史 User Input（这是控制变量）</li>
     *   <li>注入 previousTurns（多轮对话上下文）</li>
     *   <li>复用工具定义（不带工具则模型无法发起调用，工具维必然差异）</li>
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

        // 多轮对话：注入前序轮次（完整复制——tool 角色的 toolCallId/toolName
        // 是重放请求与原对话对齐的关联键，丢弃会导致服务端拒绝整个请求）
        if (baseline.getTurnIndex() > 0 && baseline.getPreviousTurns() != null) {
            for (TurnContext turn : baseline.getPreviousTurns()) {
                request.addTurn(copyTurn(turn));
            }
        }

        // 温度和模型
        request.setTemperature(config.getTemperature());
        if (config.getModel() != null) {
            request.setModel(config.getModel());
        }

        // 工具定义原样复用：重放不带工具，模型无法发起工具调用，
        // 工具维指纹必然差异（假阳性回归）。损坏定义跳过不中断
        List<String> toolDefinitions = splitToolDefinitions(baseline.getToolsDefinition());
        if (!toolDefinitions.isEmpty()) {
            request.setToolDefinitions(toolDefinitions);
        }

        return request;
    }

    /**
     * 录制的工具定义（JSON 数组原文，或单个工具对象）拆成逐工具的 JSON 字符串。
     * 解析失败返回空列表——脱敏等破坏原文时宁可不带工具也不构造非法请求。
     */
    private static List<String> splitToolDefinitions(String toolsJson) {
        List<String> definitions = new ArrayList<>();
        if (toolsJson == null || toolsJson.isEmpty()) {
            return definitions;
        }
        Object parsed = RecursiveJsonParser.parse(toolsJson);
        if (parsed instanceof List) {
            for (Object item : (List<?>) parsed) {
                if (item != null) {
                    definitions.add(RecursiveJsonParser.serialize(item));
                }
            }
        } else if (parsed instanceof Map) {
            definitions.add(RecursiveJsonParser.serialize(parsed));
        }
        return definitions;
    }

    /**
     * 从 LLM 响应构建当前交互记录（不持久化，仅用于指纹提取和对比）。
     */
    InteractionRecord buildCurrentRecord(InteractionRecord baseline, LlmResponse response, String newPrompt) {
        InteractionRecord current = new InteractionRecord();
        current.setRecordId(UUID.randomUUID().toString());
        current.setTimestamp(System.currentTimeMillis());
        current.setSkillId(baseline.getSkillId());
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

    private static TurnContext copyTurn(TurnContext turn) {
        TurnContext copy = new TurnContext(turn.getRole(), turn.getContent());
        copy.setToolCallId(turn.getToolCallId());
        copy.setToolName(turn.getToolName());
        return copy;
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
