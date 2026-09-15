package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.recorder.DataSanitizer;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.recorder.SanitizeStrategy;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.PackCodec;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * baseline export 子命令 — 把当前基线导出为验收包（`agentassert4j.acceptance-pack/1`）。
 *
 * <p>包内容天然脱敏：只携带结构指纹与调用点键；--include-samples 附加的样本强制
 * MASK 且输入/输出双侧脱敏开启（不受环境 recorder 配置影响——写入包内前完成脱敏）。
 * 导出打印文件 SHA-256 供交付双方对账（完整性优于保密性，加密进后备池）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
@Command(name = "export", description = "Export the current baselines as an acceptance pack (single JSON; all task chains by default, --task narrows)", mixinStandardHelpOptions = true)
public class BaselineExportCommand implements Callable<Integer> {

    // 输出通道：实例字段——包内测试可注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--task"}, description = "Export only task chains whose request text matches this prefix (defaults to all)")
    String task;

    @Option(names = {"--include-samples"}, description = "Attach per-step input/output samples (force-masked; never consumed by verdicts)")
    boolean includeSamples;

    @Option(names = {"--out"}, defaultValue = "acceptance-pack.json", description = "Output file path (default ./acceptance-pack.json)")
    String outPath;

    @Option(names = {"--ref"}, description = "Code reference (e.g. a git commit) recorded in the acceptance pack metadata; declared, not verified")
    String codeRef;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout (agentassert4j.export-report/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);

