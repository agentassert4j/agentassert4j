package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageException;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 增量测试筛选 — 数据驱动的变更影响分析。
 *
 * <p>测试范围由实际数据决定：每条 InteractionRecord 都存储 templateHash，
 * 变更影响面 = 使用旧 Prompt hash 的调用点及其在依赖图上的下游，
 * 全局 Prompt（多数调用点共享）采样、局部 Prompt 全量。
 * 声明与否共用同一身份空间（invocationKey），分析路径不分叉。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ImpactAnalyzer {

    /**
     * 全局/局部 Prompt 阈值：共享调用点 >= 此值视为全局 Prompt，启用采样
     */
    static final int GLOBAL_PROMPT_THRESHOLD = 10;

    /**
     * 全局 Prompt 时每个调用点采样的最大用例数
     */
    static final int GLOBAL_SAMPLE_PER_INVOCATION = 3;

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
     *                      新增调用点检测：新 hash 关联但旧 hash 不关联的调用点）
     * @return 分析结果（含冷启动提示 / 受影响调用点 + 测试用例）
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
        // 1. 查询直接受影响的调用点（键空间统一，声明与否同路）
        Set<String> directInvocations = repository.findInvocationKeysByTemplateHash(oldPromptHash);
        // 空串不是合法调用点键（防御存储层 null→"" 变形）
        directInvocations.remove("");

        // 2. 冷启动检测
        if (directInvocations.isEmpty()) {
            List<InvocationProfile> allInvocations = repository.findAllInvocations();
            if (allInvocations.isEmpty()) {
                return AnalysisResult.noBaseline("未录制到任何交互数据。请先运行 Agent 积累交互数据，框架将自动建立基线。");
            } else {
                return AnalysisResult.noBaseline("未找到使用此 Prompt hash 的调用点。可能是新 Prompt 或 hash 不匹配。");
            }
        }

        // 3. 图遍历下游依赖
        Set<String> allAffectedInvocations = new HashSet<>(directInvocations);
        for (String invocation : directInvocations) {
            allAffectedInvocations.addAll(graph.traverseDownstream(invocation));
        }

        // 4. 自适应测试密度
        List<InteractionRecord> testCases = selectTestCases(directInvocations, allAffectedInvocations);

        return new AnalysisResult(directInvocations, allAffectedInvocations, testCases);
    }

    /**
     * 根据受影响调用点数量决定测试密度。
     * 全局 Prompt（10+ 调用点共享）：每调用点采样 top 3 条。
     * 局部 Prompt（1-9 调用点）：全部测试。
     */
    private List<InteractionRecord> selectTestCases(Set<String> directInvocations, Set<String> allAffectedInvocations) {
        List<InteractionRecord> testCases = new ArrayList<>();

        if (directInvocations.size() >= GLOBAL_PROMPT_THRESHOLD) {
            // 全局 Prompt：采样策略
            for (String invocation : allAffectedInvocations) {
                List<InteractionRecord> records = repository.findByInvocationKey(invocation);
                // 存储查询自带确定性排序，top3 采样直接取规范序前缀
                // （timestamp + recordId 平局决胜）的前缀，两次分析选例才一致
                List<InteractionRecord> ordered = records.stream().sorted(Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : "")).collect(Collectors.toList());
                int limit = Math.min(GLOBAL_SAMPLE_PER_INVOCATION, ordered.size());
                testCases.addAll(ordered.subList(0, limit));
            }
        } else {
            // 局部 Prompt：全量测试
            for (String invocation : allAffectedInvocations) {
                testCases.addAll(repository.findByInvocationKey(invocation));
            }
        }

        return testCases;
    }
}
