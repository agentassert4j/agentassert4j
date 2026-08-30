package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.recorder.DataSanitizer;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.recorder.SanitizeStrategy;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.PackCodec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
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
@Command(name = "export", description = "把当前基线导出为验收基线包（单 JSON；缺省全量任务链，--task 缩域）", mixinStandardHelpOptions = true)
public class BaselineExportCommand implements Callable<Integer> {

    // 输出通道：实例字段——包内测试可注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--task"}, description = "只导出请求文本匹配该前缀的任务链（缺省全量）")
    String task;

    @Option(names = {"--include-samples"}, description = "附加每步输入/输出样本（强制 MASK 脱敏，判定不消费）")
    boolean includeSamples;

    @Option(names = {"--out"}, defaultValue = "acceptance-pack.json", description = "输出文件路径（默认 ./acceptance-pack.json）")
    String outPath;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, out);

            List<TaskChain> chains = TaskChainView.resolveAll(repository);
            if (task != null) {
                chains.removeIf(c -> !c.getRequestText().startsWith(task));
            }
            if (chains.isEmpty()) {
                err.println("没有匹配的任务链可导出（先录制并建立基线，或核对 --task 前缀）。");
                return 2;
            }

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
                    step.setFingerprint(profile.getFingerprint());
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
                    excluded.add(chain.getRequestText() + "（存在未建档步骤）");
                }
            }
            meta.setServedModel(String.join(",", servedModels));

            if (pack.getTasks().isEmpty()) {
                err.println("没有任何任务链具备完整基线指纹，包未生成。未建档链：" + String.join("；", excluded));
                return 2;
            }

            String json = PackCodec.toJson(pack);
            try {
                Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                err.println("验收包写入失败：" + e.getMessage());
                return 2;
            }
            out.println("验收包已导出：" + outPath);
            out.println("  任务链 " + pack.getTasks().size() + " 条 / 步骤 " + pack.getTasks().stream().mapToInt(t -> t.getSteps().size()).sum() + " 个" + (includeSamples ? "（含脱敏样本）" : "（无样本）"));
            out.println("  SHA-256：" + HashUtil.sha256(json) + "（请与验收方对账）");
            if (!excluded.isEmpty()) {
                out.println("  警告：以下任务链存在未建档步骤，已排除：" + String.join("；", excluded));
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("导出失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 样本写入包内前的强制脱敏器：MASK 策略 + 输入/输出双侧开启，不受环境配置影响
     */
    private static DataSanitizer forcedMaskSanitizer() {
        RecorderConfig config = RecorderConfig.builder().sanitizeStrategy(SanitizeStrategy.MASK).sanitizeUserInput(true).sanitizeModelResponse(true).build();
        return new DataSanitizer(config);
    }
}
