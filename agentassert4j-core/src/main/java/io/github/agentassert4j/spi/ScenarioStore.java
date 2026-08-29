package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.ScenarioDefinition;
import io.github.agentassert4j.model.ScenarioRun;

import java.util.List;

/**
 * 场景读写域 SPI — 场景声明实体与执行事实的持久化。
 *
 * <p>声明实体 upsert（scenario_id 为业务主键），执行事实只追加
 * （run_id 全局唯一，重复写入按存储层 INSERT 语义处理）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
public interface ScenarioStore {

    /**
     * 保存场景声明（同 scenario_id 覆盖更新）
     */
    void saveScenario(ScenarioDefinition definition);

    /**
     * 全部场景声明（按 scenario_id 字典序）
     */
    List<ScenarioDefinition> findScenarios();

    /**
     * 追加一次场景执行事实
     */
    void saveScenarioRun(ScenarioRun run);

    /**
     * 某场景的执行历史（按开始时间升序）
     */
    List<ScenarioRun> findScenarioRuns(String scenarioId);
}
