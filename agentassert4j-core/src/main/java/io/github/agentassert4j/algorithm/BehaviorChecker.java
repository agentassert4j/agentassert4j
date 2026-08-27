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
 */
public final class BehaviorChecker {

    // 内置常用 behavior
    private static final Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> BUILTINS;

    static {
        Map<String, BiFunction<DeterministicFingerprint, String, Boolean>> builtins = new LinkedHashMap<>();
        builtins.put("mustUseChinese", (fp, out) -> out != null && out.matches(".*[\\u4e00-\\u9fa5].*"));
        builtins.put("mustUseEnglish", (fp, out) -> out != null && out.matches(".*[a-zA-Z].*") && !out.matches(".*[\\u4e00-\\u9fa5].*"));
        builtins.put("returnsEmptyOnError", (fp, out) -> !fp.isHasError() || out == null || out.trim().isEmpty() || out.contains("[]"));
        // TODO: returnsEmptyOnError 中 out.contains("[]") 判断过于宽泛，
        //       如 {"data":[],"message":"成功"} 含有 [] 但非错误输出会被误判。
        //       待后续优化为使用 RecursiveJsonParser.parse() 解析后检查是否为空数组/空对象
        builtins.put("returnsErrorCode", (fp, out) -> fp.isHasError());
        builtins.put("noError", (fp, out) -> !fp.isHasError());
        builtins.put("jsonOutput", (fp, out) -> out != null && (out.trim().startsWith("{") || out.trim().startsWith("[")));
        builtins.put("nonEmptyOutput", (fp, out) -> out != null && !out.trim().isEmpty());
        builtins.put("containsCjk", (fp, out) -> out != null && out.matches(".*[\\u4e00-\\u9fa5\\u3040-\\u309f\\u30a0-\\u30ff].*"));
        BUILTINS = Collections.unmodifiableMap(builtins);
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
