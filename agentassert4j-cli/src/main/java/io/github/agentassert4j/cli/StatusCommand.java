package io.github.agentassert4j.cli;

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
}
