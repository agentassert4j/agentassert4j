package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.recorder.DataSanitizer;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.recorder.SanitizeStrategy;
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
                err.println("No task chains to export. Record interactions and establish baselines first, or check the --task prefix.");
                return 2;
            }

            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            DataSanitizer sanitizer = includeSamples ? forcedMaskSanitizer() : null;
            AcceptancePack pack = new AcceptancePack();
            AcceptancePack.PackMeta meta = new AcceptancePack.PackMeta();
            meta.setExportedAt(System.currentTimeMillis());
            meta.setExportedBy(CliSupport.currentActor());
            meta.setJudgmentSemantics(JudgmentSemantics.VERSION);
            meta.setStorageSchemaVersion(1);
            meta.setFrameworkVersion("1.0.0-SNAPSHOT");
            pack.setMeta(meta);

            List<String> excluded = new ArrayList<>();
            TreeSet<String> servedModels = new TreeSet<>();
            for (TaskChain chain : chains) {
                AcceptancePack.PackTask packTask = new AcceptancePack.PackTask();
                packTask.setTaskKey(chain.getRequestText());
                packTask.setRequestText(chain.getRequestText());
                packTask.setDeclared(chain.isDeclared());
                packTask.setBaselineTime(chain.firstTimestamp());

                boolean complete = true;
                for (InteractionRecord record : chain.getRecords()) {
                    String key = CliSupport.invocationKeyOfRecord(record);
                    InvocationProfile profile = key == null ? null : repository.findInvocationByKey(key);
                    if (profile == null || profile.getFingerprint() == null) {
                        complete = false;
                        break;
                    }
                    BaselineStep step = new BaselineStep();
                    step.setInvocationKey(key);
                    step.setRecordId(record.getRecordId());
                    // 步骤指纹逐记录现场提取（与 verify 重提侧、库内任务对齐同口径）——
                    // 画像指纹是建档种子记录的单份快照，同键多记录时冒充其他步骤必然假 CHANGED
                    step.setFingerprint(FingerprintExtractor.extract(record, rules, record.getInvocationId()));
                    if (sanitizer != null) {
                        InteractionRecord sanitized = sanitizer.sanitize(record);
                        step.setSampleInput(sanitized.getUserInput());
                        step.setSampleOutput(sanitized.getModelResponse());
                    }
                    packTask.getSteps().add(step);
                    if (record.getServedModel() != null) {
                        servedModels.add(record.getServedModel());
                    }
                }
                if (complete && !packTask.getSteps().isEmpty()) {
                    pack.getTasks().add(packTask);
                } else {
                    excluded.add(chain.getRequestText() + " (unestablished steps present)");
                }
            }
            meta.setServedModel(String.join(",", servedModels));

            if (pack.getTasks().isEmpty()) {
                err.println("No task chain has complete baseline fingerprints; pack not written. Unestablished chains: " + String.join("; ", excluded));
                return 2;
            }

            String json = PackCodec.toJson(pack);
            try {
                Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                err.println("Failed to write the acceptance pack: " + e.getMessage());
                return 2;
            }
            int stepCount = pack.getTasks().stream().mapToInt(t -> t.getSteps().size()).sum();
            if (jsonOutput) {
                StringBuilder excludedJson = new StringBuilder();
                for (String excludedChain : excluded) {
                    if (excludedJson.length() > 0) excludedJson.append(",");
                    excludedJson.append("\"").append(RecursiveJsonParser.escape(excludedChain)).append("\"");
                }
                out.println("{\"schema\":\"agentassert4j.export-report/1\",\"out\":\"" + RecursiveJsonParser.escape(outPath) + "\",\"taskCount\":" + pack.getTasks().size() + ",\"stepCount\":" + stepCount + ",\"sha256\":\"" + HashUtil.sha256(json) + "\",\"excluded\":[" + excludedJson + "]}");
                return 0;
            }
            out.println("Acceptance pack written: " + outPath);
            out.println("  " + CliSupport.plural(pack.getTasks().size(), "task chain") + " / " + CliSupport.plural(stepCount, "step") + (includeSamples ? " (masked samples included)" : " (no samples)"));
            out.println("  SHA-256: " + HashUtil.sha256(json) + " (reconcile with the accepting party)");
            if (!excluded.isEmpty()) {
                out.println("  Warning: task chains with unestablished steps were excluded: " + String.join("; ", excluded));
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("export failed: " + e.getMessage());
            return 2;
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
}
