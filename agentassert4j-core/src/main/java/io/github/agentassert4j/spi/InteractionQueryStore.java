package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.List;
import java.util.Set;

/**
 * 交互记录查询域 SPI — 分析管道（分组/指纹/图谱/影响分析）的读取面。
 *
 * <p>实现方必须保证返回顺序确定性（按 timestamp、seq、record_id 稳定排序），
 * 同一数据重复查询返回相同顺序——依赖边构建的可复现性依赖这一点。</p>
 */
public interface InteractionQueryStore {

    /**
     * 按 Skill 查询该 Skill 的全部交互记录
     */
    List<InteractionRecord> findBySkillId(String skillId);

    /**
     * 按模板 hash 查询使用该模板的全部交互记录
     */
    List<InteractionRecord> findByTemplateHash(String hash);

    /**
     * 影响分析核心查询：通过模板 hash 反查关联的 Skill
     */
    Set<String> findSkillIdsByTemplateHash(String hash);

    /**
     * 按 session 查询（依赖链重建用），返回按确定性排序键有序
     */
    List<InteractionRecord> findBySessionId(String sessionId);

    /**
     * 获取所有 session ID（rebuildGraph 使用）
     */
    List<String> findAllSessionIds();
}
