package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令面 --json 契约测试 — 每个命令的 stdout 单行报告、schema 标签、
 * 关键字段与通道纪律（--json 模式下人类文本与配置披露不落 stdout）。
 *
 * @author axy-yxa
 * @since 2026-08-31
 */
class JsonContractTest {

    @TempDir
    Path tempDir;

    private String dbPath;
    private SqliteStorageRepository repository;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream stdoutBuffer;
    private ByteArrayOutputStream stderrBuffer;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("json-contract.db").toString();
        repository = new SqliteStorageRepository(dbPath);
        repository.initialize();
        originalOut = System.out;
        originalErr = System.err;
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (repository != null) {
            repository.close();
        }
    }

    private InteractionRecord seedOneRecord() {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setSessionId("session-1");
        r.setTimestamp(1000L);
        r.setSeq(1L);
        r.setInvocationId("queryOrder");
        r.setTemplateHash("hash-old");
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("{\"orderId\":\"ORD-001\"}");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteraction(r);
        return r;
    }

    /**
     * 同一会话内的链式记录：responseJson 是上游回复（含可提取字段值），
     * argValue 是下游工具参数值（与上游字段值相等即 HIGH 边）。
     */
    private void saveChainRecord(String recordId, String invocationId, long timestamp, String argValue, String responseJson) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(recordId);
        record.setSessionId("session-graph");
        record.setTimestamp(timestamp);
        record.setSeq(timestamp);
        record.setInvocationId(invocationId);
        record.setTemplateHash("hash-" + recordId);
        record.setUserInput("输入 " + recordId);
        record.setTurnIndex(0);
        record.setModelResponse(responseJson);
        List<ToolCall> calls = new ArrayList<>();
        if (argValue != null) {
            ToolCall call = new ToolCall();
            call.setToolName(invocationId);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("order_id", argValue);
            call.setArguments(args);
            calls.add(call);
        }
        record.setToolCalls(calls);
        record.setHasToolCalls(!calls.isEmpty());
        repository.saveInteraction(record);
    }

    private void seedCandidate(String invocationKey, InteractionRecord record) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        profile.setCandidateFingerprint(FingerprintExtractor.extract(record));
        repository.saveInvocationProfile(profile);
    }

    private int execute(String... args) throws Exception {
        stdoutBuffer = new ByteArrayOutputStream();
        stderrBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdoutBuffer, true, "UTF-8"));
        System.setErr(new PrintStream(stderrBuffer, true, "UTF-8"));
        return new CommandLine(new AgentAssert4jCli()).execute(args);
    }

    private String stdout() {
        return stdoutBuffer.toString();
    }

    private String stderr() {
        return stderrBuffer.toString();
    }

    /**
     * 报告契约的三要素之一：stdout 除报告本体外必须为空，且报告单行（消费方按行读取）。
     */
    private String singleLineReport() {
        String report = stdout().trim();
        assertFalse(report.isEmpty(), "--json 模式 stdout 必须产出报告本体");
        assertFalse(report.contains("\n"), "报告必须单行: " + report);
        return report;
    }

    @Nested
    @DisplayName("baseline 与 export")
    class BaselineAndExport {

        @Test
        @DisplayName("baseline --json 首跑：逐调用点明细报告，人类过程行不落 stdout")
        void baselineJson_firstRun_realReport() throws Exception {
            seedOneRecord();

            int exit = execute("baseline", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.baseline-report/1\""), "schema 标签开头: " + report);
            assertTrue(report.contains("\"force\":false"), report);
            assertTrue(report.contains("\"established\":1"), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"label\":\"queryOrder\""), report);
            assertTrue(report.contains("\"action\":\"created\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
            assertFalse(stdout().contains("baseline established"), "建档过程行是人类输出，不得污染 stdout: " + stdout());
            assertFalse(stdout().contains("Config: "), "配置披露在 --json 模式改走 stderr: " + stdout());
            assertTrue(stderr().contains("Config: "), "配置披露改走 stderr 供排障: " + stderr());
        }

        @Test
        @DisplayName("baseline --json 幂等重跑：established=0 且逐调用点 action=exists")
        void baselineJson_idempotentRerun() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("baseline", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.contains("\"established\":0"), "重复执行不得重复建档: " + report);
            assertTrue(report.contains("\"action\":\"exists\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
        }

        @Test
        @DisplayName("export --json：包元数据报告（对账 SHA-256），包文件照常落盘")
        void exportJson_metadataReport() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            Path packPath = tempDir.resolve("pack.json");

            int exit = execute("baseline", "export", "--db", dbPath, "--out", packPath.toString(), "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.export-report/1\""), report);
            assertTrue(report.contains("\"taskCount\":1"), report);
            assertTrue(report.contains("\"stepCount\":1"), report);
            assertTrue(report.contains("\"excluded\":[]"), report);
            assertTrue(report.contains("pack.json"), "报告必须携带输出路径: " + report);
            int shaStart = report.indexOf("\"sha256\":\"") + "\"sha256\":\"".length();
            String sha = report.substring(shaStart, shaStart + 64);
            assertTrue(sha.matches("[0-9a-f]{64}"), "SHA-256 必须是 64 位小写十六进制: " + sha);
            assertTrue(Files.exists(packPath), "包文件必须已写入");
        }
    }

    @Nested
    @DisplayName("裁决与回滚")
    class AdjudicationAndRollback {

        @Test
        @DisplayName("approve --json：候选提升为新基线，action=approve、版本推进、候选清空")
        void approveJson_promotesCandidate() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);

            int exit = execute("approve", "--db", dbPath, "--invocation", "queryOrder", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.adjudication/1\""), report);
            assertTrue(report.contains("\"action\":\"approve\""), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"versionTag\":\"v2\""), "approve 推进版本标签: " + report);
            assertTrue(report.contains("\"status\":\"BASELINE\""), report);
            assertTrue(report.contains("\"hasCandidate\":false"), report);
        }

        @Test
        @DisplayName("reject --json：丢弃候选保留旧基线，action=reject、版本不变")
        void rejectJson_keepsBaseline() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);

            int exit = execute("reject", "--db", dbPath, "--invocation", "queryOrder", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.adjudication/1\""), report);
            assertTrue(report.contains("\"action\":\"reject\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), "reject 保留旧基线版本: " + report);
            assertTrue(report.contains("\"hasCandidate\":false"), report);
        }

        @Test
        @DisplayName("approve --json 无候选：退出码 2，stdout 零污染，原因走 stderr")
        void approveJson_withoutCandidate_exit2StdoutClean() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("approve", "--db", dbPath, "--invocation", "queryOrder", "--json");

            assertEquals(2, exit);
            assertEquals("", stdout().trim(), "失败路径 stdout 不得产出任何内容: " + stdout());
            assertTrue(stderr().contains("No candidate"), "失败原因必须走 stderr: " + stderr());
        }

        @Test
        @DisplayName("rollback --json：恢复归档版本，报告携带恢复后状态与审批人")
        void rollbackJson_restoresArchivedVersion() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            execute("baseline", "--db", dbPath, "--force");

            int exit = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.rollback/1\""), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
            assertTrue(report.contains("\"status\":\"BASELINE\""), report);
            assertTrue(report.contains("\"approvedBy\":\""), "审批人留痕必须在报告中: " + report);
        }

        @Test
        @DisplayName("rollback --json 版本不存在：退出码 2，stdout 零污染，可选值提示走 stderr")
        void rollbackJson_missingVersion_exit2StdoutClean() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v9", "--json");

            assertEquals(2, exit);
            assertEquals("", stdout().trim(), "失败路径 stdout 不得产出任何内容: " + stdout());
            assertTrue(stderr().contains("no archived versions"), "可选值提示必须走 stderr: " + stderr());
        }
    }

    @Nested
    @DisplayName("巡检与目录")
    class PatrolAndCatalog {

        @Test
        @DisplayName("status --json：逐调用点巡检字段齐全，人类巡检表不落 stdout")
        void statusJson_patrolReport() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("status", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.status/1\""), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"label\":\"queryOrder\""), report);
            assertTrue(report.contains("\"status\":\"BASELINE\""), report);
            assertTrue(report.contains("\"hasCandidate\":false"), report);
            assertTrue(report.contains("\"archivedVersions\":\"\""), report);
            assertTrue(report.contains("\"uncovered\":[]"), "建档后无覆盖缺口: " + report);
            assertFalse(stdout().contains("Total: "), "人类巡检汇总行不得污染 stdout: " + stdout());
        }

        @Test
        @DisplayName("status --json 覆盖缺口：已录制未建档的标签进 uncovered 清单")
        void statusJson_uncoveredListed() throws Exception {
            seedOneRecord();

            int exit = execute("status", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.contains("\"uncovered\":[\"queryOrder\"]"), "覆盖缺口必须列清单而非只报数量: " + report);
        }

        @Test
        @DisplayName("graph show --json：边携带穿透节点，环与计数齐备，人类渲染不落 stdout")
        void graphShowJson_edgesAndCycles() throws Exception {
            saveChainRecord("r-1", "queryOrder", 1000L, null, "{\"order_id\":\"SO-77\",\"status\":\"shipped\"}");
            saveChainRecord("r-2", "refundOrder", 2000L, "SO-77", null);

            int exit = execute("graph", "show", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.graph/1\""), report);
            assertTrue(report.contains("\"edgeCount\":1"), report);
            assertTrue(report.contains("\"source\":\"invocation:queryOrder:hash-r-1\""), report);
            assertTrue(report.contains("\"target\":\"invocation:refundOrder:hash-r-2\""), report);
            assertTrue(report.contains("\"confidence\":\"HIGH\""), report);
            assertTrue(report.contains("\"throughNodes\":["), report);
            assertTrue(report.contains("\"cycles\":[]"), report);
            assertFalse(stdout().contains("Nodes ("), "人类渲染不得污染 stdout: " + stdout());
        }

        @Test
        @DisplayName("rules --json：内置行为目录单行输出")
        void rulesJson_behaviorCatalog() throws Exception {
            int exit = execute("rules", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.rules/1\""), report);
            assertTrue(report.contains("\"behaviors\":["), report);
            assertTrue(report.contains("\"name\":\"mustUseChinese\""), report);
        }
    }
}
