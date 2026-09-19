package io.github.agentassert4j.model;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则模式 — 维度 3 中用户声明的正则约束。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class RegexPattern {

    private String pattern;
    private String description;
    /**
     * 编译缓存：Pattern.compile 代价随模式复杂度增长，重放逐记录逐条匹配按
     * 声明实例只编译一次。volatile 容忍并发首编译的良性竞态（重复编译结果相同）。
     */
    private volatile Pattern compiled;

    public RegexPattern(String pattern, String description) {
        this.pattern = pattern;
        this.description = description;
    }

    /**
     * 检查给定文本是否匹配此正则。
     *
     * <p>非法正则（用户声明笔误等）按不匹配处理：判定趋向回归而非崩溃，
     * 坏规则在每条重放结果中都表现为可见的不匹配信号。</p>
     */
    public boolean matches(String text) {
        if (text == null || pattern == null) return false;
        try {
            Pattern cached = compiled;
            if (cached == null) {
                cached = Pattern.compile(pattern);
                compiled = cached;
            }
            return cached.matcher(text).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
        this.compiled = null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegexPattern)) return false;
        RegexPattern other = (RegexPattern) o;
        return Objects.equals(pattern, other.pattern) && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, description);
    }
}
