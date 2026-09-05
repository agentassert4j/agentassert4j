package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.util.ArgTypeUtil;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 回归测试执行器 — 用新 Prompt + 历史输入重放 LLM 调用，对比决策行为。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class RegressionTestExecutor {

    private static final Logger LOG = Logger.getLogger(RegressionTestExecutor.class.getName());

    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final BaselineManager baselineManager;
    private final InvocationRulesConfig rules;

    /**
     * 完整构造器。
     *
     * @param llmClient       LLM 客户端
     * @param comparator      确定性对比器
     * @param baselineManager 基线管理器：对比结果非 PASS 时把候选指纹落库供 approve/reject 裁决（传 null 跳过）
     * @param rules           声明式规则配置：维度 3-4（内容规则/约束行为）按声明标签查找注入（传 null 跳过规则判定）
     */
    public RegressionTestExecutor(LlmClient llmClient, DeterministicComparator comparator, BaselineManager baselineManager, InvocationRulesConfig rules) {
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.baselineManager = baselineManager;
        this.rules = rules;
    }

    /**
     * 执行单次回归测试。
     *
     * @param baseline        历史交互（基线）
     * @param newSystemPrompt 新 System Prompt
     * @param userInput       本次调用的用户输入；null = 原样复用基线记录的历史输入
     *                        （重放语义）；非 null 覆盖末位 user 帧，实现「新输入的首次调用」
     * @param config          执行配置
     * @return 回归测试结果
     */
    public RegressionTestResult execute(InteractionRecord baseline, String newSystemPrompt, String userInput, TestExecutionConfig config) {

        // dryRun 模式：不调 LLM
        if (config.isDryRun()) {
            RegressionTestResult result = new RegressionTestResult();
            result.setBaselineRecordId(baseline.getRecordId());
            result.setInvocationId(baseline.getInvocationId());
            result.setStatus(TestResultStatus.SKIP);
            return result;
        }

        // 编排观察记录（工具回调层旁路产出：完整工具编排 + 每轮录制结果）走链式半重放——
        // 单发重放拼不出「第 2 轮输入含第 1 轮工具结果」的当时上下文，且会把整段
        // 编排一次性重新决策，工具维必然假阳性
        if (isChainReplayable(baseline)) {
            return executeChained(baseline, newSystemPrompt, userInput, config);
        }

        // 1. 构建重放请求
        LlmRequest replayRequest = buildReplayRequest(baseline, newSystemPrompt, userInput, config);

        // 2. 调用 LLM
        LlmResponse response;
        try {
            response = llmClient.chat(replayRequest, config.getTimeoutMs());
        } catch (LlmTimeoutException e) {
            return RegressionTestResult.timeout(baseline.getRecordId());
        } catch (LlmApiException e) {
            return RegressionTestResult.apiError(baseline.getRecordId(), e.getMessage());
        } catch (RuntimeException e) {
            // 客户端实现的编程错误（NPE/非法参数等）同样转为单条 ERROR——
            // 批量回归不允许一条记录的意外异常中断整体
            return RegressionTestResult.error(baseline.getRecordId(), "LLM client threw uncaught exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 3-5. LLM 调用成功后的处理（构建当前记录/提取指纹/对比与候选落库/结果封装）。
        //    任何处理失败转为 ERROR 结果，不向批量调用方逃逸——批量回归不允许单条记录中断整体
        try {
            InteractionRecord current = buildCurrentRecord(baseline, response, newSystemPrompt, userInput);

            DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline, rules, baseline.getInvocationId());
            DeterministicFingerprint currentFp = FingerprintExtractor.extract(current, rules, current.getInvocationId());

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
            result.setInvocationId(baseline.getInvocationId());
            result.setComparison(comparison);
            result.setCandidateFingerprint(currentFp);
            // served 模型与 token 消耗随结果上抛：前者供精确模型身份比对，
            // 后者供调用方做费用与预算核算；缓存/思考
            // token 可空（供应商未返回时保持 null，未知不得记 0）
            result.setServedModel(response.getServedModel());
            result.setInputTokens(response.getInputTokens());
            result.setOutputTokens(response.getOutputTokens());
            result.setCacheReadTokens(response.getCacheReadTokens());
            result.setCacheWriteTokens(response.getCacheWriteTokens());
            result.setReasoningTokens(response.getReasoningTokens());
            // 候选原文只在重放现场存活（recordCandidate 只持久化指纹），
            // 报告侧的文本差异证据依赖此处透传
            result.setReplayOutput(response.getContent());
            return result;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Post-processing failed for " + baseline.getRecordId(), e);
            return RegressionTestResult.error(baseline.getRecordId(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 构建重放请求 — 新 Prompt 与场景输入为被测变量，多轮上下文/工具定义为控制变量。
     */
    LlmRequest buildReplayRequest(InteractionRecord baseline, String newSystemPrompt, String userInput, TestExecutionConfig config) {
        LlmRequest request = new LlmRequest();

        // 替换 System Prompt
        request.setSystemPrompt(newSystemPrompt);

        // 用户输入：null = 重放语义，原样复用历史输入；传入新输入覆盖
        // 末位 user 帧（「新输入的首次调用」语义落在这里，上下文与工具原样保留）
        request.setUserInput(userInput != null ? userInput : baseline.getUserInput());

        // 多模态原样复用
        request.setMultimodalInput(baseline.isMultimodalInput());

        // 多轮对话：注入前序轮次（完整复制——tool 角色的 toolCallId/toolName
        // 是重放请求与原对话对齐的关联键，丢弃会导致服务端拒绝整个请求）。
        // 判据只看前序轮次是否非空：无 user 消息收尾的会话（典型：tool 结果轮）
        // turnIndex 为 0，但历史轮次同样必须参与重放
        if (baseline.getPreviousTurns() != null && !baseline.getPreviousTurns().isEmpty()) {
            for (TurnContext turn : baseline.getPreviousTurns()) {
                // system 帧不注入：系统提示属模板域由 systemPrompt 承载，
                // 重复入列会产生第二条 system 消息（渲染侧已有同款跳过，此处补纵深）
                if ("system".equalsIgnoreCase(turn.getRole())) {
                    continue;
                }
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
    InteractionRecord buildCurrentRecord(InteractionRecord baseline, LlmResponse response, String newPrompt, String userInput) {
        InteractionRecord current = new InteractionRecord();
        current.setRecordId(UUID.randomUUID().toString());
        current.setTimestamp(System.currentTimeMillis());
        current.setInvocationId(baseline.getInvocationId());
        current.setTemplateHash(HashUtil.sha256(newPrompt));
        current.setUserInput(userInput != null ? userInput : baseline.getUserInput());
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
        // 模型身份列（provider/endpoint/apiProtocol 等）捕获侧尚未填充，
        // 算法层不持有供应商方言知识，响应侧遥测在此就地落位
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
        copy.setToolArguments(turn.getToolArguments());
        return copy;
    }

    private ToolCall toolCallResultToToolCall(ToolCallResult tc) {
        ToolCall call = new ToolCall();
        call.setToolName(tc.getToolName());
        call.setToolCallId(tc.getToolCallId());
        call.setArguments(tc.getArguments());
        // 捕获路径的 argTypes 由接入层填写；重放路径的核心自身就是当前记录的
        // 组装方，必须按同一词表补齐参数类型，否则与基线指纹比对必失配
        call.setArgTypes(ArgTypeUtil.derive(tc.getArguments()));
        call.setSuccess(true); // 重放不执行工具，默认成功
        return call;
    }


    /**
     * 链式半重放资格：基线带完整工具编排且每个调用都有录制结果（结果道具齐备）。
     * 任一结果缺失（录制时工具执行失败等）则无法合成「当时输入」，退回单发重放。
     */
    static boolean isChainReplayable(InteractionRecord baseline) {
        if (!baseline.isHasToolCalls() || baseline.getToolCalls() == null || baseline.getToolCalls().isEmpty()) {
            return false;
        }
        for (ToolCall call : baseline.getToolCalls()) {
            if (call.getResult() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 链式半重放（编排观察记录的专用重放契约）：拿基线录制的旧结果当道具，
     * 逐轮重建「当时输入」逐轮比对模型决策。每轮把响应的 tool_calls 与基线编排的
     * 下一个片段逐项比对（工具名 + 参数解析后严格相等）；全部轮次匹配则末轮四维
     * 比对收口；某轮分歧则精确到轮的定位后立即停止（分歧即停：旧结果配新决策是
     * 虚构演进，链条停在真相失效处）。合成帧的 tool_call_id 用合成关联值，结果帧
     * 携带基线录制内容（内容无损）。分歧轮次不做候选落库——编排未走完，指纹不完整。
     */
    private RegressionTestResult executeChained(InteractionRecord baseline, String newSystemPrompt, String userInput, TestExecutionConfig config) {
        try {
            String effectiveInput = userInput != null ? userInput : baseline.getUserInput();
            List<ToolCall> orchestration = baseline.getToolCalls();
            List<TurnContext> synthesized = new ArrayList<>();
            int cursor = 0;
            int round = 0;
            long totalInput = 0;
            long totalOutput = 0;
            Integer cacheRead = null;
            Integer cacheWrite = null;
            Integer reasoning = null;
            String servedModel = null;
            LlmResponse response = null;

            // 决策轮：每轮响应的 tool_calls 必须与基线编排的下一片段逐项一致
            while (cursor < orchestration.size()) {
                round++;
                response = chainChat(baseline, newSystemPrompt, round == 1 ? effectiveInput : null, synthesized, config);
                totalInput += response.getInputTokens();
                totalOutput += response.getOutputTokens();
                if (response.getServedModel() != null) {
                    servedModel = response.getServedModel();
                }
                cacheRead = sumNullable(cacheRead, response.getCacheReadTokens());
                cacheWrite = sumNullable(cacheWrite, response.getCacheWriteTokens());
                reasoning = sumNullable(reasoning, response.getReasoningTokens());
                List<ToolCallResult> decisions = response.getToolCalls() == null ? Collections.<ToolCallResult>emptyList() : response.getToolCalls();
                if (decisions.isEmpty() || cursor + decisions.size() > orchestration.size() || !matchesSlice(orchestration, cursor, decisions)) {
                    return chainDivergence(baseline, round, cursor, decisions);
                }
                // 决策与基线一致：合成 assistant+tool 帧——结果用基线录制值（控制变量）
                for (ToolCallResult decision : decisions) {
                    String syntheticId = "aa-chain-" + round + "-" + cursor;
                    cursor++;
                    ToolCall recorded = orchestration.get(cursor - 1);
                    synthesized.add(assistantToolCallFrame(syntheticId, decision));
                    synthesized.add(toolResultFrame(syntheticId, recorded.getResult()));
                }
            }

            // 末轮收口：编排全部复现后模型给出最终答复——四维比对对象
            round++;
            response = chainChat(baseline, newSystemPrompt, null, synthesized, config);
            totalInput += response.getInputTokens();
            totalOutput += response.getOutputTokens();
            if (response.getServedModel() != null) {
                servedModel = response.getServedModel();
            }
            cacheRead = sumNullable(cacheRead, response.getCacheReadTokens());
            cacheWrite = sumNullable(cacheWrite, response.getCacheWriteTokens());
            reasoning = sumNullable(reasoning, response.getReasoningTokens());
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                // 编排比基线多出工具调用——行为变化，分歧即停
                return chainDivergence(baseline, round, cursor, response.getToolCalls());
            }

            InteractionRecord current = new InteractionRecord();
            current.setRecordId(UUID.randomUUID().toString());
            current.setTimestamp(System.currentTimeMillis());
            current.setInvocationId(baseline.getInvocationId());
            current.setTemplateHash(HashUtil.sha256(newSystemPrompt));
            current.setUserInput(effectiveInput != null ? effectiveInput : baseline.getUserInput());
            current.setTurnIndex(baseline.getTurnIndex());
            current.setSessionId(baseline.getSessionId());
            // 决策逐轮匹配成立：当前编排与基线一致（名称/参数/成功标记同值比对）
            List<ToolCall> matched = new ArrayList<>();
            for (ToolCall call : orchestration) {
                ToolCall copy = new ToolCall();
                copy.setToolName(call.getToolName());
                copy.setArguments(call.getArguments());
                copy.setArgTypes(call.getArgTypes());
                copy.setSuccess(call.isSuccess());
                matched.add(copy);
            }
            current.setToolCalls(matched);
            current.setHasToolCalls(true);
            current.setModelResponse(response.getContent());
            current.setInputTokens(response.getInputTokens());
            current.setOutputTokens(response.getOutputTokens());

            DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline, rules, baseline.getInvocationId());
            DeterministicFingerprint currentFp = FingerprintExtractor.extract(current, rules, current.getInvocationId());
            ComparisonResult comparison = comparator.compare(baselineFp, currentFp, response.getContent());

            RegressionTestResult result = new RegressionTestResult();
            result.setBaselineRecordId(baseline.getRecordId());
            result.setInvocationId(baseline.getInvocationId());
            result.setComparison(comparison);
            result.setCandidateFingerprint(currentFp);
            if (baselineManager != null && comparison.getVerdict() != Verdict.PASS) {
                try {
                    baselineManager.recordCandidate(baseline, currentFp);
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "Failed to persist candidate fingerprint for " + baseline.getRecordId(), e);
                }
            }
            result.setServedModel(servedModel);
            result.setInputTokens((int) totalInput);
            result.setOutputTokens((int) totalOutput);
            result.setCacheReadTokens(cacheRead);
            result.setCacheWriteTokens(cacheWrite);
            result.setReasoningTokens(reasoning);
            result.setReplayOutput(response.getContent());
            return result;
        } catch (LlmTimeoutException e) {
            return RegressionTestResult.timeout(baseline.getRecordId());
        } catch (LlmApiException e) {
            return RegressionTestResult.apiError(baseline.getRecordId(), e.getMessage());
        } catch (RuntimeException e) {
            // 防御性：链路中的意外异常（LLM 客户端或末轮指纹/对比的后处理）不向批量调用方逃逸
            return RegressionTestResult.error(baseline.getRecordId(), "Chained half-replay threw uncaught exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 链式单轮请求：与单发重放共用同一条装配语义（系统提示/历史轮/工具定义为
     * 控制变量），差异在两点——用户输入只在第 1 轮携带（后续轮从工具结果续起，
     * 重建「当时输入」不重复提问），以及合成帧追加在历史轮之后。
     */
    private LlmResponse chainChat(InteractionRecord baseline, String newSystemPrompt, String userInput, List<TurnContext> synthesized, TestExecutionConfig config) throws LlmTimeoutException, LlmApiException {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(newSystemPrompt);
        request.setUserInput(userInput);
        request.setMultimodalInput(baseline.isMultimodalInput());
        if (baseline.getPreviousTurns() != null) {
            for (TurnContext turn : baseline.getPreviousTurns()) {
                // system 帧不注入：系统提示属模板域由 systemPrompt 承载
                if ("system".equalsIgnoreCase(turn.getRole())) {
                    continue;
                }
                request.addTurn(copyTurn(turn));
            }
        }
        for (TurnContext turn : synthesized) {
            request.addTurn(turn);
        }
        request.setTemperature(config.getTemperature());
        if (config.getModel() != null) {
            request.setModel(config.getModel());
        }
        List<String> toolDefinitions = splitToolDefinitions(baseline.getToolsDefinition());
        if (!toolDefinitions.isEmpty()) {
            request.setToolDefinitions(toolDefinitions);
        }
        return llmClient.chat(request, config.getTimeoutMs());
    }

    /**
     * 决策片段比对：工具名严格相等 + 参数解析后 Map 严格相等（null 视同空对象）。
     * tool_call id 是关联键不是行为，不参与比对。
     */
    private static boolean matchesSlice(List<ToolCall> orchestration, int cursor, List<ToolCallResult> decisions) {
        for (int i = 0; i < decisions.size(); i++) {
            ToolCall expected = orchestration.get(cursor + i);
            ToolCallResult actual = decisions.get(i);
            if (!Objects.equals(expected.getToolName(), actual.getToolName())) {
                return false;
            }
            Map<String, Object> expectedArgs = expected.getArguments() == null ? Collections.<String, Object>emptyMap() : expected.getArguments();
            Map<String, Object> actualArgs = actual.getArguments() == null ? Collections.<String, Object>emptyMap() : actual.getArguments();
            if (!expectedArgs.equals(actualArgs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分歧结果：verdict=CHANGED + 精确到轮的定位摘要。工具/参数两维标记失配，
     * 其余维度未参与评估置为匹配（分歧发生在编排决策，不产生文本/结构证据）。
     */
    private RegressionTestResult chainDivergence(InteractionRecord baseline, int round, int cursor, List<ToolCallResult> decisions) {
        String expected;
        if (cursor < baseline.getToolCalls().size()) {
            ToolCall next = baseline.getToolCalls().get(cursor);
            expected = next.getToolName() + "(" + next.getArguments() + ")";
        } else {
            expected = "orchestration ended (no more tool calls)";
        }
        StringBuilder actual = new StringBuilder();
        for (ToolCallResult decision : decisions) {
            if (actual.length() > 0) {
                actual.append("; ");
            }
            actual.append(decision.getToolName()).append("(").append(decision.getArguments()).append(")");
        }
        if (actual.length() == 0) {
            actual.append("no tool call issued");
        }
        ComparisonResult comparison = new ComparisonResult();
        comparison.setVerdict(Verdict.CHANGED);
        comparison.setScore(0.0);
        comparison.setToolCallMatch(false);
        comparison.setParamTypeMatch(false);
        comparison.setStructureMatch(true);
        comparison.setKeywordMatch(true);
        comparison.setRegexMatch(true);
        comparison.setBehaviorMatch(true);
        comparison.setAddedFields(Collections.<String>emptySet());
        comparison.setRemovedFields(Collections.<String>emptySet());
        comparison.setSummary("Tool decision diverged at round " + round + " (chained half-replay stopped at the divergence); baseline: " + expected + ", actual: " + actual);
        RegressionTestResult result = new RegressionTestResult();
        result.setBaselineRecordId(baseline.getRecordId());
        result.setInvocationId(baseline.getInvocationId());
        result.setComparison(comparison);
        return result;
    }

    private static TurnContext assistantToolCallFrame(String syntheticId, ToolCallResult decision) {
        TurnContext turn = new TurnContext("assistant", "");
        turn.setToolCallId(syntheticId);
        turn.setToolName(decision.getToolName());
        turn.setToolArguments(decision.getArguments() != null && !decision.getArguments().isEmpty() ? RecursiveJsonParser.serialize(decision.getArguments()) : "{}");
        return turn;
    }

    private static TurnContext toolResultFrame(String syntheticId, String result) {
        TurnContext turn = new TurnContext("tool", result);
        turn.setToolCallId(syntheticId);
        return turn;
    }

    private static Integer sumNullable(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + second;
    }

}
