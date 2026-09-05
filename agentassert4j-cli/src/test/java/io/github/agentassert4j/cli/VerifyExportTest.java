package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.HashUtil;
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
import java.util.Arrays;
import java.util.HashSet;
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
        saveRecord(recordId, repo, timestamp, userInput, invocationKey, label, "h-" + label, response, servedModel);
    }

    private void saveRecord(String recordId, StorageRepository repo, long timestamp, String userInput, String invocationKey, String label, String templateHash, String response, String servedModel) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("s1");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        r.setInvocationKey(invocationKey);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
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
        assertEquals(new HashSet<>(Arrays.asList("toolCallSet", "toolParamTypes", "outputContentType", "outputFieldPaths", "outputFieldTypeMap", "textLengthMagnitude", "requiredKeywords", "forbiddenKeywords", "regexPatterns", "declaredBehaviors", "hasError")), fp.keySet(), "指纹键集固定");
        assertTrue(json.contains("\"servedModel\":\"dev-model\""), json);
    }

    @Test
    @DisplayName("export→verify 同环境往返：全 PASS 退出码 0")
    void roundtrip_sameEnvironment_pass() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String digest = HashUtil.sha256(json);

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, digest, null, null, false);

        assertEquals(0, exit, "同环境往返必须 PASS: " + output);
        assertTrue(output.toString().contains("PASS 1"), output.toString());
    }

    @Test
    @DisplayName("verify --dry-run：只读预演配对情况，零判定零写入")
    void dryRun_readOnly() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String digest = HashUtil.sha256(json);

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, digest, null, null, true);

        assertEquals(0, exit);
        assertTrue(output.toString().contains("Verification dry-run"), "dry-run 必须输出预演标题: " + output);
        assertTrue(output.toString().contains("pairs with local chain"), "dry-run 必须列出配对情况: " + output);
        assertFalse(output.toString().contains("PASS 1"), "dry-run 不得产出判定汇总: " + output);
    }

    @Test
    @DisplayName("同键多记录往返：每步骤携带各自记录的指纹 → 全 PASS")
    void roundtrip_multiRecordSameKey_pass() throws Exception {
        // 同一调用点键的两条记录（同模板、输出结构异质——模板复用形态），
        // 画像指纹只来自规范序首条；步骤指纹若取画像值，第二步必然假 CHANGED
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        saveRecord("r2", "s1", 2000L, null, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\",\"extra\":1}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String digest = HashUtil.sha256(json);

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, digest, null, null, false);

        assertEquals(0, exit, "同环境往返必 PASS（步骤指纹必须逐记录提取，画像指纹口径下第二步假 CHANGED）: " + output);
    }

    @Test
    @DisplayName("同任务键多链：只导出链首时间最新的链")
    void export_foldsToLatestChainPerTaskKey() throws Exception {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();

        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        Object parsed = RecursiveJsonParser.parse(json);
        List<?> tasks = (List<?>) ((Map<?, ?>) parsed).get("tasks");
        assertEquals(1, tasks.size(), "同任务键只导出最新链: " + json);
        Map<?, ?> task = (Map<?, ?>) tasks.get(0);
        Map<?, ?> step = (Map<?, ?>) ((List<?>) task.get("steps")).get(0);
        assertEquals("n1", step.get("recordId"), "必须是最新链的步骤");
    }

    @Test
    @DisplayName("配对精确相等：前缀同名的本地链不得冒充包任务证据")
    void pairing_exactMatchOnly() throws Exception {
        saveRecord("r1", "s1", 1000L, "V1", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            // 客户侧只执行了 "V10"（前缀同名任务）——它不是 "V1" 的证据
            saveRecord("c1", customerDb, 9000L, "V10", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);

            int exit = runner.run(json, "digest", null, null, false);

            assertEquals(2, exit, "包任务无精确匹配链 = 覆盖缺口（前缀同名链不得冒充）: " + output);
            String report = output.toString();
            assertTrue(report.contains("coverage gaps 1"), "V1 必须列为覆盖缺口: " + report);
            assertTrue(report.contains("out-of-scope chains 1"), "V10 链必须列为范围外: " + report);
        } finally {
            customerDb.close();
        }
    }

    @Test
    @DisplayName("范围外链给因果提示：新录制未建档/未入包的常见成因就地指路")
    void verifyHint_unmatchedLocal() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            // 包导出之后又录了新任务：范围外链的典型成因
            saveRecord("c2", customerDb, 9500L, "查物流", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);

            int exit = runner.run(json, "digest", null, null, false);

            assertEquals(0, exit);
            String report = output.toString();
            assertTrue(report.contains("out-of-scope chains 1"), report);
            assertTrue(report.contains("usually come from recordings made after the pack export"), "范围外必须带因果提示: " + report);

            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            VerifyRunner jsonRunner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);
            assertEquals(0, jsonRunner.run(json, "digest", null, null, false));
            assertTrue(jsonOut.toString().contains("\"hints\":[\"Out-of-scope local chains"), "JSON 报告必须携带 hints 供机器消费: " + jsonOut);
        } finally {
            customerDb.close();
        }
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

            int exit = runner.run(json, "digest", null, null, false);

            assertEquals(0, exit, "跨模型+结构一致 → PASS");
            assertTrue(output.toString().contains("Cross-model"), "必须标注跨模型: " + output);
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

        assertEquals(2, runner.run(tampered, "digest", null, null, false));
        assertTrue(output.toString().contains("Version guard"), output.toString());
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
            assertEquals(2, runner.run(json, "digest", null, null, false), "包任务未执行 = 证据缺口");
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
    @DisplayName("参照等价：包内指纹与库内记录路径喂同一对齐核，各自语义下判定正确")
    void referenceEquivalence() throws Exception {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "{\"status\":\"FAILED\"}", "dev-model");
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        // 包路径：包=最新链的行为证据（折叠+逐记录指纹），本地同链现场重提 → 自洽 PASS
        VerifyRunner packRunner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int packExit = packRunner.run(json, "digest", null, null, false);
        assertEquals(0, packExit, "包内指纹与本地重提同口径，同链必自洽 PASS: " + output);

        // 库内路径：两条链喂同一对齐核 → 两轮间的结构变化 = CHANGED
        List<TaskChain> chains = TaskChainView.resolveAll(repository);
        TaskChain baseline = chains.get(0);
        TaskChain newChain = chains.get(chains.size() - 1);
        TaskAlignment alignment = TaskAligner.align(baseline, newChain, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig());
        assertEquals(Verdict.CHANGED, alignment.getVerdict(), "记录路径捕捉两轮间结构变化");
    }

    @Test
    @DisplayName("verify 跨版本：同标签异模板版本 → 按标签配对 PASS + 版本注记，pack schema 不变")
    void verify_crossVersion_sameBehavior_pass() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-old", "verdict", "h-old", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        assertFalse(json.contains("\"invocationId\""), "acceptance-pack/1 不携带标签字段（装载时从键解析）");

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            // 客户侧同调用点换了模板版本（细分哈希不同）、行为一致——配对按标签而非版本
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-new", "verdict", "h-new", "{\"verdict\":\"DONE\"}", "cust-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);

            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            Path reportPath = tempDir.resolve("verify-report.md");
            int exit = runner.run(json, "digest", null, reportPath.toString(), false);

            assertEquals(0, exit, "同标签跨版本且行为一致 → PASS（版本差异不作缺/新增）: " + output);
            assertTrue(output.toString().contains("PASS 1"), output.toString());
            // 逐步明细（含版本注记）在验收报告文件；stdout 只有汇总行
            String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
            assertTrue(markdown.contains("cross-version pair h-old→h-new"), "报告必须带版本注记: " + markdown);

            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            VerifyRunner jsonRunner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);
            assertEquals(0, jsonRunner.run(json, "digest", null, null, false));
            String verifyJson = jsonOut.toString().trim();
            assertTrue(verifyJson.contains("\"invocationLabel\":\"verdict\""), verifyJson);
            assertTrue(verifyJson.contains("\"versionSwitch\":true"), verifyJson);
            assertTrue(verifyJson.contains("\"baselineSubdivision\":\"h-old\""), verifyJson);
            assertTrue(verifyJson.contains("\"newSubdivision\":\"h-new\""), verifyJson);
        } finally {
            customerDb.close();
        }
    }

    @Test
    @DisplayName("verify 跨版本行为变化：配对判定照常 CHANGED，注记不掩盖判定")
    void verify_crossVersion_behaviorChanged() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-old", "verdict", "h-old", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-new", "verdict", "h-new", "{\"status\":\"FAILED\"}", "cust-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            Path reportPath = tempDir.resolve("verify-report-changed.md");
            int exit = runner.run(json, "digest", null, reportPath.toString(), false);

            assertEquals(1, exit, "跨版本配对不豁免行为判定: " + output);
            assertTrue(output.toString().contains("CHANGED 1"), output.toString());
            String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
            assertTrue(markdown.contains("cross-version pair h-old→h-new"), "注记与判定并存: " + markdown);
        } finally {
            customerDb.close();
        }
    }
}
