package io.github.agentassert4j.util;

/**
 * 字符串工具（core 零依赖约束下的 JDK 8 兼容实现）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public final class TextUtil {

    private TextUtil() {
    }

    // 语义与 String#isBlank（JDK 11+）完全对齐，勿简化为 trim().isEmpty()
    public static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
