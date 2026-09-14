package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.GovernanceEvent;

import java.util.List;

/**
 * 治理事件域接口 — 治理动作时间线的写入与全量读取（audit 的单一数据源）。
 *
 * <p>事件是只追加真源：无状态痕迹的动作（reject/rollback）只能经本接口发生时落账，
 * 不存在从画像/归档行派生重建的路径。</p>
 *
 * @author axy-yxa
 * @since 2026-09-14
 */
public interface GovernanceEventStore {

    /**
     * 追加一条治理事件；happenedAt 由实现方在写入时刻盖章（调用方不携带时钟）。
     */
    void appendGovernanceEvent(GovernanceEvent event);

    /**
     * 全量事件，按发生时间升序（同刻按写入序稳定排列）。
     */
    List<GovernanceEvent> findGovernanceEvents();
}
