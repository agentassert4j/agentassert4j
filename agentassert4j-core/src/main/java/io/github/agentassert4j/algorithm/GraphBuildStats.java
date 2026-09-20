package io.github.agentassert4j.algorithm;

/**
 * 图重建扫描统计 — 空图时可区分「没数据」与「数据在、无边」。
 *
 * <p>candidatePairs = 会话内跨调用点键的有序记录对数：该值为 0 说明全部记录对
 * 共享同一调用点身份（值流边的前提不存在），是空图最常见根因。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public final class GraphBuildStats {

    private final int sessions;
    private final int records;
    private final int invocationKeys;
    private final int candidatePairs;

    public GraphBuildStats(int sessions, int records, int invocationKeys, int candidatePairs) {
        this.sessions = sessions;
        this.records = records;
        this.invocationKeys = invocationKeys;
        this.candidatePairs = candidatePairs;
    }

    public int getSessions() {
        return sessions;
    }

    public int getRecords() {
        return records;
    }

    public int getInvocationKeys() {
        return invocationKeys;
    }

    public int getCandidatePairs() {
        return candidatePairs;
    }
}
