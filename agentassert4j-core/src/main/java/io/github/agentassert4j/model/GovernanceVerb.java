package io.github.agentassert4j.model;

/**
 * 治理动词 — 治理事件时间线的封闭 wire 词表（audit/1 行的 verb 字段）。
 *
 * <p>六个治理写动词各一值；线上值为 kebab-case，常量名与线上值形态分岔，
 * 禁止 name() 式隐式转换约定。</p>
 *
 * @author axy-yxa
 * @since 2026-09-14
 */
public enum GovernanceVerb {

    /**
     * 候选提升为基线（versionTag = 新版本）
     */
    ACCEPT("accept"),
    /**
     * 候选被否决丢弃（versionTag = 保留的当前版本）
     */
    REJECT("reject"),
    /**
     * 活跃基线回滚到归档版本（versionTag = 目标版本）
     */
    ROLLBACK("rollback"),
    /**
     * 首次建档（versionTag = 建档版本）
     */
    ESTABLISH("establish"),
    /**
     * --force 重建基线（versionTag = 重建版本）
     */
    FORCE_REBUILD("force-rebuild"),
    /**
     * 漂移身份自动并入基线（versionTag = 当前版本；actor 恒 null——框架自动化无主体）
     */
    COLLECT("collect");

    private final String wireName;

    GovernanceVerb(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
