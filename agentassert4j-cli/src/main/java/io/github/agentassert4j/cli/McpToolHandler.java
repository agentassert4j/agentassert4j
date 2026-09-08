package io.github.agentassert4j.cli;

import java.util.Map;

/**
 * MCP 工具执行器 — tools/call 的工具侧入口。
 *
 * <p>实现方从 arguments（已确保为 JSON 对象）取参并执行；语义校验失败以 exit 2 +
 * error/1 包络行的 {@link McpToolOutcome} 返回（工具执行错误，isError:true），
 * 不抛异常——未捕获异常由分发层兜底为环境错误，分发循环永不因工具崩溃。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
interface McpToolHandler {

    /**
     * 执行一次工具调用。
     *
     * @param arguments tools/call 的 arguments 成员（缺省为空对象；键值形态见各工具 manifest）
     * @return 命令面形态的执行结果（exit/stdout/stderr）
     */
    McpToolOutcome invoke(Map<String, Object> arguments);
}
