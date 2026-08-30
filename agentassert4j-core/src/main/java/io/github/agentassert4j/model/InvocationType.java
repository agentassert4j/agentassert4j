package io.github.agentassert4j.model;

/**
 * 调用点视图分类枚举 — 纯展示维度，不参与身份与判定。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum InvocationType {
    /**
     * 有工具调用的调用点
     */
    TOOL,
    /**
     * 纯对话（无工具调用）
     */
    PURE_CHAT
}
