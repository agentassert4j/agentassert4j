package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageException;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 增量测试筛选 — 数据驱动的变更影响分析。
 *
 * <p>测试范围由实际数据决定：每条 InteractionRecord 都存储 templateHash，
 * 变更影响面 = 使用旧 Prompt hash 的 Skill 及其在依赖图上的下游，
 * 全局 Prompt（多数 Skill 共享）采样、局部 Prompt 全量。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ImpactAnalyzer {

    /**
     * 全局/局部 Prompt 阈值：共享 Skill >= 此值视为全局 Prompt，启用采样
     */
    static final int GLOBAL_PROMPT_THRESHOLD = 10;

    /**
     * 全局 Prompt 时每个 Skill 采样的最大用例数
     */
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
     * @param newPromptHash 变更后的 System Prompt SHA-256 hash（当前未消费，预留用于
     *                      新增 Skill 检测：新 hash 关联但旧 hash 不关联的 Skill）
     * @return 分析结果（含冷启动提示 / 受影响 Skill + 测试用例）
     */
    public AnalysisResult analyzeChange(String oldPromptHash, String newPromptHash) {
        // 查询失败与冷启动（合法空数据）必须区分：吞成空集会误导诊断方向
        try {
            return doAnalyze(oldPromptHash);
        } catch (StorageException e) {
            return AnalysisResult.error("影响分析存储查询失败：" + e.getMessage());
        }
    }

    private AnalysisResult doAnalyze(String oldPromptHash) {
        // 1. 查询直接受影响的 Skill
        Set<String> directSkills = repository.findSkillIdsByTemplateHash(oldPromptHash);

        // 未声明记录的 skill_id 列是空串（业务声明位），不能直接当查询键：
        // 按模板 hash 反查记录、以派生画像 id 归并进影响集，形状组才可进图遍历与选例
        Map<String, List<InteractionRecord>> undeclaredByNode = collectUndeclaredByNode(oldPromptHash);
        if (!undeclaredByNode.isEmpty()) {
            directSkills.remove("");
            directSkills.addAll(undeclaredByNode.keySet());
        }

        // 2. 冷启动检测
        if (directSkills.isEmpty()) {
            List<SkillProfile> allSkills = repository.findAllSkills();
            if (allSkills.isEmpty()) {
                return AnalysisResult.noBaseline("未录制到任何交互数据。请先运行 Agent 积累交互数据，框架将自动建立基线。");
            } else {
                return AnalysisResult.noBaseline("未找到使用此 Prompt hash 的 Skill。可能是新 Prompt 或 hash 不匹配。");
            }
        }

        // 3. 图遍历下游依赖
        Set<String> allAffectedSkills = new HashSet<>(directSkills);
        for (String skill : directSkills) {
            allAffectedSkills.addAll(graph.traverseDownstream(skill));
        }

        // 4. 自适应测试密度
        List<InteractionRecord> testCases = selectTestCases(directSkills, allAffectedSkills, undeclaredByNode);

        return new AnalysisResult(directSkills, allAffectedSkills, testCases);
    }

    /**
     * 未声明记录按派生画像 id（sha256(分组键)，与依赖图节点同空间）分桶。
     */
    private Map<String, List<InteractionRecord>> collectUndeclaredByNode(String oldPromptHash) {
        Map<String, List<InteractionRecord>> byNode = new LinkedHashMap<>();
        for (InteractionRecord record : repository.findByTemplateHash(oldPromptHash)) {
            if (record.getSkillId() != null && !record.getSkillId().isEmpty()) {
                continue;
            }
            try {
                String nodeId = DeterministicSkillGrouper.group(record).getSkillId();
                byNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(record);
            } catch (RuntimeException e) {
                // 个别损坏记录（如工具名缺失）跳过，不让单条数据问题中断影响分析
            }
        }
        return byNode;
    }

    /**
     * 根据受影响 Skill 数量决定测试密度。
     * 全局 Prompt（10+ Skill 共享）：每 Skill 采样 top 3 条。
     * 局部 Prompt（1-9 Skill）：全部测试。
     */
    private List<InteractionRecord> selectTestCases(Set<String> directSkills, Set<String> allAffectedSkills, Map<String, List<InteractionRecord>> undeclaredByNode) {
        List<InteractionRecord> testCases = new ArrayList<>();

        if (directSkills.size() >= GLOBAL_PROMPT_THRESHOLD) {
            // 全局 Prompt：采样策略
            for (String skill : allAffectedSkills) {
                List<InteractionRecord> records = recordsForSkill(skill, undeclaredByNode);
                // 存储查询自带确定性排序，top3 采样直接取规范序前缀
                // （timestamp + recordId 平局决胜）的前缀，两次分析选例才一致
                List<InteractionRecord> ordered = records.stream().sorted(Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : "")).collect(Collectors.toList());
                int limit = Math.min(GLOBAL_SAMPLE_PER_SKILL, ordered.size());
                testCases.addAll(ordered.subList(0, limit));
            }
        } else {
            // 局部 Prompt：全量测试
            for (String skill : allAffectedSkills) {
                testCases.addAll(recordsForSkill(skill, undeclaredByNode));
            }
        }

        return testCases;
    }

    /**
     * 影响集节点的记录读取：声明 Skill 走业务标签查询，
     * 未声明派生节点从预分桶读取（它们的 skill_id 列是空串，反查不可达）。
     */
    private List<InteractionRecord> recordsForSkill(String skill, Map<String, List<InteractionRecord>> undeclaredByNode) {
        List<InteractionRecord> undeclared = undeclaredByNode.get(skill);
        return undeclared != null ? undeclared : repository.findBySkillId(skill);
    }
}
