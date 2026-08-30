package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.ArchivedTemplateVersion;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 内存 StorageRepository 实现，用于单元测试。
 * 不引入 mockito 零依赖。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class InMemoryStorageRepository implements StorageRepository {

    private final List<InteractionRecord> store = Collections.synchronizedList(new ArrayList<>());
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
    public List<InteractionRecord> findByInvocationId(String invocationId) {
        return Collections.emptyList();
    }

    @Override
    public List<InteractionRecord> findByInvocationKey(String invocationKey) {
        return Collections.emptyList();
    }

    @Override
    public List<InteractionRecord> findByTemplateHash(String hash) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> findInvocationKeysByTemplateHash(String hash) {
        return Collections.emptySet();
    }

    @Override
    public List<InteractionRecord> findBySessionId(String sessionId) {
        return Collections.emptyList();
    }

    @Override
    public List<String> findAllSessionIds() {
        return Collections.emptyList();
    }

    @Override
    public void saveInvocationProfile(InvocationProfile p) {
    }

    @Override
    public InvocationProfile findInvocationByKey(String key) {
        return null;
    }

    @Override
    public List<InvocationProfile> findAllInvocations() {
        return Collections.emptyList();
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
    public void archiveTemplateVersion(ArchivedTemplateVersion archived) {
    }

    @Override
    public ArchivedTemplateVersion findArchivedVersion(String invocationId, String versionTag) {
        return null;
    }

    @Override
    public List<ArchivedTemplateVersion> findArchivedVersions(String invocationId) {
        return Collections.emptyList();
    }

}
