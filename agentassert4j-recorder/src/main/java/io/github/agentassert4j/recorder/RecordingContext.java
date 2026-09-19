package io.github.agentassert4j.recorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 录制上下文 — 业务线程向旁路录制声明会话归属与业务标注。
 *
 * <p>框架适配层的模型接口不携带会话概念（会话在更高层的编排/记忆组件），
 * SDK 用线程绑定的临时作用域补齐。三个框架适配线（Spring AI 1.x/2.x、
 * LangChain4j）共用本类：它不依赖任何框架类型，只在 recorder 层维护这份实现，
 * 适配模块不再各持副本。</p>
 *
 * <pre>{@code
 * try (RecordingContext ctx = RecordingContext.start("session-1")
 *         .withInvocationId("order-refund")
 *         .withTemplateId("order-skill")
 *         .withTemplateSkeleton("你是订单助手，今天是{{date}}")
 *         .withEndpoint("http://llm-gw:8000")
 *         .withMetadata("channel", "app")) {
 *     ... 业务调用 ...
 * }
 * }</pre>
 * <p>作用域可嵌套，关闭时恢复外层。未声明时录制管道按既有退化策略处理
 * （sessionId 缺失的记录各自成独立会话）。仅在声明线程内生效——异步完成
 * 线程上的调用取不到上下文，需要标注的流式调用请在发起前完成声明。</p>
 *
 * <p>必须以 try-with-resources 或显式 {@code close()} 结束作用域：忘记关闭会把
 * 本作用域残留到池化线程上，同线程的后续任务将静默携带旧的 sessionId/invocationId。</p>
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
    private String endpoint;
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

    /**
     * 当前线程的活跃作用域；未声明返回 null。适配层捕获路径经此取声明。
     */
    public static RecordingContext currentOrNull() {
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
     * 声明本次调用的端点地址（记录的 endpoint 列，基线跨部署可比的部署身份）。
     * 多模型 JVM 用逐调用声明区分部署点；单模型 JVM 配录制器级默认即可。
     */
    public RecordingContext withEndpoint(String endpoint) {
        this.endpoint = endpoint;
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

    /**
     * 会话标识（构造后不可变）。
     */
    public String sessionId() {
        return sessionId;
    }

    public String invocationId() {
        return invocationId;
    }

    public String templateId() {
        return templateId;
    }

    public String templateSkeleton() {
        return templateSkeleton;
    }

    public String endpoint() {
        return endpoint;
    }

    /**
     * 已声明元数据的只读视图（键序 = 声明序）。
     */
    public Map<String, String> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void close() {
        CURRENT.set(previous);
    }
}
