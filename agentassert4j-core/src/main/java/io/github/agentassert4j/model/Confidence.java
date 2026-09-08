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
    HIGH(0),
    /**
     * 字段名前缀匹配
     */
    LOW(1);

    private final int rank;

    Confidence(int rank) {
        this.rank = rank;
    }

    /**
     * 置信度秩：数值越小置信越高。边合并按秩比较大小语义，
     * 与枚举声明顺序解耦——重排枚举常量不得改变合并方向。
     */
    public int rank() {
        return rank;
    }
}
