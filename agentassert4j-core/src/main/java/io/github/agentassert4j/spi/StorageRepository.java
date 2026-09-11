package io.github.agentassert4j.spi;

/**
 * 存储仓库 SPI 门面 — 聚合五个读写域子接口 + 生命周期。
 *
 * <p>遵循 JDBC 模式：接口在 core，实现在独立模块；组装根（CLI / starter）
 * 显式装配实现，无运行时插件发现。</p>
 *
 * <p>按读写域拆分：消费方应依赖它实际需要的最小域接口——
 * 录制管道只见 {@link InteractionWriteStore}，分析管道只见 {@link InteractionQueryStore} 等；
 * 本门面仅作为组装根。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface StorageRepository extends InteractionWriteStore, InteractionQueryStore, InvocationStore, TemplateTextStore, TemplateVersionArchiveStore {

    /**
     * 初始化（建表/迁移）；失败抛运行时异常且不得泄漏资源
     */
    void initialize();

    void close();
}
