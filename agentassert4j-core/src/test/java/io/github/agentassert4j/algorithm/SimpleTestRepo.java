package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ImpactAnalyzer 和 BaselineManager 测试共用的内存 StorageRepository 模拟。
 * 所有数据存于内存 Map，不做 SQL 操作。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class SimpleTestRepo implements StorageRepository {

    final List<InteractionRecord> interactions = new ArrayList<>();
    final Map<String, SkillProfile> skillProfiles = new HashMap<>();
    final Map<String, String> promptTexts = new HashMap<>();
    final List<ArchivedBaseline> archivedBaselines = new ArrayList<>();
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
    public List<InteractionRecord> findBySkillId(String skillId) {
        return interactions.stream().filter(r -> skillId.equals(r.getSkillId())).collect(Collectors.toList());
    }

    @Override
    public List<InteractionRecord> findByTemplateHash(String hash) {
        return interactions.stream().filter(r -> hash.equals(r.getTemplateHash())).collect(Collectors.toList());
    }

    @Override
    public Set<String> findSkillIdsByTemplateHash(String hash) {
        return interactions.stream().filter(r -> hash.equals(r.getTemplateHash())).map(InteractionRecord::getSkillId).filter(id -> id != null && !id.isEmpty()).collect(Collectors.toSet());
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
    public void saveSkillProfile(SkillProfile p) {
        skillProfiles.put(p.getGroupKey(), p);
    }

    @Override
    public SkillProfile findSkillByGroupKey(String key) {
        return skillProfiles.get(key);
    }

    @Override
    public List<SkillProfile> findAllSkills() {
        return new ArrayList<>(skillProfiles.values());
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
    public void archiveBaseline(ArchivedBaseline archived) {
        archived.setArchivedAt(System.currentTimeMillis());
        archivedBaselines.add(archived);
    }

    @Override
    public ArchivedBaseline findArchivedBaseline(String skillId, String versionTag) {
        // 与 SQLite 实现的 tiebreaker 语义一致：同 skill 同版本多行归档时最近归档者胜，
        // 避免 Core 单测结论与生产实现方向相反
        ArchivedBaseline latest = null;
        for (ArchivedBaseline ab : archivedBaselines) {
            if (skillId.equals(ab.getSkillId()) && versionTag.equals(ab.getVersionTag())) {
                latest = ab;
            }
        }
        return latest;
    }

    @Override
    public List<ArchivedBaseline> findArchivedBaselines(String skillId) {
        // 与 SQLite 实现一致：最近归档在前（插入序反向）
        List<ArchivedBaseline> result = new ArrayList<>();
        for (int i = archivedBaselines.size() - 1; i >= 0; i--) {
            ArchivedBaseline ab = archivedBaselines.get(i);
            if (skillId.equals(ab.getSkillId())) {
                result.add(ab);
            }
        }
        return result;
    }

}