            List<TaskChain> chains = latestChainPerTaskKey(TaskChainView.resolveAll(repository));
            if (task != null) {
                chains.removeIf(c -> !c.getRequestText().startsWith(task));
            }
            if (chains.isEmpty()) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, "No task chains to export.", "Record interactions and run `agentassert4j baseline` first, or check the --task prefix.", "agentassert4j baseline");
            }

            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            DataSanitizer sanitizer = includeSamples ? forcedMaskSanitizer() : null;
            AcceptancePack pack = new AcceptancePack();
            AcceptancePack.PackMeta meta = new AcceptancePack.PackMeta();
            meta.setExportedAt(System.currentTimeMillis());
            meta.setExportedBy(CliSupport.currentActor());
            meta.setJudgmentSemantics(JudgmentSemantics.VERSION);
            meta.setStorageSchemaVersion(1);
            meta.setFrameworkVersion(AgentAssert4jCli.FRAMEWORK_VERSION);
            meta.setCodeRef(codeRef == null || codeRef.trim().isEmpty() ? null : codeRef.trim());
            pack.setMeta(meta);

            List<String> excluded = new ArrayList<>();
            List<String> unadjudicatedTasks = new ArrayList<>();
            TreeSet<String> servedModels = new TreeSet<>();
            int unadjudicatedTotal = 0;
            for (TaskChain chain : chains) {
                AcceptancePack.PackTask packTask = new AcceptancePack.PackTask();
                packTask.setTaskKey(chain.getRequestText());
                packTask.setRequestText(chain.getRequestText());
                packTask.setDeclared(chain.isDeclared());
                packTask.setBaselineTime(chain.firstTimestamp());

                boolean complete = true;
                boolean selfViolating = false;
                int unadjudicated = 0;
                // 包 = 批准真相定格交付：步骤按调用点分组取组末记录为证据锚（recordId/
                // 样本锚定链末执行），指纹消费画像活跃指纹（与 CI 同源）——链末判定下每
                // 调用点一份步骤，不存在「单份快照冒充多步骤」
                for (List<InteractionRecord> group : TaskAligner.invocationGroups(chain).values()) {
                    InteractionRecord anchor = group.get(group.size() - 1);
                    String key = CliSupport.invocationKeyOfRecord(anchor);
                    InvocationProfile profile = key == null ? null : repository.findInvocationByKey(key);
                    if (profile == null || profile.getFingerprint() == null) {
                        complete = false;
                        break;
                    }
                    BaselineStep step = new BaselineStep();
                    step.setInvocationKey(key);
                    step.setRecordId(anchor.getRecordId());
                    step.setFingerprint(profile.getFingerprint());
                    // 出厂偏离检测：链末行为与承诺的结构维不一致（或在途候选未裁决）
                    // → 计数入包并警告，任务照常入包（承诺仍良定义）。比较口径=结构维
                    //（维度 1/2 + hasError）：判定尺的维度 3/4 是基线声明 × 当前答卷，
                    // 候选侧声明集不进判定——规则配置漂移只动声明集时门禁判 PASS，偏离
                    // 检测不得比门禁更严（否则警告指路的裁决对象根本不存在）
                    if (profile.getCandidateFingerprint() != null
                            || !structuralView(FingerprintExtractor.extract(anchor, rules, anchor.getInvocationId())).equals(structuralView(profile.getFingerprint()))) {
                        unadjudicated++;
                    } else if (selfViolatesDeclaredRules(step.getFingerprint(), anchor.getModelResponse())) {
                        // 自违守卫只在组末与画像一致时执行：未批准形态走偏离出口，
                        // 不误诊为「基线自违」（approved 指纹 × 偏离响应的比对对象错位）
                        complete = false;
                        selfViolating = true;
                        break;
                    }
                    if (sanitizer != null) {
                        InteractionRecord sanitized = sanitizer.sanitize(anchor);
                        step.setSampleInput(sanitized.getUserInput());
                        step.setSampleOutput(sanitized.getModelResponse());
                    }
                    packTask.getSteps().add(step);
                    if (anchor.getServedModel() != null) {
                        servedModels.add(anchor.getServedModel());
                    }
                }
                packTask.setUnadjudicatedSteps(unadjudicated);
                if (complete && !packTask.getSteps().isEmpty()) {
                    pack.getTasks().add(packTask);
                    if (unadjudicated > 0) {
                        unadjudicatedTasks.add(chain.getRequestText() + " (" + unadjudicated + ")");
                        unadjudicatedTotal += unadjudicated;
                    }
                } else if (selfViolating) {
                    excluded.add(chain.getRequestText() + " (baseline violates its own content rules)");
                } else {
                    excluded.add(chain.getRequestText() + " (unestablished steps present)");
                }
            }
            meta.setServedModel(String.join(",", servedModels));
            // 声明规则段随包出境（断言而非提示词）：验收侧以同一份规则对称评估任务纪律；
            // 维度 3/4 的比对语义 = 基线声明 × 当前响应，导出侧声明本就随指纹入包
            if (rules != null && (rules.hasRules() || rules.hasTaskRules())) {
                pack.setRules(rules.toMap());
            }

            if (pack.getTasks().isEmpty()) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, "No task chain has complete baseline fingerprints; pack not written. Excluded chains: " + String.join("; ", excluded), "Establish or fix baselines for the excluded chains (`agentassert4j baseline`), then re-export.", "agentassert4j baseline");
            }

            String json = PackCodec.toJson(pack);
            try {
                Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "Failed to write the acceptance pack: " + CliSupport.describe(e), "Check the --out path and write permissions, then retry.", "");
            }
            int stepCount = pack.getTasks().stream().mapToInt(t -> t.getSteps().size()).sum();
            if (jsonOutput) {
                StringBuilder excludedJson = new StringBuilder();
                for (String excludedChain : excluded) {
                    if (excludedJson.length() > 0) excludedJson.append(",");
                    excludedJson.append("\"").append(RecursiveJsonParser.escape(excludedChain)).append("\"");
                }
                out.println("{\"schema\":\"" + ReportSchemas.EXPORT_REPORT + "\",\"out\":\"" + RecursiveJsonParser.escape(outPath) + "\",\"taskCount\":" + pack.getTasks().size() + ",\"stepCount\":" + stepCount + ",\"unadjudicatedSteps\":" + unadjudicatedTotal + ",\"sha256\":\"" + HashUtil.sha256(json) + "\",\"codeRef\":\"" + RecursiveJsonParser.escape(meta.getCodeRef() != null ? meta.getCodeRef() : "") + "\",\"excluded\":[" + excludedJson + "]}");
                return 0;
            }
            out.println("Acceptance pack written: " + outPath);
            out.println("  " + CliSupport.plural(pack.getTasks().size(), "task chain") + " / " + CliSupport.plural(stepCount, "step") + (includeSamples ? " (masked samples included)" : " (no samples)") + (meta.getCodeRef() != null ? " (ref " + meta.getCodeRef() + ")" : ""));
            out.println("  SHA-256: " + HashUtil.sha256(json) + " (reconcile with the accepting party)");
            List<String> ruleViolated = new ArrayList<>();
            List<String> unestablishedOnly = new ArrayList<>();
            for (String reason : excluded) {
                if (reason.contains("(baseline violates its own content rules)")) {
                    ruleViolated.add(reason);
                } else {
                    unestablishedOnly.add(reason);
                }
            }
            if (!ruleViolated.isEmpty()) {
                err.println("  Warning: task chains whose baselines violate their own declared content rules were excluded: " + String.join("; ", ruleViolated));
                err.println("  Fix the baseline (re-record under the current template) or the rules declaration, then re-export.");
            }
            if (!unestablishedOnly.isEmpty()) {
                out.println("  Warning: task chains with unestablished steps were excluded: " + String.join("; ", unestablishedOnly));
            }
            if (!unadjudicatedTasks.isEmpty()) {
                err.println("  Warning: unadjudicated steps in the pack (in-flight candidate, or the chain-end shape differs from the approved baseline): " + String.join("; ", unadjudicatedTasks));
                err.println("  Adjudicate the pending candidates (accept/reject), then re-export for a clean pack.");
                err.println("  (The pack is still written; these steps are counted in-pack and in the --json report.)");
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "export failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 同任务键只保留链首时间最新的链（resolveAll 升序遍历、后写者覆盖即最新）——
     * 与 verify 侧「取最新为对照」对称；旧链任务对到本地最新链只会制造假差异。
     */
    private static List<TaskChain> latestChainPerTaskKey(List<TaskChain> chains) {
        Map<String, TaskChain> latest = new LinkedHashMap<>();
        for (TaskChain chain : chains) {
            latest.put(chain.getRequestText(), chain);
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * 样本写入包内前的强制脱敏器：MASK 策略 + 输入/输出双侧开启，不受环境配置影响
     */
    private static DataSanitizer forcedMaskSanitizer() {
        RecorderConfig config = RecorderConfig.builder().sanitizeStrategy(SanitizeStrategy.MASK).sanitizeUserInput(true).sanitizeModelResponse(true).build();
        return new DataSanitizer(config);
    }

    /**
     * 自违检查：以与生产判定完全同源的语义自比较（指纹对自己、响应对自己）评估
     * 基线响应是否满足自己声明的内容规则——单一真源，不复制第二套 dim3 判定。
     */
    private static boolean selfViolatesDeclaredRules(DeterministicFingerprint fingerprint, String response) {
        if (fingerprint == null) {
            return false;
        }
        ComparisonResult result = new DeterministicComparator(ComparatorConfig.defaults()).compare(fingerprint, fingerprint, response);
        return !result.isKeywordMatch() || !result.isRegexMatch();
    }

    /**
     * 结构维视图（维度 1/2 + hasError，维度 3/4 声明集置空）——出厂偏离检测的比较
     * 口径与判定尺对齐：候选侧声明集不进判定，仅声明集漂移不是行为偏离。
     */
    private static DeterministicFingerprint structuralView(DeterministicFingerprint fingerprint) {
        DeterministicFingerprint view = new DeterministicFingerprint();
        view.setToolCallSet(fingerprint.getToolCallSet());
        view.setToolParamTypes(fingerprint.getToolParamTypes());
        view.setOutputContentType(fingerprint.getOutputContentType());
        view.setOutputFieldPaths(fingerprint.getOutputFieldPaths());
        view.setOutputFieldTypeMap(fingerprint.getOutputFieldTypeMap());
        view.setTextLengthMagnitude(fingerprint.getTextLengthMagnitude());
        view.setHasError(fingerprint.isHasError());
        view.setRequiredKeywords(Collections.emptySet());
        view.setForbiddenKeywords(Collections.emptySet());
        view.setRegexPatterns(Collections.emptyList());
        view.setDeclaredBehaviors(Collections.emptySet());
        return view;
    }
}
