package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量测试筛选 — 数据驱动的变更影响分析（与方案文档 5.5-5.6 节一致）。
 *
 * <p>核心洞察：测试范围由实际数据决定，不依赖变更类型猜测。
 * 框架已掌握回答"影响范围"所需的全部数据——每条 InteractionRecord 都存储了
 * systemPromptHash，可直接查询哪些 Skill 使用了该 Prompt。</p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>查询：哪些 Skill 使用了变更前的 Prompt hash？</li>
 *   <li>冷启动检测：数据库为空或无匹配时给出引导提示</li>
 *   <li>图遍历：从直接受影响的 Skill 出发，沿依赖图谱找到所有下游受影响 Skill</li>
 *   <li>自适应测试密度：全局 Prompt（10+ Skill 共享）采样，局部 Prompt 全量</li>
 * </ol>
 *
 * <p><b>设计决策</b>：零分类、零硬编码、零配置。数据说话。</p>
 */
public class ImpactAnalyzer {

    /** 全局/局部 Prompt 阈值：共享 Skill >= 此值视为全局 Prompt，启用采样 */
    static final int GLOBAL_PROMPT_THRESHOLD = 10;

    /** 全局 Prompt 时每个 Skill 采样的最大用例数 */
    static final int GLOBAL_SAMPLE_PER_SKILL = 3;

    private final StorageRepository repository;
    private final InMemoryDependencyGraph graph;

    public ImpactAnalyzer(StorageRepository repository, InMemoryDependencyGraph graph) {
        this.repository = repository;
        this.graph = graph;
    }

    /**
     * 分析 Prompt 变更的影响范围（数据驱动）。
     *
     * @param oldPromptHash 变更前的 System Prompt SHA-256 hash
     * @param newPromptHash 变更后的 System Prompt SHA-256 hash
     *                      TODO: [预留参数] 当前未使用，未来可能用于查询新增 Skill（新 hash 关联但旧 hash 不关联的 Skill）
     * @return 分析结果（含冷启动提示 / 受影响 Skill + 测试用例）
     */
    public AnalysisResult analyzeChange(String oldPromptHash, String newPromptHash) {
        // 1. 查询直接受影响的 Skill
        Set<String> directSkills = repository.findSkillIdsByPromptHash(oldPromptHash);

        // 2. 冷启动检测
        if (directSkills.isEmpty()) {
            List<SkillProfile> allSkills = repository.findAllSkills();
            if (allSkills.isEmpty()) {
                return AnalysisResult.noBaseline(
                    "未录制到任何交互数据。请先运行 Agent 积累交互数据，框架将自动建立基线。");
            } else {
                return AnalysisResult.noBaseline(
                    "未找到使用此 Prompt hash 的 Skill。可能是新 Prompt 或 hash 不匹配。");
            }
        }

        // 3. 图遍历下游依赖
        Set<String> allAffectedSkills = new HashSet<>(directSkills);
        for (String skill : directSkills) {
            allAffectedSkills.addAll(graph.traverseDownstream(skill));
        }

        // 4. 自适应测试密度
        List<InteractionRecord> testCases = selectTestCases(directSkills, allAffectedSkills);

        return new AnalysisResult(directSkills, allAffectedSkills, testCases);
    }

    /**
     * 根据受影响 Skill 数量决定测试密度。
     * 全局 Prompt（10+ Skill 共享）：每 Skill 采样 top 3 条。
     * 局部 Prompt（1-9 Skill）：全部测试。
     */
    private List<InteractionRecord> selectTestCases(Set<String> directSkills,
                                                     Set<String> allAffectedSkills) {
        List<InteractionRecord> testCases = new ArrayList<>();

        if (directSkills.size() >= GLOBAL_PROMPT_THRESHOLD) {
            // 全局 Prompt：采样策略
            for (String skill : allAffectedSkills) {
                List<InteractionRecord> records = repository.findBySkillId(skill);
                int limit = Math.min(GLOBAL_SAMPLE_PER_SKILL, records.size());
                testCases.addAll(records.subList(0, limit));
            }
        } else {
            // 局部 Prompt：全量测试
            for (String skill : allAffectedSkills) {
                testCases.addAll(repository.findBySkillId(skill));
            }
        }

        return testCases;
    }
}
