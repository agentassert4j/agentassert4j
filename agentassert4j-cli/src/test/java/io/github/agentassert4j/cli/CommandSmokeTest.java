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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setSessionId("session-1");
        r.setTimestamp(1000L);
        r.setSeq(1L);
        r.setSkillId("queryOrder");
        r.setTemplateHash("hash-old");
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
        assertTrue(text.contains("chat:hash-old"), "应以 groupKey 列出基线画像: " + text);
        assertTrue(text.contains("BASELINE"), "应展示基线状态: " + text);
        assertFalse(text.contains("无基线"), "已建基线后不应再提示无基线: " + text);
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
    @DisplayName("replay --dry-run 经完整 Picocli 链路选例")
    void replayDryRun_fullCommandLine() throws Exception {
        seedOneRecord();
        new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);
        Path promptFile = tempDir.resolve("new-prompt.txt");
        Files.write(promptFile, "新提示词内容".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--prompt", promptFile.toString(), "--dry-run");

        assertEquals(0, exit);
        assertTrue(out.toString().contains("rec-1"), "dry-run 应列出选例: " + out);
        assertTrue(out.toString().contains("未调用 LLM"));
    }

    @Test
    @DisplayName("子命令支持 --help（mixin 逐命令声明，最常见的第一动作）")
    void subcommandHelpOptions() {
        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--help");

        assertEquals(0, exit, "replay --help 必须展示帮助并返回 0");
        assertTrue(out.toString().contains("--max-cases"), "帮助文本必须包含选项列表: " + out);
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
        assertTrue(text.contains("业务标签"), "groupKey 与业务标签的对照必须就地可见: " + text);
        assertTrue(text.contains("queryOrder"), "业务标签列应展示用户代码里的标识: " + text);
    }

    @Test
    @DisplayName("approve 的 --skill 与 --all 互斥")
    void adjudicate_skillAndAll_mutuallyExclusive() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut, true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("approve", "--db", dbPath, "--skill", "chat:x", "--all");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(2, exit);
        assertTrue(errOut.toString().contains("不能同时使用"), "互斥必须显式报错而非静默忽略其一: " + errOut);
    }

    @Test
    @DisplayName("--max-cases < 1 拒绝执行而非误导性「未找到用例」")
    void replay_maxCasesBelowOne_rejected() throws Exception {
        Path promptFile = tempDir.resolve("prompt.txt");
        Files.write(promptFile, "新提示词".getBytes(StandardCharsets.UTF_8));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut, true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--prompt", promptFile.toString(), "--max-cases", "0");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(2, exit);
        assertTrue(errOut.toString().contains("--max-cases 必须 ≥ 1"), "参数下限必须显式报错: " + errOut);
    }

    @Test
    @DisplayName("replay --json 冷启动：stdout 零污染，指导信息走 stderr")
    void replayJson_coldStart_stdoutCleanErrorsOnStderr() throws Exception {
        Path promptFile = tempDir.resolve("prompt.txt");
        Files.write(promptFile, "新提示词".getBytes(StandardCharsets.UTF_8));

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(errOut, true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--prompt", promptFile.toString(), "--json");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(2, exit);
        assertEquals("", out.toString(), "--json 模式 stdout 只允许报告本体，冷启动失败不得产出 JSON: " + out);
        assertTrue(errOut.toString().contains("未找到可重放用例"), "用法错误必须走 stderr 供 CI 采集: " + errOut);
    }

    @Test
    @DisplayName("replay --json --dry-run 输出单行 dry-run 报告")
    void replayJsonDryRun_singleLineReport() throws Exception {
        seedOneRecord();
        new CommandLine(new AgentAssert4jCli()).execute("baseline", "--db", dbPath);
        Path promptFile = tempDir.resolve("new-prompt.txt");
        Files.write(promptFile, "新提示词内容".getBytes(StandardCharsets.UTF_8));

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        int exit;
        try {
            exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--prompt", promptFile.toString(), "--dry-run", "--json");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(0, exit);
        String report = out.toString().trim();
        assertTrue(report.startsWith("{\"schema\":\"agentassert4j.replay-report/1\""), "stdout 必须以报告 JSON 开头: " + report);
        assertFalse(report.contains("\n"), "报告必须单行（消费方按行读取）: " + report);
        assertTrue(report.contains("\"mode\":\"dry-run\""), "dry-run 报告必须标明模式: " + report);
        assertTrue(report.contains("rec-1"), "选例清单必须含 recordId: " + report);
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
}
