package io.github.agentassert4j.springai1;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 录制上下文 — 业务线程向旁路录制声明会话归属与业务标注。
 *
 * <p>Spring AI 的 ChatModel 层不携带会话概念（会话在 ChatClient/ChatMemory 层），
 * SDK 用线程绑定的临时作用域补齐：</p>
 * <pre>{@code
 * try (RecordingContext ctx = RecordingContext.start("session-1")
 *         .withInvocationId("order-refund")) {
 *     chatClient.prompt()...call();
 * }
 * }</pre>
 * <p>作用域可嵌套，关闭时恢复外层。未声明时录制管道按既有退化策略处理
 * （sessionId 缺失的记录各自成独立会话）。仅在声明线程内生效——Reactor/异步
 * 线程上的调用取不到上下文，需要标注的流式调用请在订阅前完成声明。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public final class RecordingContext implements AutoCloseable {

    private static final ThreadLocal<RecordingContext> CURRENT = new ThreadLocal<>();

    private final RecordingContext previous;
    private final String sessionId;
    private String invocationId;
    private String templateId;
    private String templateSkeleton;
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private RecordingContext(RecordingContext previous, String sessionId) {
        this.previous = previous;
        this.sessionId = sessionId;
    }

    /**
     * 开启一个录制上下文作用域；sessionId 为会话标识（多轮对话共享同一值）。
     */
    public static RecordingContext start(String sessionId) {
        RecordingContext context = new RecordingContext(CURRENT.get(), sessionId);
        CURRENT.set(context);
        return context;
    }

    static RecordingContext currentOrNull() {
        return CURRENT.get();
    }

    /**
     * 声明调用点标签（记录的 invocationId，调用点解析与 CLI 裁决的可操作标签）。
     */
    public RecordingContext withInvocationId(String invocationId) {
        this.invocationId = invocationId;
        return this;
    }

    /**
     * 声明模板标识（配合系统消息文本生成 templateHash 作为模板锚点）。
     */
    public RecordingContext withTemplateId(String templateId) {
        this.templateId = templateId;
        return this;
    }

    /**
     * 声明模板骨架（动态段替换为稳定占位符的模板形态）——知道自己的模板引擎的
     * 接入方声明后，动态模板下调用点身份按骨架定格，不再随组装漂移裂键。
     */
    public RecordingContext withTemplateSkeleton(String templateSkeleton) {
        this.templateSkeleton = templateSkeleton;
        return this;
    }

    /**
     * 附加自由元数据键值（序列化进记录的 metadata 列）。
     */
    public RecordingContext withMetadata(String key, String value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
        return this;
    }

    String sessionId() {
        return sessionId;
    }

    String invocationId() {
        return invocationId;
    }

    String templateId() {
        return templateId;
    }

    String templateSkeleton() {
        return templateSkeleton;
    }

    Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public void close() {
        CURRENT.set(previous);
    }
}
