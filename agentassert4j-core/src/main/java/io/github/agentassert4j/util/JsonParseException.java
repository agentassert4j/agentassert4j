package io.github.agentassert4j.util;

/**
 * JSON 解析失败异常 — 只由 {@link RecursiveJsonParser#parseStrict(String)} 抛出，
 * 消息携带根因（语法错误 / 嵌套超限 / 尾部多余字符 / 空白输入）。
 *
 * <p>宽松入口 {@link RecursiveJsonParser#parse(String)} 永不抛出本异常（退化不中断）；
 * 需要区分「解析失败根因」的消费方（如拒收包络、配置诊断）显式选择严格入口。</p>
 *
 * @author axy-yxa
 * @since 2026-09-23
 */
public class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }
}
