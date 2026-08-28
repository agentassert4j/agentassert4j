package io.github.agentassert4j.cli;

/**
 * JSON 字符串转义工具 — CLI 侧手工拼装 JSON（报告/请求体）共用的单一出口。
 *
 * <p>除常见短转义外，所有 &lt;0x20 控制字符按 JSON 规范强制转义为
 * {@code \\uXXXX}——用户输入携带原始控制字符时产物必须仍是合法 JSON。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
public final class JsonSupport {

    private JsonSupport() {
    }

    /**
     * 按 JSON 规范转义字符串；null 视为空串。
     */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
