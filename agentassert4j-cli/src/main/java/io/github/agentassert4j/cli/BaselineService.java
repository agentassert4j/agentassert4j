package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.InvocationResolver;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.spi.StorageRepository;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基线建立服务 — baseline 命令与 replay 前置步骤共用的落基线逻辑。
 *
 * <p>按 invocationId 遍历已录制交互（存储返回规范序），逐条调用幂等的
 * autoEstablishBaseline：首个基线由该 调用点 最早的交互建立，已存在基线不覆盖。
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
     * 为已录制且尚无基线的分组建立基线。
     *
     * @param out              报告输出流
     * @param actor            操作者身份（审批留痕）
     * @param force            以当前判定语义重建基线：已有基线也被当前算法新指纹覆盖
     *                         （判定语义版本升级后的恢复路径），版本标签按归档占用顺延
     * @param invocationFilter 仅处理该业务 invocationId 或分组键前缀（null = 全部；调用方经
     *                         CliSupport 预解析，invocationKey 前缀已在解析层换算成业务标签）
     * @param rules            规则配置（维度 3-4 口径，与重放判定同源；null = 无规则）
     * @return 本次新建/重建基线的分组数
     */
    public int establishMissing(PrintStream out, String actor, boolean force, String invocationFilter, InvocationRulesConfig rules) {
        return establishMissing(out, actor, force, invocationFilter, rules, null);
    }

    /**
     * 同上，另把逐调用点结果收集进 outcomes（null = 不收集）——
     * 人类结果行已就地打印，明细供 --json 报告组装。
     */
    public int establishMissing(PrintStream out, String actor, boolean force, String invocationFilter, InvocationRulesConfig rules, List<BaselineOutcome> outcomes) {
        BaselineManager manager = new BaselineManager(repository);
        int established = 0;

        for (Map.Entry<String, List<InteractionRecord>> bucket : CliSupport.invocationBuckets(repository).entrySet()) {
            String invocationKey = bucket.getKey();
            List<InteractionRecord> records = bucket.getValue();
            if (invocationFilter != null && !bucketCoversFilter(records, invocationKey, invocationFilter)) {
                continue;
            }

            InvocationProfile existing = repository.findInvocationByKey(invocationKey);
            boolean hadBaseline = existing != null && existing.getFingerprint() != null;
            if (hadBaseline && !force) {
                out.println("  " + displayLabel(records) + invocationKey + ": 基线已存在（" + existing.getVersionTag() + "）");
                if (outcomes != null) {
                    outcomes.add(new BaselineOutcome(invocationKey, firstBusinessLabel(records), "exists", existing.getVersionTag()));
                }
                continue;
            }

            if (force) {
                if (hadBaseline) {
                    // 破坏性操作必须留痕：被覆盖的旧基线进入归档，rollback 可恢复
                    out.println("  警告：分组 " + invocationKey + " 的既有基线 " + existing.getVersionTag() + "（审批人 " + existing.getApprovedBy() + "）将被当前语义重建覆盖，旧基线已归档、可用 rollback 恢复。");
                }
                // 重建取桶内规范序首条可分组记录（分桶已剔除不可分组记录）；
                // 逐条调用会让版本标签随记录数连跳
                manager.reestablishBaseline(records.get(0), actor, rules);
            } else {
                for (InteractionRecord record : records) {
                    try {
                        manager.autoEstablishBaseline(record, actor, rules);
                    } catch (RuntimeException e) {
                        // 单条建档失败（存储抖动等）不中断整批——与录制 enrich 的
                        // 单条容错同哲学；分桶已剔除不可分组记录，这里只剩存储面故障
                    }
                }
            }

            established++;
            // 首条记录建立画像时 totalRecords=1，回填该分组的真实记录数
            InvocationProfile created = repository.findInvocationByKey(invocationKey);
            if (created != null) {
                created.setTotalRecords(records.size());
                repository.saveInvocationProfile(created);
            }
            out.println("  " + displayLabel(records) + invocationKey + ": " + (hadBaseline ? "已按当前判定语义重建基线（" + created.getVersionTag() + "）" : "新建基线"));
            if (outcomes != null) {
                outcomes.add(new BaselineOutcome(invocationKey, firstBusinessLabel(records), hadBaseline ? "reestablished" : "created", created != null ? created.getVersionTag() : null));
            }
            warnSeedRuleViolations(out, records.get(0), rules);
        }
        return established;
    }

    /**
     * 种子记录对声明规则的现场断言：建档时即验证种子响应满足内容规则声明
     * （必需关键词全含、禁用关键词不出现、正则命中）。违反只告警不阻断——
     * 不满足只说明「该组每次重放都会在内容规则维度判出差异」，是规则声明的
     * 质量问题而非建档故障；建档现场指出它，避免基线建在必然假差异上。
     */
    private static void warnSeedRuleViolations(PrintStream out, InteractionRecord seed, InvocationRulesConfig rules) {
        if (rules == null || !rules.hasRules() || seed == null) {
            return;
        }
        InvocationRulesConfig.InvocationRule rule = rules.getRulesForInvocation(seed.getInvocationId());
        String response = seed.getModelResponse() != null ? seed.getModelResponse() : "";
        List<String> violations = new ArrayList<>();
        for (String keyword : rule.getRequiredKeywords()) {
            if (!response.contains(keyword)) {
                violations.add("缺少必需关键词「" + keyword + "」");
            }
        }
        for (String keyword : rule.getForbiddenKeywords()) {
            if (response.contains(keyword)) {
                violations.add("出现禁用关键词「" + keyword + "」");
            }
        }
        if (rule.getRegexPatterns() != null) {
            for (RegexPattern pattern : rule.getRegexPatterns()) {
                if (!pattern.matches(response)) {
                    violations.add("正则不命中「" + pattern.getPattern() + "」");
                }
            }
        }
        if (!violations.isEmpty()) {
            out.println("  警告：种子记录不满足声明规则（该组后续重放都会在内容规则维度判差异，请检查 rules 声明是否过窄）：");
            for (String violation : violations) {
                out.println("    - " + violation);
            }
        }
    }

    /**
     * 桶是否覆盖过滤值：桶内任一记录的业务标签精确等于过滤值，或分组键以其为前缀。
     */
    private static boolean bucketCoversFilter(List<InteractionRecord> records, String invocationKey, String filter) {
        for (InteractionRecord record : records) {
            if (filter.equals(record.getInvocationId())) {
                return true;
            }
        }
        return invocationKey.startsWith(filter);
    }

    /**
     * 桶内首个非空业务标签（声明组显示人读名，形状组显示为空）。
     */
    private static String displayLabel(List<InteractionRecord> records) {
        String label = firstBusinessLabel(records);
        return label.isEmpty() ? "" : label + " → ";
    }

    private static String firstBusinessLabel(List<InteractionRecord> records) {
        for (InteractionRecord record : records) {
            if (record.getInvocationId() != null && !record.getInvocationId().isEmpty()) {
                return record.getInvocationId();
            }
        }
        return "";
    }

    /**
     * 单个调用点的建档结果 — 逐调用点 JSON 报告字段（人类结果行已就地打印）。
     */
    public static final class BaselineOutcome {

        private final String invocationKey;
        private final String label;
        private final String action;
        private final String versionTag;

        public BaselineOutcome(String invocationKey, String label, String action, String versionTag) {
            this.invocationKey = invocationKey;
            this.label = label;
            this.action = action;
            this.versionTag = versionTag;
        }

        public String getInvocationKey() {
            return invocationKey;
        }

        public String getLabel() {
            return label;
        }

        public String getAction() {
            return action;
        }

        public String getVersionTag() {
            return versionTag;
        }
    }

    /**
     * 该业务标签下首条可分组记录（存储规范序）对应的分组键；无可分组记录返回 null。
     */
    String invocationKeyOfFirstRecord(String invocationId) {
        List<InteractionRecord> records = repository.findByInvocationId(invocationId);
        InteractionRecord first = firstGroupableRecord(records);
        return first != null ? InvocationResolver.resolve(first).getInvocationKey() : null;
    }

    /**
     * 返回列表中第一条能被分组器处理的记录——个别损坏记录（如工具名缺失）
     * 跳过处理，不让单条数据问题中断整个调用点的建档。
     */
    private static InteractionRecord firstGroupableRecord(List<InteractionRecord> records) {
        for (InteractionRecord record : records) {
            try {
                InvocationResolver.resolve(record);
                return record;
            } catch (RuntimeException e) {
                // 单条分组失败，试下一条
            }
        }
        return null;
    }
}
