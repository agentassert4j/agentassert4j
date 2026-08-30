package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交付验收的单元测试 — 包格式、export→verify 往返、版本守卫、
 * 覆盖缺口、样本强制脱敏、记录路径与指纹路径判定等价。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class VerifyExportTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("verify.db").toString());
        repository.initialize();
        output = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private void saveRecord(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String label, String response, String servedModel) {
        saveRecord(recordId, sessionId, timestamp, userInput, invocationKey, label, "h-" + label, response, servedModel);
    }

    private void saveRecord(String recordId, String sessionId, long timestamp, String userInput, String invocationKey, String label, String templateHash, String response, String servedModel) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        // 键与生产 enrich 同口径派生（存储键与现算键不得分叉）
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setModelResponse(response);
        r.setServedModel(servedModel);
        repository.saveInteraction(r);
    }

    private void saveRecord(String recordId, StorageRepository repo, long timestamp, String userInput, String invocationKey, String label, String response, String servedModel) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("s1");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey("invocation:" + label + ":h-" + label);
        r.setInvocationId(label);
        r.setTemplateHash("h-" + label);
        r.setModelResponse(response);
        r.setServedModel(servedModel);
        repo.saveInteraction(r);
    }

    private String exportPack(String dbPath, boolean includeSamples) throws Exception {
        BaselineExportCommand command = new BaselineExportCommand();
        command.db = dbPath;
        command.out = new PrintStream(output, true);
        command.err = new PrintStream(output, true);
        command.includeSamples = includeSamples;
        command.outPath = tempDir.resolve("pack.json").toString();
        Integer exit = command.call();
        assertEquals(0, exit, "导出应成功: " + output);
        return new String(Files.readAllBytes(Paths.get(command.outPath)), StandardCharsets.UTF_8);
    }

    private void establishBaselines() {
        new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);
    }

    @Test
    @DisplayName("包 JSON 的 schema/meta/step 键集与字面形态固定")
    void goldenPackFormat() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();

        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        assertTrue(json.contains("\"schema\":\"agentassert4j.acceptance-pack/1\""), json);
        assertTrue(json.contains("\"judgmentSemantics\":\"det-v1\""), json);
        assertTrue(json.contains("\"taskKey\":\"查订单\""), json);
        assertTrue(json.contains("\"invocationKey\":\"invocation:verdict:h-verdict\""), json);
        assertTrue(json.contains("\"recordId\":\"r1\""), json);
        assertFalse(json.contains("sampleInput"), "缺省不带样本");
        Object parsed = RecursiveJsonParser.parse(json);
        Map<?, ?> root = (Map<?, ?>) parsed;
        Map<?, ?> task = ((List<Map<?, ?>>) root.get("tasks")).get(0);
        Map<?, ?> step = ((List<Map<?, ?>>) task.get("steps")).get(0);
        Map<?, ?> fp = (Map<?, ?>) step.get("fingerprint");
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("toolCallSet", "toolParamTypes", "outputContentType", "outputFieldPaths", "outputFieldTypeMap", "textLengthMagnitude", "requiredKeywords", "forbiddenKeywords", "regexPatterns", "declaredBehaviors", "hasError")), fp.keySet(), "指纹键集固定");
        assertTrue(json.contains("\"servedModel\":\"dev-model\""), json);
    }

    @Test
    @DisplayName("export→verify 同环境往返：全 PASS 退出码 0")
    void roundtrip_sameEnvironment_pass() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String digest = io.github.agentassert4j.util.HashUtil.sha256(json);

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, digest, null, null);

        assertEquals(0, exit, "同环境往返必须 PASS: " + output);
        assertTrue(output.toString().contains("PASS 1"), output.toString());
    }

    @Test
    @DisplayName("跨模型验收：结构同 servedModel 异 → PASS 且标注跨模型")
    void crossModel_structureSame_pass() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "customer-local-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);

            int exit = runner.run(json, "digest", null, null);

            assertEquals(0, exit, "跨模型+结构一致 → PASS");
            assertTrue(output.toString().contains("跨模型"), "必须标注跨模型: " + output);
        } finally {
            customerDb.close();
        }
    }

    @Test
    @DisplayName("版本守卫：包判定语义与当前引擎不一致 → exit 2 拒绝判定")
    void versionGuard_rejects() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String tampered = json.replace("\"judgmentSemantics\":\"det-v1\"", "\"judgmentSemantics\":\"det-v0\"");

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);

        assertEquals(2, runner.run(tampered, "digest", null, null));
        assertTrue(output.toString().contains("版本守卫"), output.toString());
    }

    @Test
    @DisplayName("覆盖缺口：包任务未在本地执行 → exit 2")
    void uncoveredTask_exit2() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository emptyDb = new SqliteStorageRepository(tempDir.resolve("empty.db").toString());
        emptyDb.initialize();
        try {
            VerifyRunner runner = new VerifyRunner(emptyDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            assertEquals(2, runner.run(json, "digest", null, null), "包任务未执行 = 证据缺口");
        } finally {
            emptyDb.close();
        }
    }

    @Test
    @DisplayName("include-samples：样本强制脱敏为掩码")
    void includeSamples_forcedMasked() throws Exception {
        saveRecord("r1", "s1", 1000L, "我的密码是 secret123", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();

        String json = exportPack(tempDir.resolve("verify.db").toString(), true);

        // 样本整值掩码；任务键=请求原文属配对语义（敏感任务用声明 taskKey 规避）
        assertTrue(json.contains("***"), "样本必须整值掩码: " + json);
        assertFalse(json.contains("\"sampleInput\":\"我的密码是 secret123\""), "样本字段不得保留原文: " + json);
    }

    @Test
    @DisplayName("判定等价：记录路径与指纹路径对同一数据给出一致判定")
    void referenceEquivalence() throws Exception {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"status\":\"FAILED\"}", "dev-model");
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        VerifyRunner packRunner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int packExit = packRunner.run(json, "digest", null, null);
        assertEquals(1, packExit, "结构偏差 → 1");
        assertTrue(output.toString().contains("CHANGED"), output.toString());

        List<io.github.agentassert4j.model.TaskChain> chains = io.github.agentassert4j.algorithm.TaskChainView.resolveAll(repository);
        io.github.agentassert4j.model.TaskChain baseline = chains.get(0);
        io.github.agentassert4j.model.TaskChain newChain = chains.get(chains.size() - 1);
        io.github.agentassert4j.result.TaskAlignment alignment = io.github.agentassert4j.algorithm.TaskAligner.align(baseline, newChain, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig());
        assertEquals(io.github.agentassert4j.result.Verdict.CHANGED, alignment.getVerdict(), "记录路径与指纹路径判定一致");
    }
}
