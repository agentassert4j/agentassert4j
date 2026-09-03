package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * verify 命令 — 验收包 × 本地录制链的交付验收比对。
 *
 * <p>验收包只读：不落库、不改本地基线与候选状态。包判定语义与当前引擎不一致时
 * 拒绝判定；包任务未执行属证据缺口，不允许冒充通过。退出码：0 全部结构一致；
 * 1 任一结构偏差（CHANGED/缺步骤/新增步骤）；2 用法/版本守卫/覆盖缺口。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
@Command(name = "verify", description = "交付验收：用验收基线包核对本机真实执行的任务链（导入只读，不落库）", mixinStandardHelpOptions = true)
public class VerifyCommand implements Callable<Integer> {

    // 输出通道：实例字段——包内测试可注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--pack"}, required = true, description = "验收基线包文件路径（baseline export 的产物）")
    String packPath;

    @Option(names = {"--task"}, description = "只核对请求文本/任务键匹配该前缀的包任务（缺省全量）")
    String task;

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--report"}, description = "markdown 验收报告输出路径（交付证据）")
    String reportPath;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 验收报告（agentassert4j.verify-report/1）")
    boolean jsonOutput;

    @Option(names = {"--dry-run"}, description = "只读预演：装载包并列任务/本地链配对与跨模型注记，零判定零写入")
    boolean dryRun;

    @Override
    public Integer call() {
        String packContent;
        try {
            packContent = new String(Files.readAllBytes(Paths.get(packPath)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            err.println("无法读取验收包文件：" + packPath);
            return 2;
        }
        String digest = HashUtil.sha256(packContent);

        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? System.err : System.out);
            DeterministicComparator comparator = CliSupport.createComparator(config);
            return new VerifyRunner(repository, comparator, out, err, jsonOutput).run(packContent, digest, task, reportPath, dryRun);
        } catch (RuntimeException e) {
            err.println("verify 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
