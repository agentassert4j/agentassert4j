package io.github.agentassert4j.model;

/**
 * 基线状态枚举 — 三态生命周期（与方案文档 5.9 节一致）。
 *
 * <pre>
 * BASELINE  → 当前基线（首次录制自动建立）
 * CANDIDATE → 候选指纹（等待开发者裁决：approve 或 reject）
 * ARCHIVED  → 已归档的旧基线（支持回滚）
 * </pre>
 */
public enum BaselineStatus {
    /** 当前基线 */
    BASELINE,
    /** 候选（等待开发者裁决） */
    CANDIDATE,
    /** 已归档（approve 时旧基线移入归档） */
    ARCHIVED
}
