package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DoctorCommand 的单元测试——身份/覆盖/规则三段体检的确定性事实呈现。
 *
 * @author axy-yxa
 * @since 2026-09-02
 */
class DoctorCommandTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private String dbPath;
    private ByteArrayOutputStream output;
    private DoctorCommand command;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("doctor.db").toString();
        repository = new SqliteStorageRepository(dbPath);
        repository.initialize();
        output = new ByteArrayOutputStream();
        command = new DoctorCommand();
        command.out = new PrintStream(output, true);
        command.err = new PrintStream(output, true);
        command.db = dbPath;
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("空库体检：三段齐全，未建档与期望错位如实报无")
    void emptyDb_threeSections() {
        int exit = command.call();

        assertEquals(0, exit, "doctor 是人用体检，恒 0");
        String report = output.toString();
        assertTrue(report.contains("身份体检："), report);
        assertTrue(report.contains("覆盖体检："), report);
        assertTrue(report.contains("规则体检："), report);
        assertTrue(report.contains("未建档调用点：无。"), report);
        assertTrue(report.contains("template_hash 缺失记录：0 条"), report);
        assertTrue(report.contains("tasks 期望错位：无（未配置 tasks 规则）。"), report);
    }

    @Test
    @DisplayName("零声明事实：多步零标签链与重复请求族给声明建议，未建档版本可见")
    void zeroDeclarationFacts_suggestions() {
        save("r1", "s1", 1000L, "查订单", "invocation:q:h1", "q", "h1");
        new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream(), true), "tester", false, null, null);

        save("r2", "s2", 2000L, "查订单", "invocation:q:h2", "q", "h2");
        save("r3", "s3", 3000L, "修东西", "template:aaa", null, "h3");
        save("r4", "s3", 4000L, null, "template:aaa", null, "h3");

        int exit = command.call();

        assertEquals(0, exit);
        String report = output.toString();
        assertTrue(report.contains("多步零标签链 1 条"), "s3 两步无标签链被识别: " + report);
        assertTrue(report.contains("重复请求文本任务族 1 个"), "查订单跨 s1/s2 重复: " + report);
        assertTrue(report.contains("建议声明 taskKey"), report);
        assertTrue(report.contains("未建档调用点 2 个（重跑 baseline 收编）"), "h2 版本与零声明键均未建档可见: " + report);
        assertTrue(report.contains("q@h2"), "未建档版本短形列出: " + report);
        assertTrue(report.contains("tpl@aaa"), "零声明未建档键短形列出: " + report);
    }

    private void save(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String label, String templateHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey(invocationKey);
        r.setInvocationId(label != null ? label : "");
        r.setTemplateHash(templateHash);
        r.setModelResponse("{\"ok\":true}");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteraction(r);
    }
}
