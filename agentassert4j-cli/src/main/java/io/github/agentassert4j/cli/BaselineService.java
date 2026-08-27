package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.DeterministicSkillGrouper;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;

import java.io.PrintStream;
import java.util.List;

/**
 * 基线建立服务 — baseline 命令与 replay 前置步骤共用的落基线逻辑。
 *
 * <p>按 skillId 遍历已录制交互（存储返回规范序），逐条调用幂等的
 * autoEstablishBaseline：首个基线由该 skill 最早的交互建立，已存在基线不覆盖。
 * 重复执行安全——画像属于可从 interactions 重建的派生数据。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public class BaselineService {

    private final StorageRepository repository;

    public BaselineService(StorageRepository repository) {
        this.repository = repository;
    }

    /**
     * 为所有已录制且尚无基线的 skill 建立基线。
     *
     * @param out 报告输出流
     * @return 本次新建基线的 skill 数
     */
    public int establishMissing(PrintStream out) {
        BaselineManager manager = new BaselineManager(repository);
        int established = 0;

        for (String skillId : CliSupport.recordedSkillIds(repository)) {
            String groupKey = groupKeyOfFirstRecord(skillId);
            if (groupKey == null) {
                continue;
            }
            SkillProfile existing = repository.findSkillByGroupKey(groupKey);
            boolean hadBaseline = existing != null && existing.getFingerprint() != null;

            List<InteractionRecord> records = repository.findBySkillId(skillId);
            for (InteractionRecord record : records) {
                manager.autoEstablishBaseline(record);
            }

            if (hadBaseline) {
                out.println("  " + skillId + " → " + groupKey + ": 基线已存在（" + existing.getVersionTag() + "）");
            } else {
                established++;
                // 首条记录建立画像时 totalRecords=1，回填该 skill 的真实记录数
                SkillProfile created = repository.findSkillByGroupKey(groupKey);
                if (created != null) {
                    created.setTotalRecords(records.size());
                    repository.saveSkillProfile(created);
                }
                out.println("  " + skillId + " → " + groupKey + ": 新建基线");
            }
        }
        return established;
    }

    /**
     * 该业务标签下首条记录（存储规范序）对应的分组键；无记录返回 null。
     */
    String groupKeyOfFirstRecord(String skillId) {
        List<InteractionRecord> records = repository.findBySkillId(skillId);
        if (records.isEmpty()) {
            return null;
        }
        return DeterministicSkillGrouper.group(records.get(0)).getGroupKey();
    }
}
