package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.JudgmentSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * scenario run 命令冒烟测试——dry-run + JSON 通道契约（不依赖真实 LLM）。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
class ScenarioRunCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRun_json_exitZero_stdoutOnlyReport() {
        ScenarioRunCommand command = new ScenarioRunCommand();
        command.db = tempDir.resolve("scenario.db").toString();
        command.dryRun = true;
        command.jsonMode = true;

        ByteArrayOutputStream report = new ByteArrayOutputStream();
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        command.out = new PrintStream(report);
        command.err = new PrintStream(errOut);

        int exit = command.call();

        assertEquals(0, exit, "空库 dry-run 退出 0，report=[" + report + "]");
        String json = report.toString().trim();
        assertTrue(json.startsWith("{\"schema\":\"agentassert4j.scenario-report/1\""), "stdout 为场景证据报告: " + json);
        assertTrue(json.contains("\"mode\":\"dry-run\""), "dry-run 模式标识");
        assertTrue(json.contains("\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\""), "证据报告携带判定语义版本");
        assertTrue(json.contains("\"planned\":[]"), "dry-run 报告给执行计划");
        assertTrue(json.contains("\"skipped\":[]"), "报告必带跳过清单（空库为空）");
        assertFalse(json.contains("\n"), "报告必须单行");
    }

    @Test
    void emptyDb_realRun_exitTwo_notGreen() {
        // 空库真实执行：无场景可执行（runs 空、无跳过）= 证据不完整，退出 2 而非 0
        ScenarioRunCommand command = new ScenarioRunCommand();
        command.db = tempDir.resolve("scenario-empty.db").toString();
        command.jsonMode = true;

        ByteArrayOutputStream report = new ByteArrayOutputStream();
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        command.out = new PrintStream(report);
        command.err = new PrintStream(errOut);

        int exit = command.call();

        assertEquals(2, exit, "空库真实执行退出 2（无证据不冒充绿），report=[" + report + "]");
        String json = report.toString().trim();
        assertTrue(json.contains("\"runs\":[]"), "run 模式报告给执行事实: " + json);
    }

    @Test
    void explicitScenariosPath_unreadable_exitTwoWithMessage() {
        ScenarioRunCommand command = new ScenarioRunCommand();
        command.db = tempDir.resolve("scenario-path.db").toString();
        command.scenariosPath = tempDir.resolve("no-such-file.json").toString();

        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        command.out = new PrintStream(new ByteArrayOutputStream());
        command.err = new PrintStream(errOut);

        int exit = command.call();

        assertEquals(2, exit, "显式路径不可读是配置错误 → 2");
        assertTrue(errOut.toString().contains("不可读"), "错误信息点名不可读路径: " + errOut);
    }
}
