package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * doctor 命令 — 库体检：身份/覆盖/规则三段确定性体检（只读，不判定、不建档）。
 *
 * <p>面向零声明接入与首次建档前的自我检查：哪里值得声明标签/任务键、哪些录制
 * 尚未收编、规则文件配了却没生效。所有建议都来自计数与重复性等确定性事实，
 * 无模糊匹配；本命令是人用工具，退出码恒 0，不承 CI gating 职责。</p>
 *
 * @author axy-yxa
 * @since 2026-09-02
 */
@Command(name = "doctor", description = "库体检：身份/覆盖/规则三段确定性体检（只读，不判定不建档）", mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    private static final int MAX_SAMPLES = 3;

    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, out);
            List<InteractionRecord> records = allRecords(repository);
            List<TaskChain> chains = TaskChainView.resolveAll(repository);
            List<InvocationProfile> profiles = repository.findAllInvocations();
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();

            printIdentitySection(records, chains);
            printCoverageSection(repository, profiles, records);
            printRulesSection(rules, chains);
            return 0;
        } catch (RuntimeException e) {
            err.println("doctor 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 身份段：骨架族形态、多步零标签链、重复请求文本任务族——零声明接入获得
     * 框架价值前需要补的声明，都在这里以确定性事实呈现。
     */
    private void printIdentitySection(List<InteractionRecord> records, List<TaskChain> chains) {
        out.println("身份体检：");
        Map<String, Set<String>> variantsBySkeleton = new LinkedHashMap<>();
        Map<String, Integer> recordsBySkeleton = new LinkedHashMap<>();
        for (InteractionRecord record : records) {
            String skeleton = record.getSkeletonHash();
            if (skeleton == null || skeleton.isEmpty()) {
                continue;
            }
            recordsBySkeleton.merge(skeleton, 1, Integer::sum);
            if (record.getTemplateHash() != null && !record.getTemplateHash().isEmpty()) {
                variantsBySkeleton.computeIfAbsent(skeleton, k -> new LinkedHashSet<>()).add(record.getTemplateHash());
            }
        }
        if (recordsBySkeleton.isEmpty()) {
            out.println("  无骨架声明记录——动态模板场景建议在录制出口声明 templateSkeleton（见 OPERATIONS 最小录制契约）。");
        } else {
            for (Map.Entry<String, Integer> entry : recordsBySkeleton.entrySet()) {
                Set<String> variants = variantsBySkeleton.getOrDefault(entry.getKey(), new LinkedHashSet<>());
                out.println("  " + CliSupport.displayKey("skeleton:" + entry.getKey()) + "：记录 " + entry.getValue() + " 条，全文变体 " + variants.size() + " 个");
            }
        }

        List<TaskChain> unlabeledMultiStep = new ArrayList<>();
        for (TaskChain chain : chains) {
            boolean anyLabel = false;
            for (InteractionRecord record : chain.getRecords()) {
                if (record.getInvocationId() != null && !record.getInvocationId().isEmpty()) {
                    anyLabel = true;
                    break;
                }
            }
            if (!anyLabel && chain.getRecords().size() > 1) {
                unlabeledMultiStep.add(chain);
            }
        }
        if (unlabeledMultiStep.isEmpty()) {
            out.println("  多步零标签链：无。");
        } else {
            out.println("  多步零标签链 " + unlabeledMultiStep.size() + " 条——逐步可见性与任务规则都依赖调用点标签（invocationId），建议为关键调用点建立标签词汇表：");
            for (TaskChain chain : samples(unlabeledMultiStep)) {
                out.println("    「" + CliSupport.visibleText(chain.getRequestText()) + "」（session " + chain.getSessionId() + "，" + chain.getRecords().size() + " 步）");
            }
        }

        Map<String, Set<String>> sessionsByRequest = new LinkedHashMap<>();
        for (TaskChain chain : chains) {
            sessionsByRequest.computeIfAbsent(chain.getRequestText(), k -> new LinkedHashSet<>()).add(chain.getSessionId());
        }
        List<String> repeatedFamilies = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : sessionsByRequest.entrySet()) {
            if (entry.getValue().size() >= 2) {
                repeatedFamilies.add(entry.getKey());
            }
        }
        if (repeatedFamilies.isEmpty()) {
            out.println("  重复请求文本任务族：无（同请求多会话重复出现的任务适合声明 taskKey，规则精修只对声明任务生效）。");
        } else {
            out.println("  重复请求文本任务族 " + repeatedFamilies.size() + " 个——建议声明 taskKey（规则精修与跨会话配对的前置）：");
            for (String request : samples(repeatedFamilies)) {
                out.println("    「" + CliSupport.visibleText(request) + "」出现于 " + sessionsByRequest.get(request).size() + " 个会话");
            }
        }
    }

    /**
     * 覆盖段：未收编录制与哈希投影缺口。
     */
    private void printCoverageSection(StorageRepository repository, List<InvocationProfile> profiles, List<InteractionRecord> records) {
        out.println("覆盖体检：");
        List<CliSupport.InvocationFootprint> unestablished = new ArrayList<>();
        Set<String> established = new HashSet<>();
        for (InvocationProfile profile : profiles) {
            established.add(profile.getInvocationKey());
        }
        for (CliSupport.InvocationFootprint footprint : CliSupport.recordedInvocationFootprints(repository)) {
            if (!established.contains(footprint.invocationKey)) {
                unestablished.add(footprint);
            }
        }
        if (unestablished.isEmpty()) {
            out.println("  未建档调用点：无。");
        } else {
            out.println("  未建档调用点 " + unestablished.size() + " 个（重跑 baseline 收编）：");
            for (CliSupport.InvocationFootprint footprint : samples(unestablished)) {
                out.println("    " + CliSupport.displayKey(footprint.invocationKey) + "（" + (footprint.label != null ? footprint.label : "无标签") + "）记录 " + footprint.recordCount + " 条");
            }
        }
        int missingTemplateHash = 0;
        for (InteractionRecord record : records) {
            if (record.getTemplateHash() == null || record.getTemplateHash().isEmpty()) {
                missingTemplateHash++;
            }
        }
        out.println("  template_hash 缺失记录：" + missingTemplateHash + " 条" + (missingTemplateHash > 0 ? "——这些记录缺全文归档与全文门控凭据（重录可补齐）。" : "。"));
    }

    /**
     * 规则段：解析注记回放与期望错位（配了键但库内从未出现声明链）。
     */
    private void printRulesSection(InvocationRulesConfig rules, List<TaskChain> chains) {
        out.println("规则体检：");
        CliSupport.warnMalformedTaskRules(rules, out);
        Set<String> declaredSeen = new LinkedHashSet<>();
        for (TaskChain chain : chains) {
            if (chain.isDeclared()) {
                declaredSeen.add(chain.getRequestText());
            }
        }
        List<String> mismatched = new ArrayList<>();
        for (String key : rules.getDeclaredTaskKeys()) {
            if (!declaredSeen.contains(key)) {
                mismatched.add(key);
            }
        }
        if (mismatched.isEmpty()) {
            out.println("  tasks 期望错位：无" + (rules.getDeclaredTaskKeys().isEmpty() ? "（未配置 tasks 规则）。" : "。"));
        } else {
            out.println("  tasks 期望错位 " + mismatched.size() + " 个——已配规则但库内从未出现该声明链（核对键拼写或确认录制范围）：");
            for (String key : samples(mismatched)) {
                out.println("    " + key);
            }
        }
    }

    private static List<InteractionRecord> allRecords(StorageRepository repository) {
        List<InteractionRecord> records = new ArrayList<>();
        for (String sessionId : repository.findAllSessionIds()) {
            records.addAll(repository.findBySessionId(sessionId));
        }
        return records;
    }

    private static <T> List<T> samples(List<T> items) {
        return items.size() <= MAX_SAMPLES ? items : items.subList(0, MAX_SAMPLES);
    }
}
