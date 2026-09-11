package io.github.agentassert4j.util;

import java.util.Locale;

/**
 * 模型名 → 供应商标识推断 — SDK 捕获侧共用的纯函数，推断值落 provider 列。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
public final class LlmProviderUtil {

    /**
     * 供应商标识封闭词表（provider 列的合法取值）。
     */
    public enum Provider {
        DEEPSEEK("deepseek"), OPENAI("openai"), ANTHROPIC("anthropic"), QWEN("qwen"), GEMINI("gemini"), OLLAMA("ollama"), CUSTOM("custom");

        private final String wireName;

        Provider(String wireName) {
            this.wireName = wireName;
        }

        /**
         * 线上存储值（provider 列的合法取值）
         */
        public String wireName() {
            return wireName;
        }
    }

    private LlmProviderUtil() {
    }

    /**
     * 按模型名前缀推断供应商；无法识别归 custom，null 模型返回 null。
     */
    public static String inferFromModel(String model) {
        if (model == null) {
            return null;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("deepseek")) {
            return Provider.DEEPSEEK.wireName();
        }
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")) {
            return Provider.OPENAI.wireName();
        }
        if (lower.startsWith("claude")) {
            return Provider.ANTHROPIC.wireName();
        }
        if (lower.startsWith("qwen") || lower.startsWith("qwq")) {
            return Provider.QWEN.wireName();
        }
        if (lower.startsWith("gemini")) {
            return Provider.GEMINI.wireName();
        }
        if (lower.startsWith("llama")) {
            return Provider.OLLAMA.wireName();
        }
        return Provider.CUSTOM.wireName();
    }
}
