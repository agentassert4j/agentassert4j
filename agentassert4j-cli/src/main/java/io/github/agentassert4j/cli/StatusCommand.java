package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * status 命令 — 查看已录制 Skill 与基线状态（裁决前后的巡检入口）。
 *
 * <p>groupKey 是 skill 的稳定标识（分组器确定性产出），approve/reject 的
 * --skill 以它（或其唯一前缀）为目标。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "status", description = "查看已录制 Skill 与基线状态")
public class StatusCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            List<SkillProfile> profiles = repository.findAllSkills();

            System.out.println("groupKey                                              状态       版本   候选");
            for (SkillProfile profile : profiles) {
                System.out.printf("  %-50s %-9s %-6s %s%n", profile.getGroupKey(), String.valueOf(profile.getBaselineStatus()), String.valueOf(profile.getVersionTag()), profile.getCandidateFingerprint() != null ? "有" : "-");
            }

            List<String> uncovered = uncoveredBusinessTags(repository, profiles);
            for (String tag : uncovered) {
                System.out.println("  " + tag + ": 已录制但无基线（先执行 baseline）");
            }
            System.out.println("共 " + profiles.size() + " 个基线画像。");
            printGraphSnapshot(repository);
            return 0;
        } catch (RuntimeException e) {
            System.err.println("status 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 已录制业务标签中尚无对应基线画像的（记录标签 → 分组 → 画像缺失）。
     */
    private static List<String> uncoveredBusinessTags(StorageRepository repository, List<SkillProfile> profiles) {
        List<String> uncovered = new ArrayList<>();
        for (String skillId : CliSupport.recordedSkillIds(repository)) {
            String groupKey = new BaselineService(repository).groupKeyOfFirstRecord(skillId);
            if (groupKey == null) {
                continue;
            }
            boolean covered = false;
            for (SkillProfile profile : profiles) {
                if (groupKey.equals(profile.getGroupKey())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(skillId);
            }
        }
        return uncovered;
    }

    /**
     * 依赖图快照巡检：快照是最近一次 replay 的分析视图留档（本命令只读不重建，
     * 看实时图用 graph show）。
     */
    private static void printGraphSnapshot(StorageRepository repository) {
        String json = null;
        try {
            json = repository.loadGraph();
        } catch (RuntimeException e) {
            // 快照缺席不阻断状态巡检
        }
        if (json == null || json.trim().isEmpty()) {
            System.out.println("依赖图：无快照（执行 replay 后生成；实时视图用 graph show）。");
            return;
        }
        InMemoryDependencyGraph graph = InMemoryDependencyGraph.fromJson(json);
        System.out.println("依赖图快照：" + graph.nodeCount() + " 节点 / " + graph.edgeCount() + " 边（最近一次 replay 生成；实时视图用 graph show）");
    }
}
