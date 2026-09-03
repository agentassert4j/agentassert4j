package io.github.agentassert4j.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * AgentAssert4j 命令行入口 — 库是一切真源：提示词内容只从应用→录制→库流入引擎，
 * bare 命令即全项目完整默认能力，参数只做缩域或开关。
 *
 * <p>典型流程：</p>
 * <pre>
 * agentassert4j status                            # 巡检调用点画像与基线状态
 * agentassert4j replay                            # 全项目漂移检测 + 逐任务对齐（零 LLM 调用）
 * agentassert4j replay --re-drive                 # 受控重驱：逐漂移点以归档模板复核（花调用）
 * agentassert4j approve                           # 裁决全部待裁决候选
 * agentassert4j rollback --invocation ab12cd34 --version v1   # 恢复到归档基线
 * agentassert4j completion > agentassert4j.bash   # 生成 shell 补全脚本
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "agentassert4j", version = "AgentAssert4j 1.0.0-SNAPSHOT", description = {"AI Agent 行为回归测试框架 — 旁路录制、确定性基线、变更检测与真实对齐、人工裁决。", "", "bare 命令 = 全项目完整默认能力（replay 缺省零 LLM 调用）；参数只做缩域或开关。", "常用组合：replay 全项目变更检测 → 对齐 CHANGED/漂移落候选 → approve/reject 裁决 → rollback 回溯。"}, exitCodeList = {"0", "无行为回归（--ci 模式下漂移未收编仍出 0，附警告行）", "1", "行为差异或证据缺口：对齐 CHANGED/缺步骤/新增步骤/规则违规/漂移挂起（没跑够，真实重跑或重驱可补）", "2", "用法、数据或环境问题：选链错误、守卫拒绝、预算耗尽、重驱全败（被截断或环境故障）"}, subcommands = {BaselineCommand.class, StatusCommand.class, ReplayCommand.class, ApproveCommand.class, RejectCommand.class, RollbackCommand.class, RulesCommand.class, GraphCommand.class, VerifyCommand.class, DoctorCommand.class, CompletionCommand.class}, mixinStandardHelpOptions = true)
public class AgentAssert4jCli {

    public static void main(String[] args) {
        CliSupport.installUtf8Console();
        int exitCode = new CommandLine(new AgentAssert4jCli()).execute(args);
        System.exit(exitCode);
    }
}
