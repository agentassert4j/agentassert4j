package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.result.DriftReport;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.*;

/**
 * 模板漂移检测器 — 全库画像只读巡检「画像模板身份 vs 最新记录模板身份」。
 *
 * <p>检测按画像对照关系覆盖两种漂移：同键漂移（画像键下最新可分组记录的模板哈希与画像
 * 不一致，动态模板下全文变更不裂键，新老哈希同键共存）；标签裂键（声明标签的调用点在
 * 全文变更后裂出未建档新键，其声明标签与既有画像标签相同）。无画像对照的全新键不进
 * 漂移集——无对照即无漂移语义，由建档路径与巡检视图承接。</p>
 *
 * <p>记录凭据口径：存储键与现算键一致的记录才可作为身份凭据（单条损坏或键不一致的
 * 记录倒序回退跳过）；最新可分组记录无模板哈希时该画像记为零模板点排除出检测集——
 * 无模板身份即无漂移语义。单键查询失败安全跳过并计数，不中断巡检。比较两侧都是
 * 显式携带的哈希字段，严格相等，无模糊匹配。</p>
 *
 * @author axy-yxa
 * @since 2026-09-03
 */
public final class DriftDetector {

    private DriftDetector() {
    }

    /**
     * 巡检全库画像漂移，并经依赖图把漂移键扩散为下游波及集。
     *
     * @param repository 存储仓库（只读消费）
     * @param graph      依赖图（调用方负责其新鲜度；为 null 或图为空时下游波及集为空）
     * @return 结构化漂移报告（漂移点按键升序，下游波及键升序且不含漂移键自身）
     */
    public static DriftReport detect(StorageRepository repository, InMemoryDependencyGraph graph) {
        DriftReport report = new DriftReport();

        List<InvocationProfile> profiles;
        try {
            profiles = repository.findAllInvocations();
        } catch (RuntimeException e) {
            report.setSkippedQueries(1);
            return report;
        }

        Set<String> profileKeys = new HashSet<>();
        Set<String> declaredLabels = new HashSet<>();
        for (InvocationProfile profile : profiles) {
            if (profile.getInvocationKey() != null) {
                profileKeys.add(profile.getInvocationKey());
            }
            if (profile.getLabel() != null && !profile.getLabel().isEmpty()) {
                declaredLabels.add(profile.getLabel());
            }
        }

        Set<String> driftedKeys = new LinkedHashSet<>();
        for (InvocationProfile profile : profiles) {
            String key = profile.getInvocationKey();
            if (key == null) {
                continue;
            }
            List<InteractionRecord> records;
            try {
                records = repository.findByInvocationKey(key);
            } catch (RuntimeException e) {
                report.setSkippedQueries(report.getSkippedQueries() + 1);
                continue;
            }
            InteractionRecord anchor = latestIdentityRecord(records, key);
            if (anchor == null || anchor.getTemplateHash() == null || anchor.getTemplateHash().isEmpty()) {
                report.getZeroTemplateKeys().add(key);
                continue;
            }
            if (!anchor.getTemplateHash().equals(profile.getTemplateHash())) {
                DriftReport.DriftPoint point = new DriftReport.DriftPoint();
                point.setInvocationKey(key);
                point.setLabel(profile.getLabel());
                point.setProfileTemplateHash(profile.getTemplateHash());
                point.setLatestTemplateHash(anchor.getTemplateHash());
                report.getSameKeyDrifts().add(point);
                driftedKeys.add(key);
            }
        }

        for (String label : declaredLabels) {
            List<InteractionRecord> records;
            try {
                records = repository.findByInvocationId(label);
            } catch (RuntimeException e) {
                report.setSkippedQueries(report.getSkippedQueries() + 1);
                continue;
            }
            // 同标签下未建档的键 = 全文变更裂出的新细分键；同键多条记录取最新为投影
            Map<String, List<InteractionRecord>> unestablished = new LinkedHashMap<>();
            for (InteractionRecord record : records) {
                String key = record.getInvocationKey();
                if (key == null || profileKeys.contains(key)) {
                    continue;
                }
                unestablished.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }
            for (Map.Entry<String, List<InteractionRecord>> entry : unestablished.entrySet()) {
                InteractionRecord anchor = latestIdentityRecord(entry.getValue(), entry.getKey());
                DriftReport.DriftPoint point = new DriftReport.DriftPoint();
                point.setInvocationKey(entry.getKey());
                point.setLabel(label);
                point.setLatestTemplateHash(anchor != null ? anchor.getTemplateHash() : null);
                report.getLabelSplits().add(point);
            }
        }

        Set<String> downstream = new TreeSet<>();
        if (graph != null) {
            for (String key : driftedKeys) {
                try {
                    downstream.addAll(graph.traverseDownstream(key));
                } catch (RuntimeException e) {
                    report.setSkippedQueries(report.getSkippedQueries() + 1);
                }
            }
        }
        downstream.removeAll(driftedKeys);
        report.getDownstreamKeys().addAll(downstream);

        report.getSameKeyDrifts().sort(Comparator.comparing(DriftReport.DriftPoint::getInvocationKey));
        report.getLabelSplits().sort(Comparator.comparing(DriftReport.DriftPoint::getInvocationKey));
        return report;
    }

    /**
     * 键桶内最新可分组记录：按规范序（时间、序号、记录 ID）倒序扫描，返回首个现算键
     * 与存储键一致的记录。单条损坏或键不一致即跳过回退；全部不可用返回 null。
     * 返回记录的模板哈希可为 null（零模板点），由调用方决定保守语义。
     * 检测、治理身份前移与重驱取点共用本口径（存储键×现算键双一致才可作身份凭据）。
     */
    public static InteractionRecord latestIdentityRecord(List<InteractionRecord> records, String expectedKey) {
        List<InteractionRecord> ordered = new ArrayList<>(records);
        ordered.sort(canonicalOrder());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            InteractionRecord record = ordered.get(i);
            String resolvedKey;
            try {
                resolvedKey = InvocationResolver.resolve(record).getInvocationKey();
            } catch (RuntimeException e) {
                continue;
            }
            if (resolvedKey.equals(expectedKey)) {
                return record;
            }
        }
        return null;
    }

    /**
     * 存储查询的确定性排序口径（时间 → 序号 → 记录 ID），与本仓 SQLite 读侧一致；
     * 核心算法不依赖各存储实现对排序的自觉
     */
    static Comparator<InteractionRecord> canonicalOrder() {
        return Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparingLong(InteractionRecord::getSeq).thenComparing(r -> r.getRecordId() != null ? r.getRecordId() : "");
    }
}
