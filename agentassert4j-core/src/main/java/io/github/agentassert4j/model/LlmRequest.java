package io.github.agentassert4j.model;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 请求 — 回归测试重放时构建。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class LlmRequest {

    private String systemPrompt;
    private String userInput;
    private boolean multimodalInput;
    private List<TurnContext> previousTurns;
    private String model;
    /**
     * null 表示请求体不携带该成员（推理模型方言）
     */
    private Double temperature;

    /**
     * 工具定义列表 — OpenAI tools 格式。
     *
     * <p>每个元素是一个 JSON 字符串，格式如下：</p>
     * <pre>{"type":"function","function":{"name":"...","description":"...","parameters":{...}}}</pre>
     *
     * <p>设置此字段后，LLM 可能返回 tool_calls 响应。</p>
     */
    private List<String> toolDefinitions;
    /**
     * 本次调用的 wire 方言提示（LlmWireProtocol 的线上值，如 "anthropic-messages"）。
     * 重放装配侧携带基线记录的 apiProtocol，供协议路由客户端按记录原方言发射；
     * null = 无提示，由客户端按配置或缺省方言解析。值是提示不是指令——
     * 显式配置的 llm.protocol 优先级更高（跨协议重放是显式用户意图）。
     */
    private String wireProtocol;

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public boolean isMultimodalInput() {
        return multimodalInput;
    }

    public void setMultimodalInput(boolean multimodalInput) {
        this.multimodalInput = multimodalInput;
    }

    public List<TurnContext> getPreviousTurns() {
        return previousTurns;
    }

    public void setPreviousTurns(List<TurnContext> previousTurns) {
        this.previousTurns = previousTurns;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    /**
     * 追加一个完整的前序轮次（保留 toolCallId/toolName——tool 角色消息的关联键）。
     */
    public void addTurn(TurnContext turn) {
        if (turn == null) return;
        if (previousTurns == null) previousTurns = new ArrayList<>();
        previousTurns.add(turn);
    }

    public List<String> getToolDefinitions() {
        return toolDefinitions;
    }

    public void setToolDefinitions(List<String> toolDefinitions) {
        this.toolDefinitions = toolDefinitions;
    }

    public String getWireProtocol() {
        return wireProtocol;
    }

    public void setWireProtocol(String wireProtocol) {
        this.wireProtocol = wireProtocol;
    }
}
