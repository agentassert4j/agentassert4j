package io.github.agentassert4j.springai2;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具回调观察装饰器 —— 100% 委托透传（永不执行/过滤/修改工具调用），
 * 只在调用前后把 toolName + 参数原文 + 结果原文记入共享缓冲。
 * 业务异常照常上抛，仅标记 success=false；观察本身对业务零可见副作用。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
final class ObservingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolInvocationObserver observer;

    ObservingToolCallback(ToolCallback delegate, ToolInvocationObserver observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        try {
            String result = delegate.call(toolInput);
            observer.record(toolName(), toolInput, result, true);
            return result;
        } catch (RuntimeException e) {
            observer.record(toolName(), toolInput, null, false);
            throw e;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            String result = delegate.call(toolInput, toolContext);
            observer.record(toolName(), toolInput, result, true);
            return result;
        } catch (RuntimeException e) {
            observer.record(toolName(), toolInput, null, false);
            throw e;
        }
    }

    /**
     * 工具名取自工具定义；定义访问失败按空名记录（纯观察：任何异常不出现在业务链路）
     */
    private String toolName() {
        try {
            return delegate.getToolDefinition().name();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
