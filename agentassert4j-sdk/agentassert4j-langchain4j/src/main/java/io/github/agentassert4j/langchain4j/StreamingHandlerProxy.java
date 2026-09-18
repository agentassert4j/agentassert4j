package io.github.agentassert4j.langchain4j;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式响应 handler 的动态代理转发层 — 聚合完成信号驱动录制，其余回调全量转发。
 *
 * <p>用 Proxy 而非普通包装类：LangChain4j 在 1.x 线内持续为 handler 增补带默认
 * 实现的富回调（部分思考/部分工具调用等），普通包装类只能覆写编译期可见的方法，
 * 运行在更高版本时会静默丢弃用户新回调的覆写；代理按方法名转发对现在与未来的
 * 回调一视同仁。代理接口集动态纳入用户 handler 的全部实现接口，防未来把回调
 * 拆进子接口时模型按扩展类型持有 handler 而类型不符。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
final class StreamingHandlerProxy implements InvocationHandler {

    /**
     * 聚合完成时驱动一次录制；ttftMs 为首个分片到达耗时，无分片直接完成时为 null。
     */
    interface CompletionListener {

        void onComplete(ChatResponse response, Long ttftMs, long latencyMs);
    }

    private final StreamingChatResponseHandler userHandler;
    private final CompletionListener listener;
    private final long startMs;
    private final AtomicLong firstPartialMs = new AtomicLong(-1);

    private StreamingHandlerProxy(StreamingChatResponseHandler userHandler, CompletionListener listener, long startMs) {
        this.userHandler = userHandler;
        this.listener = listener;
        this.startMs = startMs;
    }

    static StreamingChatResponseHandler wrap(StreamingChatResponseHandler userHandler, CompletionListener listener) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = userHandler.getClass(); type != null; type = type.getSuperclass()) {
            interfaces.addAll(Arrays.asList(type.getInterfaces()));
        }
        interfaces.add(StreamingChatResponseHandler.class);
        return (StreamingChatResponseHandler) Proxy.newProxyInstance(StreamingChatResponseHandler.class.getClassLoader(), interfaces.toArray(new Class<?>[0]), new StreamingHandlerProxy(userHandler, listener, System.currentTimeMillis()));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return forward(method, args);
        }
        if ("onPartialResponse".equals(method.getName()) && args != null && args.length >= 1) {
            // 按方法名匹配而非签名：分片回调存在带上下文的富重载（高版本新增），
            // 任意重载的首达都计首 token 时延
            firstPartialMs.compareAndSet(-1, System.currentTimeMillis());
        }
        boolean completion = "onCompleteResponse".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof ChatResponse;
        if (!completion) {
            return forward(method, args);
        }
        // 完成信号已到：录制放 finally——用户回调自身抛错不丢该次记录
        // （业务异常仍原样上抛，录制绝不改变回调链的可观察行为）
        try {
            return forward(method, args);
        } finally {
            long first = firstPartialMs.get();
            Long ttftMs = first > 0 ? Long.valueOf(first - startMs) : null;
            try {
                listener.onComplete((ChatResponse) args[0], ttftMs, System.currentTimeMillis() - startMs);
            } catch (Exception e) {
                // 录制失败只影响旁路：吞掉，不惊扰已完成的业务回调链
            }
        }
    }

    /**
     * 转发并解包反射包装异常——用户回调抛出的原始异常原样上抛，
     * 不被代理层多包一层 UndeclaredThrowableException（异常透明性）。
     */
    private Object forward(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(userHandler, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
