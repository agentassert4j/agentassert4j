package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.SkillProfile;

import java.util.List;

/**
 * Skill 画像域 SPI — 基线三态生命周期（BASELINE/CANDIDATE/ARCHIVED）的载体存储。
 */
public interface SkillStore {

    /**
     * 保存/更新 Skill 画像
     */
    void saveSkillProfile(SkillProfile p);

    /**
     * 按确定性分组键查询画像
     */
    SkillProfile findSkillByGroupKey(String key);

    /**
     * 全量画像（冷启动检测用）
     */
    List<SkillProfile> findAllSkills();
}
