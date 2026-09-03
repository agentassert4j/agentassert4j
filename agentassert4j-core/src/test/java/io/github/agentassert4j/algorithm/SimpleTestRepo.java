package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.ArchivedTemplateVersion;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 核心算法单元测试共用的内存 StorageRepository 模拟。
 * 所有数据存于内存 Map，不做 SQL 操作。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class SimpleTestRepo implements StorageRepository {

    final List<InteractionRecord> interactions = new ArrayList<>();
    final Map<String, InvocationProfile> invocationProfiles = new HashMap<>();
    final Map<String, String> promptTexts = new HashMap<>();
    final List<ArchivedTemplateVersion> archivedBaselines = new ArrayList<>();
    String graphJson;

    @Override
    public String type() {
        return "test";
    }

    @Override
    public void initialize() {
    }

    @Override
    public void close() {
    }

    @Override
    public void saveInteraction(InteractionRecord r) {
        interactions.add(r);
    }

    @Override
    public void saveInteractions(List<InteractionRecord> records) {
        interactions.addAll(records);
    }

    @Override
    public List<InteractionRecord> findByInvocationId(String invocationId) {
        return interactions.stream().filter(r -> invocationId.equals(r.getInvocationId())).collect(Collectors.toList());
    }

    @Override
    public List<InteractionRecord> findByTemplateHash(String hash) {
        return interactions.stream().filter(r -> hash.equals(r.getTemplateHash())).collect(Collectors.toList());
    }

    @Override
    public List<InteractionRecord> findByInvocationKey(String invocationKey) {
        return interactions.stream().filter(r -> invocationKey.equals(r.getInvocationKey())).collect(Collectors.toList());
    }

    @Override
    public List<InteractionRecord> findBySessionId(String sessionId) {
        return interactions.stream().filter(r -> sessionId.equals(r.getSessionId())).collect(Collectors.toList());
    }

    @Override
    public List<String> findAllSessionIds() {
        return interactions.stream().map(InteractionRecord::getSessionId).distinct().collect(Collectors.toList());
    }

    @Override
    public void saveInvocationProfile(InvocationProfile p) {
        invocationProfiles.put(p.getInvocationKey(), p);
    }

    @Override
    public InvocationProfile findInvocationByKey(String key) {
        return invocationProfiles.get(key);
    }

    @Override
    public List<InvocationProfile> findAllInvocations() {
        return new ArrayList<>(invocationProfiles.values());
    }

    @Override
    public void saveTemplateText(String hash, String templateText) {
        promptTexts.put(hash, templateText);
    }

    @Override
    public String findTemplateText(String hash) {
        return promptTexts.get(hash);
    }

    @Override
    public void saveGraph(String graphJson) {
        this.graphJson = graphJson;
    }

    @Override
    public String loadGraph() {
        return graphJson;
    }

    @Override
    public void archiveTemplateVersion(ArchivedTemplateVersion archived) {
        archived.setArchivedAt(System.currentTimeMillis());
        archivedBaselines.add(archived);
    }

    @Override
    public ArchivedTemplateVersion findArchivedVersion(String invocationKey, String versionTag) {
        // 与 SQLite 实现的 tiebreaker 语义一致：同调用点同版本多行归档时最近归档者胜，
        // 避免 Core 单测结论与生产实现方向相反
        ArchivedTemplateVersion latest = null;
        for (ArchivedTemplateVersion ab : archivedBaselines) {
            if (invocationKey.equals(ab.getInvocationKey()) && versionTag.equals(ab.getVersionTag())) {
                latest = ab;
            }
        }
        return latest;
    }

    @Override
    public List<ArchivedTemplateVersion> findArchivedVersions(String invocationKey) {
        // 与 SQLite 实现一致：最近归档在前（插入序反向）
        List<ArchivedTemplateVersion> result = new ArrayList<>();
        for (int i = archivedBaselines.size() - 1; i >= 0; i--) {
            ArchivedTemplateVersion ab = archivedBaselines.get(i);
            if (invocationKey.equals(ab.getInvocationKey())) {
                result.add(ab);
            }
        }
        return result;
    }

}
