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
@Command(name = "doctor", aliases = {"d"}, description = "Health check: deterministic identity/coverage/rules inspection (read-only; no verdicts, no baselines)", mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    private static final int MAX_SAMPLES = 3;

    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
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
            err.println("doctor failed: " + e.getMessage());
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
        out.println("Identity check:");
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
            out.println("  No skeleton-declared records; for dynamic templates consider declaring templateSkeleton at the recording exit (see the minimal recording contract in OPERATIONS).");
        } else {
            for (Map.Entry<String, Integer> entry : recordsBySkeleton.entrySet()) {
                Set<String> variants = variantsBySkeleton.getOrDefault(entry.getKey(), new LinkedHashSet<>());
                out.println("  " + CliSupport.displayKey("skeleton:" + entry.getKey()) + ": " + CliSupport.plural(entry.getValue(), "record") + ", " + CliSupport.plural(variants.size(), "full-text variant"));
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
            out.println("  Multi-step unlabeled chains: none.");
        } else {
            out.println("  " + CliSupport.plural(unlabeledMultiStep.size(), "multi-step unlabeled chain") + " (step visibility and task rules both rely on invocationId labels); consider building a label vocabulary for key invocations:");
            for (TaskChain chain : samples(unlabeledMultiStep)) {
                out.println("    '" + CliSupport.visibleText(CliSupport.abbreviateText(chain.getRequestText(), 60)) + "' (session " + chain.getSessionId() + ", " + CliSupport.plural(chain.getRecords().size(), "step") + ")");
            }
        }

        Map<String, Set<String>> sessionsByRequest = new LinkedHashMap<>();
        for (TaskChain chain : chains) {
            // 已声明 taskKey 的链其请求文本即声明值——跨会话重复正是声明在起作用，
            // 不进「建议声明」清单；此处只收集未声明链的重复事实
            if (chain.isDeclared()) {
                continue;
            }
            sessionsByRequest.computeIfAbsent(chain.getRequestText(), k -> new LinkedHashSet<>()).add(chain.getSessionId());
        }
        List<String> repeatedFamilies = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : sessionsByRequest.entrySet()) {
            if (entry.getValue().size() >= 2) {
                repeatedFamilies.add(entry.getKey());
            }
        }
        if (repeatedFamilies.isEmpty()) {
            out.println("  Repeated request-text families: none (undeclared tasks repeating across sessions are good taskKey candidates; rule refinement only applies to declared tasks).");
        } else {
            out.println("  " + CliSupport.plural(repeatedFamilies.size(), "repeated request-text family") + " (declare taskKey to enable rule refinement and cross-session pairing):");
            for (String request : samples(repeatedFamilies)) {
                out.println("    '" + CliSupport.visibleText(CliSupport.abbreviateText(request, 60)) + "' appears in " + CliSupport.plural(sessionsByRequest.get(request).size(), "session"));
            }
        }
    }

    /**
     * 覆盖段：未收编录制与哈希投影缺口。
     */
    private void printCoverageSection(StorageRepository repository, List<InvocationProfile> profiles, List<InteractionRecord> records) {
        out.println("Coverage check:");
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
            out.println("  Unestablished invocations: none.");
        } else {
            out.println("  " + CliSupport.plural(unestablished.size(), "unestablished invocation") + " (run `agentassert4j baseline` to collect):");
            for (CliSupport.InvocationFootprint footprint : samples(unestablished)) {
                out.println("    " + CliSupport.displayKey(footprint.invocationKey) + " (" + (footprint.label != null ? footprint.label : "no label") + ") " + CliSupport.plural(footprint.recordCount, "record"));
            }
        }
        int missingTemplateHash = 0;
        for (InteractionRecord record : records) {
            if (record.getTemplateHash() == null || record.getTemplateHash().isEmpty()) {
                missingTemplateHash++;
            }
        }
        out.println("  Records missing template_hash: " + missingTemplateHash + (missingTemplateHash > 0 ? "; these records lack full-text archives and template-gate credentials (re-recording fixes this)." : "."));
    }

    /**
     * 规则段：解析注记回放与期望错位（配了键但库内从未出现声明链）。
     */
    private void printRulesSection(InvocationRulesConfig rules, List<TaskChain> chains) {
        out.println("Rules check:");
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
            out.println("  tasks expectation mismatches: none" + (rules.getDeclaredTaskKeys().isEmpty() ? " (no tasks rules configured)." : "."));
        } else {
            out.println("  " + CliSupport.plural(mismatched.size(), "tasks expectation mismatch") + " (rule declared but the declared chain never appeared in the library; check key spelling or recording scope):");
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
