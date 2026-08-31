package io.github.agentassert4j.cli;

import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * baseline 命令 — 为已录制的交互建立基线（幂等，可重复执行）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "baseline", description = "为已录制的交互建立基线（幂等，可重复执行）；export 子命令导出验收包", mixinStandardHelpOptions = true, subcommands = {BaselineExportCommand.class})
public class BaselineCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--invocation"}, description = "只处理该调用点：业务 invocationId 或 invocationKey 唯一前缀（缺省全部调用点）")
    String invocation;

    @Option(names = {"--approver"}, description = "操作者身份，随基线审批留痕（缺省取当前系统用户）")
    String approver;

    @Option(names = {"--force"}, description = "以当前判定语义重建基线：已有基线也被当前算法新指纹覆盖（判定语义升级后的恢复路径）")
    boolean force;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 报告")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露与告警改走 stderr，建档过程行丢弃
            PrintStream notice = jsonOutput ? err : out;
            repository = CliSupport.openRepository(db, notice);
            String actor = approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
            String resolvedInvocation = CliSupport.resolveInvocationFilter(repository, invocation, notice);
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, notice);
            List<BaselineService.BaselineOutcome> outcomes = new ArrayList<>();
            int established = new BaselineService(repository).establishMissing(jsonOutput ? CliSupport.discardStream() : out, actor, force, resolvedInvocation, rules, outcomes);
            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (BaselineService.BaselineOutcome outcome : outcomes) {
                    if (invocations.length() > 0) {
                        invocations.append(",");
                    }
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(outcome.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(outcome.getLabel())).append("\",\"action\":\"").append(outcome.getAction()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(outcome.getVersionTag() != null ? outcome.getVersionTag() : "")).append("\"}");
                }
                out.println("{\"schema\":\"agentassert4j.baseline-report/1\",\"force\":" + force + ",\"established\":" + established + ",\"invocations\":[" + invocations + "]}");
            } else {
                out.println(established > 0 ? "完成：" + established + " 个 调用点 " + (force ? "重建" : "新建") + "基线。" : "完成：所有 调用点 均已有基线。");
            }
            return 0;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("baseline 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
