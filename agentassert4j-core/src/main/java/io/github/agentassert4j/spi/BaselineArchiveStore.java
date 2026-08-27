package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.DeterministicFingerprint;

/**
 * 基线归档域 SPI — approve 时旧基线移入归档，支持回滚到任意历史版本。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface BaselineArchiveStore {

    void archiveBaseline(String skillId, DeterministicFingerprint fingerprint, String versionTag);

    ArchivedBaseline findArchivedBaseline(String skillId, String versionTag);
}
