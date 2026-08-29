package io.github.agentassert4j.model;

/**
 * 场景声明 — 场景层的定义实体（对应 scenarios 表行）。
 *
 * <p>一个场景描述「用一份新输入对某个已录制行为发起 N 轮真实调用，并断言响应」：
 * inputSpec 持有输入声明（userInput 与结构化变量）的 JSON 文本，assertions 持有
 * 断言集声明的 JSON 文本（复用规则词表，不引入 LLM-as-judge）。两列以 JSON 文本
 * 存储而非展开成列——声明结构尚在 MVP 演进期，展开成列是单向门，等口径稳定再定。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
public class ScenarioDefinition {

    private String scenarioId;
    private String name;
    private String templateId;
    private String inputSpec;
    private String assertions;
    private int version;
    private String metadata;
    private long createdAt;
    private long updatedAt;

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getInputSpec() {
        return inputSpec;
    }

    public void setInputSpec(String inputSpec) {
        this.inputSpec = inputSpec;
    }

    public String getAssertions() {
        return assertions;
    }

    public void setAssertions(String assertions) {
        this.assertions = assertions;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
