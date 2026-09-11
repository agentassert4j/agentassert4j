package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
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
 * 无模糊匹配；本命令退出码恒 0，不承 CI gating 职责。--json 提供机器通道
 * （agentassert4j.doctor/1）：计数全量、样本封顶，与人类通道同源采集。</p>
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

    @Option(names = {"--json"}, description = "Print a single-line JSON health report to stdout (agentassert4j.doctor/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露与规则告警改走 stderr
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<InteractionRecord> records = allRecords(repository);
            List<TaskChain> chains = TaskChainView.resolveAll(repository);
            List<InvocationProfile> profiles = repository.findAllInvocations();
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            AgentAssert4jConfig mainConfig = ConfigLoader.loadAgentAssert4jConfig();
            for (String note : mainConfig.getConfigNotes()) {
                (jsonOutput ? err : out).println("Config note: " + note);
            }

            DoctorFindings findings = collectFindings(repository, records, chains, profiles, rules);
            if (jsonOutput) {
                for (String warning : findings.ruleWarnings) {
                    err.println("Warning: " + warning);
                }
                out.println(doctorJson(findings));
            } else {
                printIdentitySection(findings);
                printCoverageSection(findings);
                printRulesSection(findings);
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "doctor failed: " + CliSupport.describe(e), "Fix the reported problem and retry.", "");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 三段体检的一次性采集：人类渲染与 doctor/1 报告共用同一份事实，两通道不得分叉。
     */
    private DoctorFindings collectFindings(StorageRepository repository, List<InteractionRecord> records, List<TaskChain> chains, List<InvocationProfile> profiles, InvocationRulesConfig rules) {
        DoctorFindings findings = new DoctorFindings();

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
        for (Map.Entry<String, Integer> entry : recordsBySkeleton.entrySet()) {
            findings.skeletons.put(entry.getKey(), new SkeletonStat(entry.getValue(), variantsBySkeleton.getOrDefault(entry.getKey(), new LinkedHashSet<>()).size()));
        }

        List<TaskChain> unlabeledMultiStep = new ArrayList<>();
        for (TaskChain chain : chains) {
            if (CliSupport.isMultiStepUnlabeled(chain)) {
                unlabeledMultiStep.add(chain);
            }
        }
        findings.multiStepUnlabeled.addAll(unlabeledMultiStep);

        // 已声明 taskKey 的链其请求文本即声明值——跨会话重复正是声明在起作用，
        // 不进「建议声明」清单；此处只收集未声明链的重复事实
        Map<String, Set<String>> sessionsByRequest = new LinkedHashMap<>();
        for (TaskChain chain : chains) {
            if (chain.isDeclared()) {
                continue;
            }
            sessionsByRequest.computeIfAbsent(chain.getRequestText(), k -> new LinkedHashSet<>()).add(chain.getSessionId());
        }
        for (Map.Entry<String, Set<String>> entry : sessionsByRequest.entrySet()) {
            if (entry.getValue().size() >= 2) {
                findings.repeatedFamilies.add(new RequestFamily(entry.getKey(), entry.getValue().size()));
            }
        }

        Set<String> established = new HashSet<>();
        for (InvocationProfile profile : profiles) {
            established.add(profile.getInvocationKey());
        }
        for (InvocationFootprint footprint : CliSupport.recordedInvocationFootprints(repository)) {
            if (!established.contains(footprint.invocationKey)) {
                findings.unestablished.add(footprint);
            }
        }
        for (InteractionRecord record : records) {
            if (record.getTemplateHash() == null || record.getTemplateHash().isEmpty()) {
                findings.recordsMissingTemplateHash++;
            }
        }

        findings.ruleWarnings.addAll(CliSupport.malformedTaskRuleWarnings(rules));
        findings.noTasksConfigured = rules.getDeclaredTaskKeys().isEmpty();
        Set<String> declaredSeen = new LinkedHashSet<>();
        for (TaskChain chain : chains) {
            if (chain.isDeclared()) {
                declaredSeen.add(chain.getRequestText());
            }
        }
        for (String key : rules.getDeclaredTaskKeys()) {
            if (!declaredSeen.contains(key)) {
                findings.expectationMismatches.add(key);
            }
        }
        return findings;
    }

    /**
     * 身份段：骨架族形态、多步零标签链、重复请求文本任务族——零声明接入获得
     * 框架价值前需要补的声明，都在这里以确定性事实呈现。
     */
    private void printIdentitySection(DoctorFindings findings) {
        out.println("Identity check:");
        if (findings.skeletons.isEmpty()) {
            out.println("  No skeleton-declared records; for dynamic templates consider declaring templateSkeleton when recording (see the minimal recording contract in OPERATIONS).");
        } else {
            for (Map.Entry<String, SkeletonStat> entry : findings.skeletons.entrySet()) {
                out.println("  " + CliSupport.displayKey("skeleton:" + entry.getKey()) + ": " + CliSupport.plural(entry.getValue().records, "record") + ", " + CliSupport.plural(entry.getValue().fullTextVariants, "full-text variant"));
            }
        }

        if (findings.multiStepUnlabeled.isEmpty()) {
            out.println("  Multi-step unlabeled chains: none.");
        } else {
            out.println("  " + CliSupport.plural(findings.multiStepUnlabeled.size(), "multi-step unlabeled chain") + " (step visibility and task rules both rely on invocationId labels); consider building a label vocabulary for key invocations:");
            for (TaskChain chain : samples(findings.multiStepUnlabeled)) {
                out.println("    '" + CliSupport.visibleText(CliSupport.abbreviateText(chain.getRequestText(), 60)) + "' (session " + chain.getSessionId() + ", " + CliSupport.plural(chain.getRecords().size(), "step") + ")");
            }
        }

        if (findings.repeatedFamilies.isEmpty()) {
            out.println("  Repeated request-text families: none (undeclared tasks repeating across sessions are good taskKey candidates; rule refinement only applies to declared tasks).");
        } else {
            out.println("  " + CliSupport.plural(findings.repeatedFamilies.size(), "repeated request-text family") + " (declare taskKey to enable rule refinement and cross-session pairing):");
            for (RequestFamily family : samples(findings.repeatedFamilies)) {
                out.println("    '" + CliSupport.visibleText(CliSupport.abbreviateText(family.request, 60)) + "' appears in " + CliSupport.plural(family.sessions, "session"));
            }
        }
    }

    /**
     * 覆盖段：未收编录制与哈希投影缺口。
     */
    private void printCoverageSection(DoctorFindings findings) {
        out.println("Coverage check:");
        if (findings.unestablished.isEmpty()) {
            out.println("  Unestablished invocations: none.");
        } else {
            out.println("  " + CliSupport.plural(findings.unestablished.size(), "unestablished invocation") + " (run `agentassert4j baseline` to collect):");
            for (InvocationFootprint footprint : samples(findings.unestablished)) {
                out.println("    " + CliSupport.displayKey(footprint.invocationKey) + " (" + (footprint.label != null ? footprint.label : "no label") + ") " + CliSupport.plural(footprint.recordCount, "record"));
            }
        }
        out.println("  Records missing template_hash: " + findings.recordsMissingTemplateHash + (findings.recordsMissingTemplateHash > 0 ? "; these records have no full-text archive and no template hash, so drift detection cannot check them (re-recording fixes this)." : "."));
    }

    /**
     * 规则段：解析注记回放与期望错位（配了键但库内从未出现声明链）。
     */
    private void printRulesSection(DoctorFindings findings) {
        out.println("Rules check:");
        for (String warning : findings.ruleWarnings) {
            out.println("Warning: " + warning);
        }
        if (findings.expectationMismatches.isEmpty()) {
            out.println("  tasks expectation mismatches: none" + (findings.noTasksConfigured ? " (no tasks rules configured)." : "."));
        } else {
            out.println("  " + CliSupport.plural(findings.expectationMismatches.size(), "tasks expectation mismatch") + " (taskKey declared but no recorded chain ever matched it; check key spelling or recording scope):");
            for (String key : samples(findings.expectationMismatches)) {
                out.println("    " + key);
            }
        }
    }

    /**
     * doctor/1 单行机器报告：计数全量 + 样本封顶（与人类通道同款缩略），
     * 规则告警清单全量携带（数量级受规则文件约束）。
     */
    private String doctorJson(DoctorFindings findings) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"" + ReportSchemas.DOCTOR + "\",\"identity\":{\"skeletonCount\":").append(findings.skeletons.size()).append(",\"skeletonSamples\":[");
        List<String> skeletonJsons = new ArrayList<>();
        for (Map.Entry<String, SkeletonStat> entry : samples(new ArrayList<>(findings.skeletons.entrySet()))) {
            skeletonJsons.add("{\"key\":\"" + RecursiveJsonParser.escape(entry.getKey()) + "\",\"records\":" + entry.getValue().records + ",\"fullTextVariants\":" + entry.getValue().fullTextVariants + "}");
        }
        sb.append(String.join(",", skeletonJsons)).append("],\"multiStepUnlabeledChains\":").append(findings.multiStepUnlabeled.size()).append(",\"chainSamples\":[");
        List<String> chainJsons = new ArrayList<>();
        for (TaskChain chain : samples(findings.multiStepUnlabeled)) {
            chainJsons.add("{\"request\":\"" + RecursiveJsonParser.escape(CliSupport.visibleText(CliSupport.abbreviateText(chain.getRequestText(), 60))) + "\",\"sessionId\":\"" + RecursiveJsonParser.escape(chain.getSessionId()) + "\",\"steps\":" + chain.getRecords().size() + "}");
        }
        sb.append(String.join(",", chainJsons)).append("],\"repeatedRequestFamilies\":").append(findings.repeatedFamilies.size()).append(",\"requestSamples\":[");
        List<String> familyJsons = new ArrayList<>();
        for (RequestFamily family : samples(findings.repeatedFamilies)) {
            familyJsons.add("{\"request\":\"" + RecursiveJsonParser.escape(CliSupport.visibleText(CliSupport.abbreviateText(family.request, 60))) + "\",\"sessions\":" + family.sessions + "}");
        }
        sb.append(String.join(",", familyJsons)).append("]},\"coverage\":{\"unestablishedInvocations\":").append(findings.unestablished.size()).append(",\"invocationSamples\":[");
        List<String> unestablishedJsons = new ArrayList<>();
        for (InvocationFootprint footprint : samples(findings.unestablished)) {
            unestablishedJsons.add("{\"invocationKey\":\"" + RecursiveJsonParser.escape(footprint.invocationKey) + "\",\"label\":\"" + RecursiveJsonParser.escape(footprint.label != null ? footprint.label : "") + "\",\"recordCount\":" + footprint.recordCount + "}");
        }
        sb.append(String.join(",", unestablishedJsons)).append("],\"recordsMissingTemplateHash\":").append(findings.recordsMissingTemplateHash).append("},\"rules\":{\"ruleWarnings\":[");
        List<String> warningJsons = new ArrayList<>();
        for (String warning : findings.ruleWarnings) {
            warningJsons.add("\"" + RecursiveJsonParser.escape(warning) + "\"");
        }
        sb.append(String.join(",", warningJsons)).append("],\"expectationMismatches\":").append(findings.expectationMismatches.size()).append(",\"mismatchSamples\":[");
        List<String> mismatchJsons = new ArrayList<>();
        for (String key : samples(findings.expectationMismatches)) {
            mismatchJsons.add("\"" + RecursiveJsonParser.escape(key) + "\"");
        }
        sb.append(String.join(",", mismatchJsons)).append("]}}");
        return sb.toString();
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

    /**
     * doctor/1 与人类渲染共用的体检事实——采集一次，两通道各自成形。
     */
    private static final class DoctorFindings {
        /**
         * 骨架族（插入序 = 记录遍历序）：哈希 → 记录数与全文变体数。
         */
        final Map<String, SkeletonStat> skeletons = new LinkedHashMap<>();
        /**
         * 多步零标签链（全量，渲染与样本各自封顶）。
         */
        final List<TaskChain> multiStepUnlabeled = new ArrayList<>();
        /**
         * 跨会话重复的未声明请求文本族（全量）。
         */
        final List<RequestFamily> repeatedFamilies = new ArrayList<>();
        /**
         * 已录制未建档调用点（全量）。
         */
        final List<InvocationFootprint> unestablished = new ArrayList<>();
        int recordsMissingTemplateHash;
        /**
         * rules.tasks 畸形告警（全量；人类通道加 Warning: 前缀输出）。
         */
        final List<String> ruleWarnings = new ArrayList<>();
        /**
         * 声明了 taskKey 但库内从未出现声明链（全量）。
         */
        final List<String> expectationMismatches = new ArrayList<>();
        /**
         * 规则文件是否完全没有声明 tasks 键（决定 none 行的补充说明）。
         */
        boolean noTasksConfigured;
    }

    /**
     * 骨架族计数。
     */
    private static final class SkeletonStat {
        final int records;
        final int fullTextVariants;

        SkeletonStat(int records, int fullTextVariants) {
            this.records = records;
            this.fullTextVariants = fullTextVariants;
        }
    }

    /**
     * 重复请求文本族：请求文本 × 覆盖会话数。
     */
    private static final class RequestFamily {
        final String request;
        final int sessions;

        RequestFamily(String request, int sessions) {
            this.request = request;
            this.sessions = sessions;
        }
    }
}
