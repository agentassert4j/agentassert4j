package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.InvocationResolver;
import io.github.agentassert4j.algorithm.VersionMismatchException;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.spi.StorageRepository;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @param out             报告输出流
     * @param actor           操作者身份（审批留痕）
     * @param codeRef         代码锚（申报制：随基线留痕，空缺合法）
     * @param force           以当前判定语义重建基线：已有基线也被当前算法新指纹覆盖
     *                        （判定语义版本升级后的恢复路径），版本标签按归档占用顺延
     * @param invocationKeys  仅处理这些调用点键（null = 全部；由 CliSupport 统一解析阶梯
     *                        产出——标签扇出/显示短形/唯一前缀在解析层收敛为键集合，
     *                        匹配职责不残留本层）
     * @param rules           规则配置（维度 3-4 口径，与重放判定同源；null = 无规则）
     * @param outcomes        逐调用点结果收集（null = 不收集；人类结果行已就地打印，
     *                        明细供 --json 报告组装）
     * @param expectedVersion 乐观并发守卫的期望活跃版本标签，null = 不设守卫
     * @return 本次新建/重建基线的分组数
     */
    public int establishMissing(PrintStream out, String actor, String codeRef, boolean force, Set<String> invocationKeys, InvocationRulesConfig rules, List<BaselineOutcome> outcomes, String expectedVersion) {
        BaselineManager manager = new BaselineManager(repository);
        int established = 0;

        for (Map.Entry<String, List<InteractionRecord>> bucket : CliSupport.invocationBuckets(repository).entrySet()) {
            String invocationKey = bucket.getKey();
            List<InteractionRecord> records = bucket.getValue();
            if (invocationKeys != null && !invocationKeys.contains(invocationKey)) {
                continue;
            }

            InvocationProfile existing = repository.findInvocationByKey(invocationKey);
            boolean hadBaseline = existing != null && existing.getFingerprint() != null;
            if (hadBaseline && !force) {
                out.println("  " + displayLabel(records) + invocationKey + ": baseline exists (" + existing.getVersionTag() + ")" + refSuffix(existing.getCodeRef()));
                warnRulesDrift(out, firstBusinessLabel(records), existing.getFingerprint(), rules);
                if (outcomes != null) {
                    outcomes.add(new BaselineOutcome(invocationKey, firstBusinessLabel(records), "exists", existing.getVersionTag(), existing.getCodeRef()));
                }
                continue;
            }

            if (force) {
                if (hadBaseline) {
                    // 破坏性操作必须留痕：被覆盖的旧基线进入归档，rollback 可恢复
                    if (expectedVersion != null && !expectedVersion.equals(existing.getVersionTag())) {
                        throw new VersionMismatchException("Invocation " + invocationKey + " active baseline is " + existing.getVersionTag() + ", not the expected " + expectedVersion + "; a concurrent actor may have changed it.");
                    }
                    out.println("  Warning: existing baseline " + existing.getVersionTag() + (existing.getApprovedBy() != null ? " (approved by " + existing.getApprovedBy() + ")" : "") + " of " + invocationKey + " will be rebuilt under the current semantics; the old baseline is archived and restorable via `rollback`.");
                }
                // 重建取桶内规范序首条可分组记录（分桶已剔除不可分组记录）；
                // 逐条调用会让版本标签随记录数连跳
                manager.reestablishBaseline(records.get(0), actor, rules, codeRef);
            } else {
                for (InteractionRecord record : records) {
                    try {
                        manager.autoEstablishBaseline(record, actor, rules, codeRef);
                    } catch (RuntimeException e) {
                        // 单条建档失败（存储抖动等）不中断整批——与录制 enrich 的
                        // 单条容错同哲学；分桶已剔除不可分组记录，这里只剩存储面故障
                    }
                }
            }

            // 落库回验：建档路径吞掉单条存储故障（不中断整批），但全失败时
            // 画像不存在——此时不得上报「已建立」的假成功、不得计入计数
            InvocationProfile created = repository.findInvocationByKey(invocationKey);
            if (created == null || created.getFingerprint() == null) {
                out.println("  " + displayLabel(records) + invocationKey + ": baseline establishment failed (storage error; see storage logs)");
                if (outcomes != null) {
                    outcomes.add(new BaselineOutcome(invocationKey, firstBusinessLabel(records), "failed", null, null));
                }
                continue;
            }
            established++;
            // 首条记录建立画像时 totalRecords=1，回填该分组的真实记录数
            created.setTotalRecords(records.size());
            repository.saveInvocationProfile(created);
            out.println("  " + displayLabel(records) + invocationKey + ": " + (hadBaseline ? "baseline re-established under the current judgment semantics (" + created.getVersionTag() + ")" : "baseline established") + " (seed record " + records.get(0).getRecordId() + ")" + refSuffix(created.getCodeRef()));
            if (outcomes != null) {
                outcomes.add(new BaselineOutcome(invocationKey, firstBusinessLabel(records), hadBaseline ? "reestablished" : "created", created.getVersionTag(), created.getCodeRef()));
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
                violations.add("missing required keyword '" + keyword + "'");
            }
        }
        for (String keyword : rule.getForbiddenKeywords()) {
            if (response.contains(keyword)) {
                violations.add("forbidden keyword '" + keyword + "' present");
            }
        }
        if (rule.getRegexPatterns() != null) {
            for (RegexPattern pattern : rule.getRegexPatterns()) {
                if (!pattern.matches(response)) {
                    violations.add("regex '" + pattern.getPattern() + "' not matched");
                }
            }
        }
        if (!violations.isEmpty()) {
            out.println("  Warning: the seed record violates the declared rules (every replay of this group will flag content-rule differences; check whether the rules declaration is too narrow):");
            for (String violation : violations) {
                out.println("    - " + violation);
            }
        }
    }

    /**
     * 已存在基线与当前规则文件的声明差异告警：establish 对既有基线是幂等 no-op，
     * 不会把规则文件里的新声明刷进指纹——差异静默时用户以为「已 establish = 已刷新」。
     * 指路两条刷新路径：check 后 accept（用当前规则落候选再升格，不动种子）或
     * --force（连种子一起从桶内最早记录重播）。
     */
    private static void warnRulesDrift(PrintStream out, String label, DeterministicFingerprint fingerprint, InvocationRulesConfig rules) {
        if (rules == null || !rules.hasRules() || fingerprint == null) {
            return;
        }
        InvocationRulesConfig.InvocationRule rule = rules.getRulesForInvocation(label);
        String pinned = declarationDescription(fingerprint.getRequiredKeywords(), fingerprint.getForbiddenKeywords(), fingerprint.getRegexPatterns(), fingerprint.getDeclaredBehaviors());
        String file = declarationDescription(rule.getRequiredKeywords(), rule.getForbiddenKeywords(), rule.getRegexPatterns(), rule.getBehaviors());
        if (pinned.equals(file)) {
            return;
        }
        out.println("  Warning: rules declarations for " + label + " differ from the ones pinned in this baseline (pinned " + pinned + " | file " + file + ").");
        out.println("    establish does not refresh them: run `replay --ci` with this rules file in place and accept the candidate it lands (no re-seed), or use `--force` (re-seeds from the earliest record in the bucket).");
    }

    /**
     * 规则声明的紧凑描述（告警用，两侧同形才可比较）。
     */
    private static String declarationDescription(Set<String> required, Set<String> forbidden, List<RegexPattern> regex, Set<String> behaviors) {
        StringBuilder sb = new StringBuilder("required=").append(sorted(required));
        sb.append(", forbidden=").append(sorted(forbidden));
        sb.append(", regex=").append(regex == null || regex.isEmpty() ? "[]" : regex.toString());
        sb.append(", behaviors=").append(sorted(behaviors));
        return sb.toString();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<>(values != null ? values : Collections.<String>emptySet());
        Collections.sort(list);
        return list;
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
     * 人读行的申报锚后缀：只回显已落库的锚（真源在画像行），调用方声明值
     * 未经空白归一前不进输出。
     */
    private static String refSuffix(String codeRef) {
        return codeRef != null ? " (ref " + codeRef + ")" : "";
    }

    /**
     * 单个调用点的建档结果 — 逐调用点 JSON 报告字段（人类结果行已就地打印）。
     * 仅同包建档命令消费，不对外承诺。
     */
    static final class BaselineOutcome {

        private final String invocationKey;
        private final String label;
        private final String action;
        private final String versionTag;
        private final String codeRef;

        BaselineOutcome(String invocationKey, String label, String action, String versionTag, String codeRef) {
            this.invocationKey = invocationKey;
            this.label = label;
            this.action = action;
            this.versionTag = versionTag;
            this.codeRef = codeRef;
        }

        String getInvocationKey() {
            return invocationKey;
        }

        String getLabel() {
            return label;
        }

        String getAction() {
            return action;
        }

        String getVersionTag() {
            return versionTag;
        }

        String getCodeRef() {
            return codeRef;
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
