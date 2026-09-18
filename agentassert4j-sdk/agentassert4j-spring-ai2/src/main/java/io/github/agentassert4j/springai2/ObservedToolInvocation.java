package io.github.agentassert4j.springai2;

/**
 * 一次工具调用的观察事实 —— 装饰器从回调现场提取的原始三要素。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
final class ObservedToolInvocation {

    final String toolName;
    /**
     * 模型产出的参数 JSON 原文（ToolCallback.call 的入参）
     */
    final String arguments;
    /**
     * 业务工具的返回原文；执行失败为 null
     */
    final String result;
    final boolean success;

    ObservedToolInvocation(String toolName, String arguments, String result, boolean success) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.success = success;
    }
}
