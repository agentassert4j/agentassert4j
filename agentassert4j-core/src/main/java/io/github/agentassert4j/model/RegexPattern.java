package io.github.agentassert4j.model;

/**
 * 正则模式 — 维度 3 中用户声明的正则约束。
 */
public class RegexPattern {

    private String pattern;
    private String description;

    public RegexPattern() {}

    public RegexPattern(String pattern, String description) {
        this.pattern = pattern;
        this.description = description;
    }

    /**
     * 检查给定文本是否匹配此正则。
     *
     * <p>TODO: [性能债] 每次调用都重新编译 Pattern，在 DeterministicComparator 中可能被频繁调用。
     * 待 config 包实现后，应在创建 RegexPattern 时预编译 Pattern 并缓存，
     * 或在 DeterministicComparator 中做 Pattern 缓存。</p>
     */
    public boolean matches(String text) {
        if (text == null || pattern == null) return false;
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
