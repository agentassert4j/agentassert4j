package io.github.agentassert4j.springai2;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单次 AI 调用的工具编排观察缓冲 —— 2.x 的工具循环由 ChatClient 的
 * ToolCallingAdvisor 在 ChatModel 之上驱动（装饰器天然逐轮可见，无需观察）；
 * 本缓冲覆盖的是直调 ChatModel 且模型实现内部消费工具调用的姿势。
 * 缓冲线程安全（stream 内部回路的工具执行可能发生在 reactor 线程）。
 *
 * <p>{@link #decorate} 经 {@code mutate()} 在 options 副本上换装回调，
 * 业务对象零触碰；装饰失败静默返回原 Prompt（纯观察：绝不阻断业务）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
final class ToolInvocationObserver {

    private final List<ObservedToolInvocation> invocations = new CopyOnWriteArrayList<>();

    /**
     * 换装观察回调：请求带工具定义时经 mutate() 在副本上逐个包装；无工具定义或
     * 装饰失败返回原 Prompt
     */
    Prompt decorate(Prompt prompt) {
        try {
            ChatOptions options = prompt.getOptions();
            if (!(options instanceof ToolCallingChatOptions)) {
                return prompt;
            }
            ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
            if (toolOptions.getToolCallbacks() == null || toolOptions.getToolCallbacks().isEmpty()) {
                return prompt;
            }
            List<ToolCallback> wrapped = new ArrayList<>();
            for (ToolCallback callback : toolOptions.getToolCallbacks()) {
                wrapped.add(new ObservingToolCallback(callback, this));
            }
            ToolCallingChatOptions copied = toolOptions.mutate().toolCallbacks(wrapped).build();
            return new Prompt(prompt.getInstructions(), copied);
        } catch (RuntimeException e) {
            return prompt;
        }
    }

    void record(String toolName, String arguments, String result, boolean success) {
        invocations.add(new ObservedToolInvocation(toolName, arguments, result, success));
    }

    /**
     * 观察到的编排（按发生顺序）；无调用时为空表
     */
    List<ObservedToolInvocation> snapshot() {
        return new ArrayList<>(invocations);
    }
}
