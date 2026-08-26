package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.Checkpoint;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;

import java.util.List;
import java.util.Set;

/**
 * 存储仓库 SPI 接口 — 全部定义在 agentassert4j-core/spi/，零外部依赖。
 *
 * <p>遵循 JDBC 模式：接口在 core，实现在独立模块。
 * 插件发现优先级：CLI 显式配置 > Spring Boot AutoConfig > ServiceLoader > 默认 SQLite</p>
 *
 * <p><b>TODO: [设计债务-R6接口隔离]</b> 当前接口包含 17 个方法，超过 R6 原则（≤5 方法/接口）。
 * 方案文档 R6 与 3.7 节存在内部矛盾。待 Phase 1 完成后评估是否拆分为子接口：
 * InteractionStore / SkillStore / GraphStore / BaselineStore / CheckpointStore。
 * 拆分需同步修改所有实现类和方案文档。</p>
 */
public interface StorageRepository {

    // --- 基础生命周期 ---
    String type();
    void initialize();
    void close();

    // --- 交互记录 ---
    void saveInteraction(InteractionRecord r);
    void saveInteractions(List<InteractionRecord> records);
    List<InteractionRecord> findBySkillId(String skillId);
    List<InteractionRecord> findByPromptHash(String hash);
    /** ImpactAnalyzer 核心查询：通过 Prompt hash 反查关联的 Skill */
    Set<String> findSkillIdsByPromptHash(String hash);
    List<InteractionRecord> findBySessionId(String sessionId);
    /** rebuildGraph 使用：获取所有 session ID */
    List<String> findAllSessionIds();

    // --- Skill 画像 ---
    void saveSkillProfile(SkillProfile p);
    SkillProfile findSkillByGroupKey(String key);
    List<SkillProfile> findAllSkills();

    // --- Prompt 文本缓存 ---
    void savePromptText(String hash, String promptText);
    String findPromptText(String hash);

    // --- 依赖图谱 ---
    /** 整体 JSON 持久化，非逐行存边 */
    void saveGraph(String graphJson);
    String loadGraph();

    // --- 基线归档 ---
    void archiveBaseline(String skillId, DeterministicFingerprint fingerprint, String versionTag);
    ArchivedBaseline findArchivedBaseline(String skillId, String versionTag);

    // --- 检查点 ---
    void saveCheckpoint(Checkpoint c);
}
