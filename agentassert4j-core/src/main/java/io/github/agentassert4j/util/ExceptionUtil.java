package io.github.agentassert4j.util;

/**
 * 异常因果链工具 — 根因定位的单一定义处。
 *
 * <p>CLI 错误包络与存储层日志都需要把层层包装下的根因消息露出来；
 * 深度封顶防御病态因果链（构造异常时的循环引用或超长链不得拖垮调用方）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public final class ExceptionUtil {

    private static final int MAX_CAUSE_DEPTH = 8;

    private ExceptionUtil() {
    }

    /**
     * 沿因果链走到最深层异常（深度封顶；自引用立即停）。
     */
    public static Throwable rootCause(Throwable e) {
        if (e == null) {
            return null;
        }
        Throwable current = e;
        for (int i = 0; i < MAX_CAUSE_DEPTH && current.getCause() != null && current.getCause() != current; i++) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 根因的可读消息：优先根因自身 message，缺失时退化为 toString，入参为 null 时返回空串。
     */
    public static String rootMessage(Throwable e) {
        Throwable root = rootCause(e);
        if (root == null) {
            return "";
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }
}
