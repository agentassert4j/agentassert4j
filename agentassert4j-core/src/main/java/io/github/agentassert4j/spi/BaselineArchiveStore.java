package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.ArchivedBaseline;

/**
 * 基线归档域 SPI — approve 时旧基线移入归档，支持回滚到任意历史版本。
 *
 * <p>归档以 {@link ArchivedBaseline} 值对象整体写入：指纹、版本标签之外，
 * 语义版本与审批事实随行快照，将来归档字段扩展只动模型不动本接口。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface BaselineArchiveStore {

    /**
     * 写入归档行。archivedAt 由实现方在写入时刻盖章。
     */
    void archiveBaseline(ArchivedBaseline archived);

    ArchivedBaseline findArchivedBaseline(String skillId, String versionTag);
}
