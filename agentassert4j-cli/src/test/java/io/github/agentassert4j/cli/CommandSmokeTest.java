package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI 命令冒烟测试 — Picocli 解析、baseline 幂等、status 展示。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class CommandSmokeTest {

    @TempDir
    Path tempDir;

    private String dbPath;
    private SqliteStorageRepository repository;
    private final PrintStream originalStdout = System.out;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("smoke.db").toString();
        repository = new SqliteStorageRepository(dbPath);
        repository.initialize();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalStdout);
        if (repository != null) {
            repository.close();
        }
    }

    private void seedOneRecord() {
        seedOneRecord("session-1", 1000L, "hash-old");
    }

    private void seedOneRecord(String sessionId, long timestamp, String templateHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-" + timestamp);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setInvocationId("queryOrder");
        r.setInvocationKey("invocation:queryOrder:" + templateHash);
        r.setTemplateHash(templateHash);
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("{\"orderId\":\"ORD-001\"}");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteraction(r);
    }

    @Test
    @DisplayName("baseline 命令建基线且幂等")
    void baseline_idempotent() {
        seedOneRecord();

        ByteArrayOutputStream out = redirectStdout();
        int first = new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);
        assertEquals(0, first);
        assertTrue(out.toString().contains("新建基线"), "首跑应建立基线: " + out);

        ByteArrayOutputStream second = redirectStdout();
        int rerun = new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);
        assertEquals(0, rerun);
        assertTrue(second.toString().contains("基线已存在"), "重复执行不得重复建: " + second);
    }

    @Test
    @DisplayName("status 命令列出 skill 与基线状态")
    void status_listsSkill() {
        seedOneRecord();
        new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath);

        assertEquals(0, exit);
        String text = out.toString();
        assertTrue(text.contains("queryOrder@hash-old"), "应以人读短形列出基线画像: " + text);
        assertTrue(text.contains("未建档调用点：无。"), "全部建档后应明示无未建档调用点: " + text);
        assertTrue(text.contains("BASELINE"), "应展示基线状态: " + text);
        assertFalse(text.contains("无基线"), "已建基线后不应再提示无基线: " + text);
    }

    @Test
    @DisplayName("status 未建档段：建档后新录制版本在 status 可见")
    void status_unestablishedSection() {
        seedOneRecord();
        new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);
        seedOneRecord("s-2", 2000L, "hash-new");

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath);

        assertEquals(0, exit);
        String text = out.toString();
        assertTrue(text.contains("未建档调用点（重跑 baseline 收编）"), "新版本应进未建档段: " + text);
        assertTrue(text.contains("queryOrder@hash-new"), "未建档版本以短形列出: " + text);
    }

    @Test
    @DisplayName("status 空库正常退出")
    void status_emptyDb() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath);
        assertEquals(0, exit);
        assertTrue(out.toString().contains("共 0 个基线画像"));
    }

    @Test
    @DisplayName("replay --dry-run 经完整 Picocli 链路预演对齐计划")
    void replayDryRun_fullCommandLine() throws Exception {
        seedOneRecord();

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--dry-run");

        assertEquals(0, exit);
        assertTrue(out.toString().contains("对齐计划"), "dry-run 应预演对齐计划: " + out);
        assertTrue(out.toString().contains("零 LLM 调用"), "缺省对齐零调用必须披露: " + out);
    }

    @Test
    @DisplayName("子命令支持 --help（mixin 逐命令声明，最常见的第一动作）")
    void subcommandHelpOptions() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--help");

        assertEquals(0, exit, "replay --help 必须展示帮助并返回 0");
        assertTrue(out.toString().contains("--invocation"), "帮助文本必须包含选项列表: " + out);
    }

    @Test
    @DisplayName("completion 生成 bash 补全脚本且覆盖 replay 子命令")
    void completion_generatesBashScript() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("completion");

        assertEquals(0, exit);
        String script = out.toString();
        assertTrue(script.contains("_agentassert4j"), "补全函数名必须以脚本名命名: " + script);
        assertTrue(script.contains("replay"), "子命令补全必须含 replay: " + script);
    }

    @Test
    @DisplayName("rules 命令列出内置行为目录")
    void rulesCommand_listsBehaviors() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("rules");

        assertEquals(0, exit);
        String text = out.toString();
        assertTrue(text.contains("mustUseChinese"), "行为目录必须完整列出合法名: " + text);
        assertTrue(text.contains("jsonOutput"));
    }

    @Test
    @DisplayName("status 展示归档版本列与业务标签列")
    void status_showsArchiveAndBusinessColumns() {
        seedOneRecord();
        new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath);

        assertEquals(0, exit);
        String text = out.toString();
        assertTrue(text.contains("归档版本"), "rollback 的可选值来源必须可见: " + text);
        assertTrue(text.contains("业务标签"), "invocationKey 与业务标签的对照必须就地可见: " + text);
        assertTrue(text.contains("queryOrder"), "业务标签列应展示用户代码里的标识: " + text);
    }

    @Test
    @DisplayName("approve bare = 裁决全部待裁决候选，无候选时显式说明")
    void adjudicate_bare_reportsNoCandidates() {
        ByteArrayOutputStream out = redirectStdout();
        ByteArrayOutputStream errOut = redirectStderr();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("approve", "--db", dbPath);

        assertEquals(2, exit);
        assertTrue(errOut.toString().contains("没有任何待裁决的候选"), "bare 无候选必须显式说明而非误报成功: " + errOut);
    }


    @Test
    @DisplayName("replay help 终态：三层模型参数面，拆除参数不复活")
    void replayHelp_finalParamSurface() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--help");

        assertEquals(0, exit);
        String help = out.toString();
        for (String dead : new String[]{"--prompt", "--old-prompt", "--old-key", "--affected", "--max-cases", "--selection", "--no-establish"}) {
            assertFalse(help.contains(dead), "已拆除参数不得出现在 help: " + dead);
        }
        for (String live : new String[]{"--task", "--invocation", "--ci", "--re-drive", "--full-chain", "--max-total-calls", "--max-total-tokens", "--dry-run", "--json", "--db"}) {
            assertTrue(help.contains(live), "终态参数必须在场: " + live);
        }
    }

    @Test
    @DisplayName("replay --json 冷启动：stdout 零污染，指导信息走 stderr")
    void replayJson_coldStart_stdoutCleanErrorsOnStderr() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(errOut, true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--json");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(2, exit);
        assertEquals("", out.toString(), "--json 模式 stdout 只允许报告本体，冷启动失败不得产出 JSON: " + out);
        assertTrue(errOut.toString().contains("未录制到任何交互数据"), "用法错误必须走 stderr 供 CI 采集: " + errOut);
    }

    @Test
    @DisplayName("replay --json --dry-run 逐行输出检测与对齐计划报告")
    void replayJsonDryRun_stageReports() throws Exception {
        seedOneRecord();

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--dry-run", "--json");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(0, exit);
        String report = out.toString().trim();
        assertTrue(report.startsWith("{\"schema\":\"agentassert4j.task-report/1\""), "stdout 必须以报告 JSON 开头: " + report);
        assertTrue(report.contains("\"mode\":\"drift-detection\""), "漂移检测报告必须先行: " + report);
        assertTrue(report.contains("\"mode\":\"task-dry-run\""), "对齐计划报告必须随行: " + report);
        assertFalse(report.contains("\"mode\":\"replay"), "调用点域报告模式已随统一引擎退役: " + report);
    }

    @Test
    @DisplayName("命令输出披露实际加载的配置来源")
    void configSource_disclosedInCommandOutput() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("status", "--db", dbPath);

        assertEquals(0, exit);
        String text = out.toString();
        assertTrue(text.contains("配置："), "隐式查找链的命中结果必须披露: " + text);
        assertTrue(text.contains("agentassert4j.json"), "披露必须指明来源文件: " + text);
    }

    /**
     * 临时接管 stdout，返回捕获缓冲（命令直接写 System.out）；tearDown 统一恢复。
     */
    private ByteArrayOutputStream redirectStdout() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        return buffer;
    }

    private ByteArrayOutputStream redirectStderr() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true));
        return buffer;
    }


}
