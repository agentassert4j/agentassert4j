package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 内置行为校验库 — 维度 4 中用户声明的 behavior 校验。
 *
 * <p>未知 behavior 默认通过（不误报比漏报好）。</p>
 *
 * <p>设计决策：内置库用 Map 而非 SPI——十来个常用 behavior 不值得做成插件机制。
 * 需要新 behavior 时提 PR 或 issue 加到内置库。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class BehaviorChecker {

    private static final Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> BUILTINS;
    private static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> builtins = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        // 语言类判定用码点扫描而非正则：`.` 默认不匹配换行，LLM 输出几乎必然多行，
        // 正则全串匹配会把多行中文输出误判为不含中文
        builtins.put("mustUseChinese", (fp, out) -> out != null && containsScript(out, ScriptRange.CJK));
        descriptions.put("mustUseChinese", "output contains Chinese characters (multiline-safe)");
        builtins.put("mustUseEnglish", (fp, out) -> out != null && containsScript(out, ScriptRange.LATIN) && !containsScript(out, ScriptRange.CJK));
        descriptions.put("mustUseEnglish", "output contains Latin letters and no Chinese characters");
        builtins.put("returnsEmptyOnError", (fp, out) -> !fp.isHasError() || out == null || out.trim().isEmpty() || out.contains("[]"));
        descriptions.put("returnsEmptyOnError", "output is empty on error");
        // TODO: [空数组判定宽泛] returnsEmptyOnError 的 out.contains("[]") 会把含空数组字面量的
        //       正常输出（如 {"data":[],"message":"成功"}）误判为空输出；待改为 RecursiveJsonParser
        //       解析后按结构判空数组/空对象
        builtins.put("returnsErrorCode", (fp, out) -> fp.isHasError());
        descriptions.put("returnsErrorCode", "output contains an error-code field");
        builtins.put("noError", (fp, out) -> !fp.isHasError());
        descriptions.put("noError", "no error field appeared in this interaction");
        builtins.put("jsonOutput", (fp, out) -> out != null && (out.trim().startsWith("{") || out.trim().startsWith("[")));
        descriptions.put("jsonOutput", "output is JSON-shaped (starts with { or [)");
        builtins.put("nonEmptyOutput", (fp, out) -> out != null && !out.trim().isEmpty());
        descriptions.put("nonEmptyOutput", "output is not empty");
        builtins.put("containsCjk", (fp, out) -> out != null && (containsScript(out, ScriptRange.CJK) || containsScript(out, ScriptRange.KANA)));
        descriptions.put("containsCjk", "output contains CJK characters (including Japanese kana)");
        BUILTINS = Collections.unmodifiableMap(builtins);
        DESCRIPTIONS = Collections.unmodifiableMap(descriptions);
    }

    private enum ScriptRange {
        CJK('\u4e00', '\u9fa5'), KANA('\u3040', '\u30ff'), LATIN('a', 'z');

        final char from;
        final char to;

        ScriptRange(char from, char to) {
            this.from = from;
            this.to = to;
        }
    }

    private static boolean containsScript(String text, ScriptRange range) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (range == ScriptRange.LATIN) {
                // 拉丁字母是 A-Z 与 a-z 两个不连续区间，中间的标点不算英文
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    return true;
                }
            } else if (c >= range.from && c <= range.to) {
                return true;
            }
        }
        return false;
    }

    private BehaviorChecker() {
    }

    /**
     * 校验单个 behavior。
     *
     * @param behavior behavior 名称
     * @param fp       当前指纹
     * @param output   当前输出文本
     * @return true=通过，false=不满足
     */
    public static boolean check(String behavior, DeterministicFingerprint fp, String output) {
        BiFunction<DeterministicFingerprint, String, Boolean> checker = BUILTINS.get(behavior);
        if (checker != null) return checker.apply(fp, output);
        // 未知的 behavior → 默认通过（不误报比漏报好）
        return true;
    }

    /**
     * 批量校验所有声明的 behavior。
     *
     * @param behaviors 声明的 behavior 集合
     * @param fp        当前指纹
     * @param output    当前输出文本
     * @return true=全部通过，false=至少一个不满足
     */
    public static boolean checkAll(Set<String> behaviors, DeterministicFingerprint fp, String output) {
        if (behaviors == null || behaviors.isEmpty()) return true;
        for (String behavior : behaviors) {
            if (!check(behavior, fp, output)) return false;
        }
        return true;
    }

    /**
     * 获取所有内置 behavior 名称。
     */
    public static Set<String> getBuiltinBehaviorNames() {
        return BUILTINS.keySet();
    }

    /**
     * behavior 的用户目录说明——与词表同址声明，新增 behavior 时漏写说明会在
     * 本方法返回 null 处显式可见，而不是消费方 switch 的静默兜底。
     */
    public static String describeBehavior(String behavior) {
        return DESCRIPTIONS.get(behavior);
    }
}
