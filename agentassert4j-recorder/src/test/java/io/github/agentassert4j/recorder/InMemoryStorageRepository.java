package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 内存 StorageRepository 实现，用于单元测试。
 * 不引入 mockito 零依赖。
 */
class InMemoryStorageRepository implements StorageRepository {

    private final List<InteractionRecord> store =
            Collections.synchronizedList(new ArrayList<>());
    private volatile boolean throwOnSave = false;

    void setThrowOnSave(boolean throwOnSave) {
        this.throwOnSave = throwOnSave;
    }

    List<InteractionRecord> getStore() {
        return store;
    }

    @Override
    public String type() {
        return "in-memory";
    }

    @Override
    public void initialize() {
    }

    @Override
    public void close() {
    }

    @Override
    public void saveInteraction(InteractionRecord r) {
        if (throwOnSave) {
            throw new RuntimeException("Simulated DB failure");
        }
        store.add(r);
    }

    @Override
    public void saveInteractions(List<InteractionRecord> records) {
        if (throwOnSave) {
            throw new RuntimeException("Simulated DB failure");
        }
        store.addAll(records);
    }

    @Override
    public List<InteractionRecord> findBySkillId(String skillId) {
        return List.of();
    }

    @Override
    public List<InteractionRecord> findByTemplateHash(String hash) {
        return List.of();
    }

    @Override
    public Set<String> findSkillIdsByTemplateHash(String hash) {
        return Set.of();
    }

    @Override
    public List<InteractionRecord> findBySessionId(String sessionId) {
        return List.of();
    }

    @Override
    public List<String> findAllSessionIds() {
        return List.of();
    }

    @Override
    public void saveSkillProfile(SkillProfile p) {
    }

    @Override
    public SkillProfile findSkillByGroupKey(String key) {
        return null;
    }

    @Override
    public List<SkillProfile> findAllSkills() {
        return List.of();
    }

    @Override
    public void saveTemplateText(String hash, String templateText) {
    }

    @Override
    public String findTemplateText(String hash) {
        return null;
    }

    @Override
    public void saveGraph(String graphJson) {
    }

    @Override
    public String loadGraph() {
        return null;
    }

    @Override
    public void archiveBaseline(String skillId, DeterministicFingerprint fingerprint, String versionTag) {
    }

    @Override
    public ArchivedBaseline findArchivedBaseline(String skillId, String versionTag) {
        return null;
    }

}
