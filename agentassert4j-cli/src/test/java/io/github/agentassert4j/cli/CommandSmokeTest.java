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
        java.nio.file.Files.write(promptFile, "新提示词内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ByteArrayOutputStream out = redirectStdout();
        int exit = new CommandLine(new AgentAssert4jCli()).execute("replay", "--db", dbPath, "--prompt", promptFile.toString(), "--dry-run");

        assertEquals(0, exit);
        assertTrue(out.toString().contains("rec-1"), "dry-run 应列出选例: " + out);
        assertTrue(out.toString().contains("未调用 LLM"));
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
