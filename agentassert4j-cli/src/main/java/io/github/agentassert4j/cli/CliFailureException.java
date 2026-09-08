package io.github.agentassert4j.cli;

/**
 * 带机器错误分类的命令失败 — 错误码在抛出点钉死，命令层不做消息反推。
 * 人类通道照常输出消息本体；--json 模式下由 {@code CliSupport.fail} 组装
 * agentassert4j.error/1 包络写 stdout。
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class CliFailureException extends RuntimeException {

    /**
     * 错误码四族之一。
     */
    final CliErrorCode errorCode;
    /**
     * 可行动建议（失败路径必填），随包络 hints 出境。
     */
    final String hint;
    /**
     * 最可能的下一条命令，无明确指向时为空串。
     */
    final String nextAction;

    CliFailureException(CliErrorCode errorCode, String message, String hint, String nextAction) {
        super(message);
        this.errorCode = errorCode;
        this.hint = hint;
        this.nextAction = nextAction;
    }
}
