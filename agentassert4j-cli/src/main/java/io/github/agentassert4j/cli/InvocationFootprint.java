package io.github.agentassert4j.cli;

/**
 * 调用点足迹 — 按完整键分组的只读巡检视图（status 未建档段与 doctor 共用的派生行实体）。
 *
 * @author axy-yxa
 * @since 2026-09-02
 */
final class InvocationFootprint {

    final String invocationKey;
    final String label;
    final int recordCount;
    final String lastSessionId;

    InvocationFootprint(String invocationKey, String label, int recordCount, String lastSessionId) {
        this.invocationKey = invocationKey;
        this.label = label;
        this.recordCount = recordCount;
        this.lastSessionId = lastSessionId;
    }
}
