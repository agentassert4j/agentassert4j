package io.github.agentassert4j.spi;

/**
 * 存储仓库 SPI 门面 — 聚合六个读写域子接口 + 生命周期。
 *
 * <p>遵循 JDBC 模式：接口在 core，实现在独立模块。
 * 插件发现优先级：CLI 显式配置 > Spring Boot AutoConfig > ServiceLoader > 默认 SQLite。</p>
 *
 * <p>按读写域拆分（R6 接口隔离，复审确认原为 20 方法超面接口）：
 * 消费方应依赖它实际需要的最小域接口——录制管道只见 {@link InteractionWriteStore}，
 * 分析管道只见 {@link InteractionQueryStore} 等；本门面仅作为组装根与发现入口。
 * checkpoints 域已砍除（只写无读的死表）。</p>
 */
public interface StorageRepository extends
        InteractionWriteStore,
        InteractionQueryStore,
        SkillStore,
        TemplateTextStore,
        GraphStore,
        BaselineArchiveStore {

    // --- 生命周期 ---

    /** 存储后端类型标识（如 "sqlite"） */
    String type();

    /** 初始化（建表/迁移）；失败抛运行时异常且不得泄漏资源 */
    void initialize();

    void close();
}
