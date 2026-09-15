package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
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
        repository.saveInteractionIfAbsent(r);
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
        repository.saveInteractionIfAbsent(record);
    }

    private void seedCandidate(String invocationKey, InteractionRecord record) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        profile.setCandidateFingerprint(FingerprintExtractor.extract(record, null, null));
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

    /**
     * 失败包络断言三要素：schema 开头、错误码归属、hints 必填。
     */
    private void assertErrorEnvelope(String lastLine, String expectedErrorCode) {
        assertTrue(lastLine.startsWith("{\"schema\":\"agentassert4j.error/1\""), "失败须以 error/1 包络收尾: " + lastLine);
        assertTrue(lastLine.contains("\"status\":\"error\""), lastLine);
        assertTrue(lastLine.contains("\"errorCode\":\"" + expectedErrorCode + "\""), "错误码归属: " + lastLine);
        assertTrue(lastLine.contains("\"hints\":[\""), "失败路径 hints 必填: " + lastLine);
    }

    /**
     * 失败前已产出部分报告时的 stdout 收尾行——包络恒为最后一行。
     */
    private String lastStdoutLine() {
        String trimmed = stdout().trim();
        assertFalse(trimmed.isEmpty(), "--json 模式 stdout 必须有产出");
        String[] lines = trimmed.split("\r?\n");
        return lines[lines.length - 1];
    }

    @Nested
    @DisplayName("baseline 与 export")
    class BaselineAndExport {

        @Test
        @DisplayName("baseline --json 首跑：逐调用点明细报告，人类过程行不落 stdout")
        void baselineJson_firstRun_realReport() throws Exception {
            seedOneRecord();

            int exit = execute("baseline", "--db", dbPath, "--ref", "abc1234", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.baseline-report/1\""), "schema 标签开头: " + report);
            assertTrue(report.contains("\"force\":false"), report);
            assertTrue(report.contains("\"established\":1"), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"label\":\"queryOrder\""), report);
            assertTrue(report.contains("\"action\":\"created\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
            assertTrue(report.contains("\"codeRef\":\"abc1234\""), report);
            assertFalse(stdout().contains("baseline established"), "建档过程行是人类输出，不得污染 stdout: " + stdout());
            assertFalse(stdout().contains("Config: "), "配置披露在 --json 模式改走 stderr: " + stdout());
            assertTrue(stderr().contains("Config: "), "配置披露改走 stderr 供排障: " + stderr());
        }

        @Test
        @DisplayName("baseline --json 幂等重跑：established=0 且逐调用点 action=exists")
        void baselineJson_idempotentRerun() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath, "--ref", "abc1234");

            int exit = execute("baseline", "--db", dbPath, "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.contains("\"established\":0"), "重复执行不得重复建档: " + report);
            assertTrue(report.contains("\"action\":\"exists\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
            assertTrue(report.contains("\"codeRef\":\"abc1234\""), "exists 条目回显已落库的锚而非本次声明: " + report);
        }

        @Test
        @DisplayName("baseline --json --invocation：selection 段随报告出境（机器通道可见扇出）")
        void baselineJson_selectionDisclosesFanOut() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            InteractionRecord split = new InteractionRecord();
            split.setRecordId("rec-split");
            split.setSessionId("session-2");
            split.setTimestamp(2000L);
            split.setSeq(2L);
            split.setInvocationId("queryOrder");
            split.setTemplateHash("hash-new");
            split.setInvocationKey("invocation:queryOrder:hash-new");
            split.setUserInput("查订单");
            split.setTurnIndex(0);
            split.setModelResponse("{\"orderId\":\"ORD-002\"}");
            split.setToolCalls(new ArrayList<>());
            split.setHasToolCalls(false);
            repository.saveInteractionIfAbsent(split);

            int narrowed = execute("baseline", "--db", dbPath, "--invocation", "queryOrder", "--json");

            assertEquals(0, narrowed);
            String report = singleLineReport();
            assertTrue(report.contains("\"selection\":{\"requested\":\"queryOrder\",\"matched\":2"), "机器面必须看到一次调用覆盖了几个键: " + report);

            int all = execute("baseline", "--db", dbPath, "--json");
            assertEquals(0, all);
            assertFalse(singleLineReport().contains("\"selection\":"), "全量缺省无选择器，不出 selection 段");
        }

        @Test
        @DisplayName("export --json：包元数据报告（对账 SHA-256），包文件照常落盘")
        void exportJson_metadataReport() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            Path packPath = tempDir.resolve("pack.json");

            int exit = execute("baseline", "export", "--db", dbPath, "--out", packPath.toString(), "--ref", "abc1234", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.export-report/1\""), report);
            assertTrue(report.contains("\"taskCount\":1"), report);
            assertTrue(report.contains("\"stepCount\":1"), report);
            assertTrue(report.contains("\"codeRef\":\"abc1234\""), report);
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
        @DisplayName("accept --json：候选提升为新基线，action=accept、版本推进、候选清空")
        void acceptJson_promotesCandidate() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);

            int exit = execute("accept", "--db", dbPath, "--invocation", "queryOrder", "--ref", "def5678", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.adjudication/1\""), report);
            assertTrue(report.contains("\"action\":\"accept\""), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"versionTag\":\"v2\""), "accept 推进版本标签: " + report);
            assertTrue(report.contains("\"codeRef\":\"def5678\""), report);
            assertTrue(report.contains("\"status\":\"BASELINE\""), report);
            assertTrue(report.contains("\"hasCandidate\":false"), report);
        }

        @Test
        @DisplayName("accept 以 agent: 身份申报 → audit 时间线列出该治理写（人类 actor 不进清单）")
        void acceptAgentMarked_listedInAudit() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath, "--approver", "agent:setup");
            seedCandidate("invocation:queryOrder:hash-old", record);

            int exit = execute("accept", "--db", dbPath, "--invocation", "queryOrder", "--approver", "agent:codex", "--ref", "def5678", "--json");

            assertEquals(0, exit);

            assertEquals(0, execute("audit", "--db", dbPath));
            String audit = stdout();
            assertTrue(audit.contains("[establish]"), "agent 建档事件必须在时间线: " + audit);
            assertTrue(audit.contains("[accept]"), audit);
            assertTrue(audit.contains("agent:codex"), audit);
            assertTrue(audit.contains("(ref def5678)"), audit);

            assertEquals(0, execute("audit", "--db", dbPath, "--json"));
            String json = singleLineReport();
            assertTrue(json.startsWith("{\"schema\":\"agentassert4j.audit/1\""), json);
            assertTrue(json.contains("\"verb\":\"accept\""), json);
            assertTrue(json.contains("\"actor\":\"agent:codex\""), json);
            assertTrue(json.contains("\"happenedAt\":"), json);
        }

        @Test
        @DisplayName("reject/rollback 的 agent 事件进 audit 时间线（此前不可见）")
        void rejectAndRollback_eventsListedInAudit() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);
            execute("reject", "--db", dbPath, "--invocation", "queryOrder", "--approver", "agent:codex", "--json");
            seedCandidate("invocation:queryOrder:hash-old", record);
            execute("accept", "--db", dbPath, "--invocation", "queryOrder", "--approver", "agent:codex", "--json");
            execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1", "--approver", "agent:codex", "--json");

            assertEquals(0, execute("audit", "--db", dbPath));
            String audit = stdout();
            assertTrue(audit.contains("[reject]") && audit.contains("[rollback]"), "reject/rollback 事件必须可见: " + audit);
            assertTrue(audit.contains("[accept]"), audit);
            assertTrue(audit.contains("v1"), audit);
            assertFalse(audit.contains("[establish]"), "人类 actor（默认 OS 身份建档）不进 agent 透镜: " + audit);
        }

        @Test
        @DisplayName("status/1 审批溯源：approvedBy/approvedAt 随画像回读（「谁批的」任何面可答）")
        void statusJson_exposesApprover() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath, "--approver", "wang", "--ref", "abc1234");

            assertEquals(0, execute("status", "--db", dbPath, "--json"));
            String report = singleLineReport();
            assertTrue(report.contains("\"approvedBy\":\"wang\""), "审批人必须可读: " + report);
            assertTrue(report.contains("\"approvedAt\":"), "审批时刻必须可读: " + report);
            assertFalse(report.contains("\"approvedAt\":null"), "建档即盖章，approvedAt 不缺席: " + report);

            assertEquals(0, execute("status", "--db", dbPath));
            String human = stdout();
            assertTrue(human.contains("approver"), "人读 approver 列头必须在场: " + human);
            assertTrue(human.contains("wang"), "人读 approver 列值必须在场: " + human);
        }

        @Test
        @DisplayName("rollback 守卫与披露：目标=活动版本拒绝指路 reject；跨版本回滚清候选必须披露")
        void rollbackGuards_activeTargetRefusedAndCandidateDisclosed() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);
            int accepted = execute("accept", "--db", dbPath, "--invocation", "queryOrder", "--json");
            assertEquals(0, accepted, "accept 应成功: " + stdout() + stderr());
            int firstRollback = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1", "--json");
            assertEquals(0, firstRollback, "回滚到 v1 应成功: " + stdout() + stderr());

            int refused = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1", "--json");

            assertEquals(2, refused, "回滚到活动版本必须拒绝（清候选有专门动词）");
            String envelope = singleLineReport();
            assertTrue(envelope.contains("already the active baseline"), "拒绝必须言明空回滚: " + envelope);
            assertTrue(envelope.contains("reject"), "拒绝必须指路 reject: " + envelope);

            seedCandidate("invocation:queryOrder:hash-old", record);
            assertEquals(0, execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v2", "--json"));
            assertTrue(singleLineReport().contains("\"candidateDiscarded\":true"), "回滚清候选必须在回执披露: " + singleLineReport());
        }

        @Test
        @DisplayName("status 人读归档列：活动版本打 * 标记，机器通道不标记")
        void statusHuman_archivedColumnMarksActive() throws Exception {
            InteractionRecord record = seedOneRecord();
            execute("baseline", "--db", dbPath);
            seedCandidate("invocation:queryOrder:hash-old", record);
            execute("accept", "--db", dbPath, "--invocation", "queryOrder");
            execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1");

            assertEquals(0, execute("status", "--db", dbPath));
            String human = stdout();
            assertTrue(human.contains("v1*"), "活动 tag 必须带 *（回滚恢复后活动号仍在归档列）: " + human);
            assertTrue(human.contains("rollback targets"), "归档列例必须在场: " + human);

            assertEquals(0, execute("status", "--db", dbPath, "--json"));
            assertFalse(singleLineReport().contains("v1*"), "机器通道不标记（versionTag 即活动版）: " + singleLineReport());
        }

        @Test
        @DisplayName("audit 空清单：无 agent 治理写时报 no writes")
        void audit_empty() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            assertEquals(0, execute("audit", "--db", dbPath, "--json"));
            assertEquals("{\"schema\":\"agentassert4j.audit/1\",\"writes\":[]}", singleLineReport());

            assertEquals(0, execute("audit", "--db", dbPath));
            assertTrue(stdout().contains("No agent-driven governance writes found."), stdout());
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
        @DisplayName("accept --json 无候选可裁决：退出码 2，stdout 以 E-NO-DATA 包络收尾")
        void acceptJson_withoutCandidate_errorEnvelope() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("accept", "--db", dbPath, "--invocation", "queryOrder", "--json");

            assertEquals(2, exit);
            assertErrorEnvelope(singleLineReport(), "E-NO-DATA");
            assertTrue(stderr().contains("No candidate"), "现象必须同时走 stderr 供人排障: " + stderr());
        }

        @Test
        @DisplayName("人读模式失败：stdout 零失败内容契约不变（与 --json 包络并行不悖）")
        void humanMode_failure_stdoutStaysClean() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("accept", "--db", dbPath, "--invocation", "queryOrder");

            assertEquals(2, exit);
            assertFalse(stdout().contains("No candidate"), "人读失败路径不得向 stdout 输出失败内容: " + stdout());
            assertTrue(stderr().contains("No candidate"), "失败原因走 stderr: " + stderr());
        }

        @Test
        @DisplayName("rollback --json：恢复归档版本，报告携带恢复后状态与审批人")
        void rollbackJson_restoresArchivedVersion() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath, "--ref", "abc1234");
            execute("baseline", "--db", dbPath, "--force", "--ref", "def5678");

            int exit = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.rollback/1\""), report);
            assertTrue(report.contains("\"invocationKey\":\"invocation:queryOrder:hash-old\""), report);
            assertTrue(report.contains("\"versionTag\":\"v1\""), report);
            assertTrue(report.contains("\"status\":\"BASELINE\""), report);
            assertTrue(report.contains("\"approvedBy\":\""), "审批人留痕必须在报告中: " + report);
            assertTrue(report.contains("\"codeRef\":\"abc1234\""), report);
        }

        @Test
        @DisplayName("rollback --json 版本不存在：退出码 2，包络携带现象与可选值指引，stderr 同步")
        void rollbackJson_missingVersion_errorEnvelope() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v9", "--json");

            assertEquals(2, exit);
            String envelope = singleLineReport();
            assertErrorEnvelope(envelope, "E-NO-DATA");
            assertTrue(envelope.contains("no archived versions"), "包络 message 携带可选值指引: " + envelope);
            assertTrue(stderr().contains("no archived versions"), "可选值提示必须同时走 stderr: " + stderr());
        }

        @Test
        @DisplayName("rollback --json 包络转义：版本值含引号不破坏 JSON 单行结构")
        void rollbackJson_envelopeEscapesQuotes() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v\"9", "--json");

            assertEquals(2, exit);
            String envelope = singleLineReport();
            assertErrorEnvelope(envelope, "E-NO-DATA");
            assertTrue(envelope.contains("v\\\"9"), "引号必须以 \\\" 形态转义: " + envelope);
            RecursiveJsonParser.parse(envelope);
        }

        @Test
        @DisplayName("rollback 人读：审批事实按在场渲染，未经审批的基线不留 null 痕")
        void rollbackHuman_factsRenderOnlyWhenPresent() throws Exception {
            seedOneRecord();
            // 经 core 以 null 操作者建档：CLI 路径审批人恒有 OS 用户兜底，
            // approvedBy=null 只能来自 API 侧，属「未经审批链盖章」的合法形态
            new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream()), null, "abc1234", false, null, null, null, null);
            execute("baseline", "--db", dbPath, "--force", "--ref", "def5678");

            assertEquals(0, execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v1"));
            String restored = stdout();
            assertTrue(restored.contains(" → v1 (ref abc1234)"), restored);
            assertFalse(restored.contains("approver"), "null 审批人整段省略而非渲染 null: " + restored);
            assertFalse(restored.contains("null"), restored);

            assertEquals(0, execute("rollback", "--db", dbPath, "--invocation", "queryOrder", "--version", "v2"));
            String both = stdout();
            assertTrue(both.contains("(approver ") && both.contains(", ref def5678)"), both);
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
            assertTrue(report.contains("\"codeRef\":\""), report);
            assertTrue(report.contains("\"health\":{\"labelSplits\":0,\"selfEstablishedTasks\":1,\"multiStepUnlabeledChains\":0}"), report);
            assertTrue(report.contains("\"uncovered\":[]"), "建档后无覆盖缺口: " + report);
            assertFalse(stdout().contains("Total: "), "人类巡检汇总行不得污染 stdout: " + stdout());
        }

        @Test
        @DisplayName("status --json --invocation：缩域两通道一致生效（命中保留、未命中排除）")
        void statusJson_invocationNarrows() throws Exception {
            seedOneRecord();
            InteractionRecord other = new InteractionRecord();
            other.setRecordId("rec-other");
            other.setSessionId("session-other");
            other.setTimestamp(2000L);
            other.setSeq(2L);
            other.setInvocationId("refund");
            other.setTemplateHash("hash-refund");
            other.setUserInput("退款");
            other.setTurnIndex(0);
            other.setModelResponse("ok");
            other.setToolCalls(new ArrayList<>());
            repository.saveInteractionIfAbsent(other);
            execute("baseline", "--db", dbPath);

            int hit = execute("status", "--db", dbPath, "--json", "--invocation", "queryOrder");
            assertEquals(0, hit);
            String narrowed = singleLineReport();
            assertTrue(narrowed.contains("\"label\":\"queryOrder\""), "命中标签必须在场: " + narrowed);
            assertFalse(narrowed.contains("\"label\":\"refund\""), "未命中标签必须被排除: " + narrowed);

            int all = execute("status", "--db", dbPath, "--json");
            assertEquals(0, all);
            assertTrue(singleLineReport().contains("\"label\":\"refund\""), "缺省仍为全量快照");
        }

        @Test
        @DisplayName("status --json --invocation 换算：Note 行走 err，stdout 恒单行 JSON（通道纯净性钉）")
        void statusJson_conversionNoteRoutesToStderr() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);

            int exit = execute("status", "--db", dbPath, "--json", "--invocation", "invocation:queryOrder");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.status/1\""), report);
            assertFalse(report.contains("Note:"), "换算提示不得污染 stdout: " + report);
            assertFalse(stdout().contains("Config:"), "配置披露不得污染 stdout");
            assertTrue(stderr().contains("matched invocationKey prefix"), "换算提示必须走 err: " + stderr());
        }

        @Test
        @DisplayName("record show --json：latencyMs 与 metadata（含 taskKey）随视图回显")
        void recordShow_projectsLatencyAndMetadata() throws Exception {
            // 全字段就位后再落库——INSERT OR IGNORE 幂等会让「先存后改」静默无效
            InteractionRecord r = seedOneRecord();
            r.setRecordId("rec-meta");
            r.setLatencyMs(1234L);
            r.setMetadata("{\"taskKey\":\"order-probe\",\"env\":\"staging\"}");
            repository.saveInteractionIfAbsent(r);

            int exit = execute("record", "show", "--db", dbPath, "--record-id", "rec-meta", "--json");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.record-view/1\""), report);
            assertTrue(report.contains("\"latencyMs\":1234"), report);
            assertTrue(report.contains("\"taskKey\":\"order-probe\""), "metadata 必须原样投影（taskKey 住里面）: " + report);
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
        @DisplayName("status --json --invocation 缩域：uncovered/unestablished 以全库为准，不得把域外已建档键误报为缺口")
        void statusJson_narrowingKeepsGlobalGapSemantics() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            InteractionRecord pending = new InteractionRecord();
            pending.setRecordId("rec-pending");
            pending.setSessionId("session-pending");
            pending.setTimestamp(2000L);
            pending.setSeq(2L);
            pending.setInvocationId("refund");
            pending.setTemplateHash("hash-refund");
            pending.setInvocationKey("invocation:refund:hash-refund");
            pending.setUserInput("退款");
            pending.setTurnIndex(0);
            pending.setModelResponse("ok");
            pending.setToolCalls(new ArrayList<>());
            pending.setHasToolCalls(false);
            repository.saveInteractionIfAbsent(pending);

            int narrowed = execute("status", "--db", dbPath, "--json", "--invocation", "queryOrder");

            assertEquals(0, narrowed);
            String report = singleLineReport();
            assertTrue(report.contains("\"uncovered\":[]"), "缩域不得把选择集内已建档标签报成 uncovered: " + report);
            assertTrue(report.contains("\"unestablished\":[]"), "缩域不得把域外键报成 unestablished（语义反转）: " + report);

            assertEquals(0, execute("status", "--db", dbPath, "--json"));
            String full = singleLineReport();
            assertTrue(full.contains("\"uncovered\":[\"refund\"]"), "全量面如实报未建档标签: " + full);
            assertTrue(full.contains("\"unestablished\":[{\"invocationKey\":\"invocation:refund:hash-refund\""), "全量面列出未建档键: " + full);
        }

        @Test
        @DisplayName("status --json --invocation 缩域含未建档键：正向缺口保留（信息不因缩域丢失）")
        void statusJson_narrowedUnestablishedStillListed() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            InteractionRecord split = new InteractionRecord();
            split.setRecordId("rec-split");
            split.setSessionId("session-2");
            split.setTimestamp(2000L);
            split.setSeq(2L);
            split.setInvocationId("queryOrder");
            split.setTemplateHash("hash-new");
            split.setInvocationKey("invocation:queryOrder:hash-new");
            split.setUserInput("查订单");
            split.setTurnIndex(0);
            split.setModelResponse("{\"orderId\":\"ORD-002\"}");
            split.setToolCalls(new ArrayList<>());
            split.setHasToolCalls(false);
            repository.saveInteractionIfAbsent(split);

            int exit = execute("status", "--db", dbPath, "--json", "--invocation", "queryOrder");

            assertEquals(0, exit);
            String report = singleLineReport();
            assertTrue(report.contains("\"unestablished\":[{\"invocationKey\":\"invocation:queryOrder:hash-new\""), "缩域内真实未建档键必须仍在场: " + report);
            assertTrue(report.contains("\"uncovered\":[]"), "标签首键已有基线，标签级 uncovered 为空: " + report);
        }

        @Test
        @DisplayName("graph show --json：边、置信与环计数齐备，人类渲染不落 stdout")
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
            assertFalse(report.contains("throughNodes"), "透传字段已随穿透压缩退役: " + report);
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

    @Nested
    @DisplayName("机器失败包络与 doctor 机器通道")
    class ErrorEnvelopeAndDoctorMachineChannel {

        @Test
        @DisplayName("replay --json 用法错误：--full-chain 无 --re-drive 出 E-USAGE 包络")
        void replayJson_usageError_envelope() throws Exception {
            int exit = execute("replay", "--db", dbPath, "--full-chain", "--json");

            assertEquals(2, exit);
            String envelope = singleLineReport();
            assertErrorEnvelope(envelope, "E-USAGE");
            assertTrue(envelope.contains("--re-drive"), "现象与指引必须点名缺失的旗标: " + envelope);
            assertTrue(envelope.contains("agentassert4j replay --re-drive"), "nextAction 给出可执行命令: " + envelope);
        }

        @Test
        @DisplayName("replay --json --ci 未建档守卫：拒绝判定出 E-GUARD 包络（漂移报告在先，包络收尾）")
        void replayJson_ciGuard_envelopeAfterDriftReport() throws Exception {
            seedOneRecord();

            int exit = execute("replay", "--db", dbPath, "--ci", "--json");

            assertEquals(2, exit);
            assertTrue(stdout().contains("agentassert4j.task-report/1"), "漂移检测报告先行产出: " + stdout());
            String envelope = lastStdoutLine();
            assertErrorEnvelope(envelope, "E-GUARD");
            assertTrue(envelope.contains("baseline"), "守卫拒绝必须指路建档: " + envelope);
        }

        @Test
        @DisplayName("verify --json 覆盖缺口：报告在先、E-NO-DATA 包络收尾，exit 2")
        void verifyJson_uncovered_envelopeAfterReport() throws Exception {
            seedOneRecord();
            execute("baseline", "--db", dbPath);
            Path packPath = tempDir.resolve("pack.json");
            execute("baseline", "export", "--db", dbPath, "--out", packPath.toString());
            // 全新空库：包任务全部未执行 = 覆盖缺口
            Path emptyDb = tempDir.resolve("empty.db");

            int exit = execute("verify", "--pack", packPath.toString(), "--db", emptyDb.toString(), "--json");

            assertEquals(2, exit);
            assertTrue(stdout().contains("agentassert4j.verify-report/1"), "验收报告先行产出: " + stdout());
            assertTrue(stdout().contains("\"health\":{\"labelSplits\":0,\"selfEstablishedTasks\":0,\"multiStepUnlabeledChains\":0}"), "验收报告携带出口健康三计数: " + stdout());
            String envelope = lastStdoutLine();
            assertErrorEnvelope(envelope, "E-NO-DATA");
            assertTrue(envelope.contains("no local execution"), "包络点明缺口语义: " + envelope);
        }

        @Test
        @DisplayName("doctor --json：三段体检机器报告，人类渲染不落 stdout")
        void doctorJson_healthReport() throws Exception {
            seedKeyedRecord();

            int exit = execute("doctor", "--db", dbPath, "--json");

            assertEquals(0, exit, "doctor 不承 CI gating 职责，恒 0");
            String report = singleLineReport();
            assertTrue(report.startsWith("{\"schema\":\"agentassert4j.doctor/1\""), report);
            assertTrue(report.contains("\"identity\":{\"skeletonCount\":0"), "未声明骨架的记录不进骨架族: " + report);
            assertTrue(report.contains("\"unestablishedInvocations\":1"), "建档前调用点属未收编: " + report);
            assertTrue(report.contains("\"recordsMissingTemplateHash\":0"), report);
            assertTrue(report.contains("\"expectationMismatches\":0"), report);
            assertFalse(stdout().contains("Identity check:"), "人类渲染不得污染 stdout: " + stdout());
        }

        /**
         * 带落库调用点键的记录——足迹枚举只认存储键（enrich 写入口径），
         * 机器通道的未收编计数以此为前提。
         */
        private void seedKeyedRecord() {
            InteractionRecord record = new InteractionRecord();
            record.setRecordId("rec-1");
            record.setSessionId("session-1");
            record.setTimestamp(1000L);
            record.setSeq(1L);
            record.setInvocationId("queryOrder");
            record.setInvocationKey("invocation:queryOrder:hash-old");
            record.setTemplateHash("hash-old");
            record.setUserInput("查订单");
            record.setTurnIndex(0);
            record.setModelResponse("{\"orderId\":\"ORD-001\"}");
            record.setToolCalls(new ArrayList<>());
            record.setHasToolCalls(false);
            repository.saveInteractionIfAbsent(record);
        }
    }
}
