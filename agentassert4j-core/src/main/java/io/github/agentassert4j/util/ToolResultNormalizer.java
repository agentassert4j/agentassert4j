package io.github.agentassert4j.util;

/**
 * 工具结果方言归一 — 各框架适配层的工具返回语义统一入口。
 *
 * <p>背景：部分框架的默认工具执行器对 String 返回的工具方法整体做一层 JSON
 * 编码（语义原文再包引号转义，模型与观察层看到的都是该形态）。此处解码一层
 * 还原语义原文，值溯源与内容处理才能取到叶子值；对象/数组返回本就是 JSON、
 * 纯文本解析不出字符串字面量，两者原样保留。真源 = 工具方法的语义返回，
 * wire 形态是方言。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
public final class ToolResultNormalizer {

    private ToolResultNormalizer() {
    }

    /**
     * 解码一层字符串字面量；null 与空串原样返回，其余形态透传。
     */
    public static String normalize(String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        Object decoded = RecursiveJsonParser.parse(result);
        return decoded instanceof String ? (String) decoded : result;
    }
}
