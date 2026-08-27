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
 * <p>内置常用 behavior 8 个，覆盖 80% 场景。
 * 未知 behavior 默认通过（不误报比漏报好）。</p>
 *
 * <p>设计决策：内置库用 Map 而非 SPI，10 个常用 behavior 不值得做成插件机制。
 * 需要新 behavior 时提 PR 或 issue 加到内置库。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class BehaviorChecker {

    // 内置常用 behavior
    private static final Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> BUILTINS;

    static {
        Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> builtins = new LinkedHashMap<>();
        // 语言类判定用码点扫描而非正则：`.` 默认不匹配换行，LLM 输出几乎必然多行，
        // 正则全串匹配会把多行中文输出误判为不含中文
        builtins.put("mustUseChinese", (fp, out) -> out != null && containsScript(out, ScriptRange.CJK));
        builtins.put("mustUseEnglish", (fp, out) -> out != null && containsScript(out, ScriptRange.LATIN) && !containsScript(out, ScriptRange.CJK));
        builtins.put("returnsEmptyOnError", (fp, out) -> !fp.isHasError() || out == null || out.trim().isEmpty() || out.contains("[]"));
        // TODO: [空数组判定宽泛] returnsEmptyOnError 的 out.contains("[]") 会把含空数组字面量的
        //       正常输出（如 {"data":[],"message":"成功"}）误判为空输出；待改为 RecursiveJsonParser
        //       解析后按结构判空数组/空对象
        builtins.put("returnsErrorCode", (fp, out) -> fp.isHasError());
        builtins.put("noError", (fp, out) -> !fp.isHasError());
        builtins.put("jsonOutput", (fp, out) -> out != null && (out.trim().startsWith("{") || out.trim().startsWith("[")));
        builtins.put("nonEmptyOutput", (fp, out) -> out != null && !out.trim().isEmpty());
        builtins.put("containsCjk", (fp, out) -> out != null && (containsScript(out, ScriptRange.CJK) || containsScript(out, ScriptRange.KANA)));
        BUILTINS = Collections.unmodifiableMap(builtins);
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
}
