package io.github.agentassert4j.model;

/**
 * 置信度枚举 — 依赖图谱边的置信度。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum Confidence {
    /**
     * 字段值精确匹配
     */
    HIGH,
    /**
     * 字段名前缀匹配
     */
    LOW,
    /**
     * 穿透压缩产生的透传边
     */
    TRANSPARENT
}
