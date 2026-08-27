package io.github.agentassert4j.recorder;

import io.github.agentassert4j.util.HashUtil;

/**
 * 脱敏策略枚举 — 指定敏感字段值的处理方式。
 *
 * <p>对应 data-protection.strategy 配置项。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum SanitizeStrategy {

    /**
     * 值替换为固定掩码 {@code "***"}。
     * 适用于：密码、Token 等不需要保留原始值的场景。
     */
    MASK,

    /**
     * 值替换为 SHA-256 哈希（小写十六进制）。
     * 适用于：需要保持唯一性但不可逆的场景。
     */
    HASH,

    /**
     * 不存储该字段（从 JSON 中删除键值对）。
     * 适用于：绝对不能落盘的敏感数据（如身份证号）。
     */
    DROP;

    private static final String MASK_VALUE = "***";

    /**
     * 按策略替换敏感值。
     *
     * @param value 原始敏感值
     * @return 脱敏后的值；DROP 策略返回 null
     */
    public String apply(String value) {
        if (value == null) {
            return null;
        }
        switch (this) {
            case MASK:
                return MASK_VALUE;
            case HASH:
                return HashUtil.sha256(value);
            case DROP:
                return null;
            default:
                return MASK_VALUE;
        }
    }
}
