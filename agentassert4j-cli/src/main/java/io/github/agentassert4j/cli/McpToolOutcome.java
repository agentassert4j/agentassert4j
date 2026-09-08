package io.github.agentassert4j.cli;

/**
 * 工具执行结果 — 命令面形态的统一载体：每个 MCP 工具（无论直调命令类还是 record 摄取）
 * 都产出 exit/stdout/stderr 三元组，与命令行进程语义同构。
 *
 * <p>exit 0/1（含 CHANGED 判定）是成功态——判定语义由报告承载，不由 isError 承载；
 * exit 2 是失败态，stdout 末尾携带 agentassert4j.error/1 包络行。分发层据此组装
 * 工具结果的 content 与 structuredContent 双形态。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpToolOutcome {

    final int exit;
    final String stdout;
    final String stderr;

    private McpToolOutcome(int exit, String stdout, String stderr) {
        this.exit = exit;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
    }

    static McpToolOutcome of(int exit, String stdout, String stderr) {
        return new McpToolOutcome(exit, stdout, stderr);
    }

    boolean failed() {
        return exit == 2;
    }
}
