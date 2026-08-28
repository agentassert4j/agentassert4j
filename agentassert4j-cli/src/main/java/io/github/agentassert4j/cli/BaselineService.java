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
     * @param out   报告输出流
     * @param actor 操作者身份（审批留痕）
     * @param force 以当前判定语义重建基线：已有基线也被当前算法新指纹覆盖
     *              （判定语义版本升级后的恢复路径），版本标签按归档占用顺延
     * @return 本次新建/重建基线的 skill 数
     */
    public int establishMissing(PrintStream out, String actor, boolean force) {
        BaselineManager manager = new BaselineManager(repository);
        int established = 0;

        for (String skillId : CliSupport.recordedSkillIds(repository)) {
            String groupKey = groupKeyOfFirstRecord(skillId);
            if (groupKey == null) {
                continue;
            }
            SkillProfile existing = repository.findSkillByGroupKey(groupKey);
            boolean hadBaseline = existing != null && existing.getFingerprint() != null;
            if (hadBaseline && !force) {
                out.println("  " + skillId + " → " + groupKey + ": 基线已存在（" + existing.getVersionTag() + "）");
                continue;
            }

            List<InteractionRecord> records = repository.findBySkillId(skillId);
            if (force) {
                if (hadBaseline) {
                    // 破坏性操作必须留痕：被覆盖的旧基线进入归档，rollback 可恢复
                    out.println("  警告：skill " + skillId + " 的既有基线 " + existing.getVersionTag() + "（审批人 " + existing.getApprovedBy() + "）将被当前语义重建覆盖，旧基线已归档、可用 rollback 恢复。");
                }
                // 重建只需任一该 skill 的可分组录制（取存储规范序，与首次建立的取材一致）；
                // 逐条调用会让版本标签随记录数连跳
                InteractionRecord material = firstGroupableRecord(records);
                if (material != null) {
                    manager.reestablishBaseline(material, actor);
                }
            } else {
                for (InteractionRecord record : records) {
                    try {
                        manager.autoEstablishBaseline(record, actor);
                    } catch (RuntimeException e) {
                        // 单条分组失败不拦截其余记录——与录制 enrich 的单条容错同哲学，
                        // 无法归组的记录由重放守卫统一告警并剔除
                    }
                }
            }

            established++;
            // 首条记录建立画像时 totalRecords=1，回填该 skill 的真实记录数
            SkillProfile created = repository.findSkillByGroupKey(groupKey);
            if (created != null) {
                created.setTotalRecords(records.size());
                repository.saveSkillProfile(created);
            }
            out.println("  " + skillId + " → " + groupKey + ": " + (hadBaseline ? "已按当前判定语义重建基线（" + created.getVersionTag() + "）" : "新建基线"));
        }
        return established;
    }

    /**
     * 该业务标签下首条可分组记录（存储规范序）对应的分组键；无可分组记录返回 null。
     */
    String groupKeyOfFirstRecord(String skillId) {
        List<InteractionRecord> records = repository.findBySkillId(skillId);
        InteractionRecord first = firstGroupableRecord(records);
        return first != null ? DeterministicSkillGrouper.group(first).getGroupKey() : null;
    }

    /**
     * 返回列表中第一条能被分组器处理的记录——个别损坏记录（如工具名缺失）
     * 跳过处理，不让单条数据问题中断整个 skill 的建档。
     */
    private static InteractionRecord firstGroupableRecord(List<InteractionRecord> records) {
        for (InteractionRecord record : records) {
            try {
                DeterministicSkillGrouper.group(record);
                return record;
            } catch (RuntimeException e) {
                // 单条分组失败，试下一条
            }
        }
        return null;
    }
}
