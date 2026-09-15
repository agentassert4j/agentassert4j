package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.AcceptancePack;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.PackCodec;
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
        repository.saveInteractionIfAbsent(r);
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
        repo.saveInteractionIfAbsent(r);
    }

    /**
     * 构造而不落库（recordCandidate 等直调需要 InteractionRecord 对象本体）。
     */
    private InteractionRecord record(String recordId, String sessionId, long timestamp, String invocationKey, String label, String templateHash, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setInvocationKey(invocationKey);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setModelResponse(response);
        return r;
    }

    private String exportPack(String dbPath, boolean includeSamples) throws Exception {
        return exportPack(dbPath, includeSamples, null);
    }

    private String exportPack(String dbPath, boolean includeSamples, String codeRef) throws Exception {
        BaselineExportCommand command = new BaselineExportCommand();
        command.db = dbPath;
        command.out = new PrintStream(output, true);
        command.err = new PrintStream(output, true);
        command.includeSamples = includeSamples;
        command.outPath = tempDir.resolve("pack.json").toString();
        command.codeRef = codeRef;
        Integer exit = command.call();
        assertEquals(0, exit, "导出应成功: " + output);
        return new String(Files.readAllBytes(Paths.get(command.outPath)), StandardCharsets.UTF_8);
    }

    private void establishBaselines() {
        establishBaselines(null);
    }

    /**
     * 带 rules 建档与 CLI 生产路径同形（BaselineCommand 加载配置后传入）——
     * 画像指纹的维度 3/4 由建档时的 rules 定格，null = 无规则建档。
     */
    private void establishBaselines(InvocationRulesConfig rules) {
        new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", null, false, null, rules, null, null);
    }

    @Test
    @DisplayName("包 JSON 的 schema/meta/step 键集与字面形态固定")
    void goldenPackFormat() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
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
        assertTrue(json.contains("\"unadjudicatedSteps\":0"), "出厂偏离计数恒序列化（0 也写）: " + json);
        assertTrue(json.contains("\"servedModel\":\"dev-model\""), json);
        assertTrue(json.contains("\"codeRef\":null"), "未声明锚的包显式写 null 键: " + json);
    }

    @Test
    @DisplayName("申报代码锚写入包本体：含引号的锚经 PackCodec 读回逐字保真")
    void packCarriesCodeRef_quoteSafeRoundTrip() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();

        String json = exportPack(tempDir.resolve("verify.db").toString(), false, "ab\"12");

        assertTrue(json.contains("\"codeRef\":"), "包本体必须携带 codeRef 键: " + json);
        assertEquals("ab\"12", PackCodec.fromJson(json).getMeta().getCodeRef(), "锚经转义落盘，读回必须逐字保真");
    }

    @Test
    @DisplayName("export→verify 同环境往返：全 PASS 退出码 0")
    void roundtrip_sameEnvironment_pass() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
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
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
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
    @DisplayName("同键多记录往返：uniform 多记录链折叠为每调用点一步（组末锚）→ 全 PASS")
    void roundtrip_multiRecordSameKey_pass() throws Exception {
        // 同一调用点键的两条同形态记录：链末判定下每调用点一份步骤（组末为证据锚），
        // 指纹消费画像批准真相——uniform 链链末与画像一致，往返必自洽
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        saveRecord("r2", "s1", 2000L, null, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        Object parsed = RecursiveJsonParser.parse(json);
        Map<?, ?> task = (Map<?, ?>) ((List<?>) ((Map<?, ?>) parsed).get("tasks")).get(0);
        List<?> steps = (List<?>) task.get("steps");
        assertEquals(1, steps.size(), "同键多记录折叠为每调用点一步: " + json);
        assertEquals("r2", ((Map<?, ?>) steps.get(0)).get("recordId"), "证据锚=组末记录: " + json);
        assertEquals(0, ((Number) task.get("unadjudicatedSteps")).intValue(), "链末与画像一致 → 偏离计数 0");

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, HashUtil.sha256(json), null, null, false);
        assertEquals(0, exit, "同环境往返必 PASS: " + output);
    }

    @Test
    @DisplayName("出厂偏离检测·链末形态异：承诺照常入包+计数，verify 同尺判链末 CHANGED")
    void export_flagsChainEndDeviation_verifyJudgesChainFinal() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        saveRecord("r2", "s1", 2000L, null, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\",\"extra\":1}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        String digest = HashUtil.sha256(json);

        Object parsed = RecursiveJsonParser.parse(json);
        Map<?, ?> task = (Map<?, ?>) ((List<?>) ((Map<?, ?>) parsed).get("tasks")).get(0);
        assertEquals(1, ((Number) task.get("unadjudicatedSteps")).intValue(), "链末提取≠批准指纹 → 出厂偏离计数: " + json);
        assertTrue(output.toString().contains("unadjudicated steps in the pack"), "人读警告在场: " + output);

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        Path reportPath = tempDir.resolve("deviation-report.md");
        int exit = runner.run(json, digest, null, reportPath.toString(), false);
        assertEquals(1, exit, "verify 判本地链末（异形态）vs 包承诺（批准形态）→ CHANGED（与 CI 同尺）: " + output);
        assertTrue(output.toString().contains("CHANGED 1"), output.toString());
        String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("judging the latest execution per invocation; 1 earlier record(s)"), "markdown 镜像 N 面注记: " + markdown);

        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        VerifyRunner jsonRunner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);
        assertEquals(1, jsonRunner.run(json, digest, null, null, false));
        assertTrue(jsonOut.toString().contains("\"earlierRecords\":1"), "verify-report/1 步骤镜像 N 面字段: " + jsonOut);
    }

    @Test
    @DisplayName("出厂偏离检测·在途候选：未裁决即导出计数+警告+JSON 报告字段；accept 后重导归零")
    void export_flagsInFlightCandidate_thenCleanAfterAdjudication() throws Exception {
        InteractionRecord seed = record("r1", "s1", 1000L, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}");
        repository.saveInteractionIfAbsent(seed);
        establishBaselines();
        InteractionRecord changed = record("n1", "s2", 9000L, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"status\":\"FAILED\"}");
        changed.setUserInput("查订单");
        repository.saveInteractionIfAbsent(changed);
        assertTrue(new BaselineManager(repository).recordCandidate(changed, FingerprintExtractor.extract(changed, new InvocationRulesConfig(), "verdict")), "登记在途候选");

        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        Object parsed = RecursiveJsonParser.parse(json);
        Map<?, ?> task = (Map<?, ?>) ((List<?>) ((Map<?, ?>) parsed).get("tasks")).get(0);
        assertEquals(1, ((Number) task.get("unadjudicatedSteps")).intValue(), "在途候选 → 出厂偏离计数: " + json);
        assertTrue(output.toString().contains("unadjudicated steps in the pack"), "人读警告在场: " + output);

        BaselineExportCommand jsonReport = new BaselineExportCommand();
        jsonReport.db = tempDir.resolve("verify.db").toString();
        jsonReport.out = new PrintStream(output, true);
        jsonReport.err = new PrintStream(output, true);
        jsonReport.outPath = tempDir.resolve("pack2.json").toString();
        jsonReport.jsonOutput = true;
        assertEquals(0, jsonReport.call(), "导出应成功: " + output);
        assertTrue(output.toString().contains("\"unadjudicatedSteps\":1"), "export-report/1 计数在场: " + output);

        new BaselineManager(repository).accept("invocation:verdict:h-verdict", null, "tester", null);
        output.reset();
        String clean = exportPack(tempDir.resolve("verify.db").toString(), false);
        Object reparsed = RecursiveJsonParser.parse(clean);
        Map<?, ?> cleanTask = (Map<?, ?>) ((List<?>) ((Map<?, ?>) reparsed).get("tasks")).get(0);
        assertEquals(0, ((Number) cleanTask.get("unadjudicatedSteps")).intValue(), "裁决后重导归零: " + clean);
        assertFalse(output.toString().contains("unadjudicated steps in the pack"), "全裁决一致库不出警告: " + output);
    }

    @Test
    @DisplayName("自违不误报：declared 规则 × 链末偏离 → 走偏离出口，不整链排除为自违")
    void export_selfViolation_notMisdiagnosed() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.write(rulesFile, "{\"invocations\":{\"verdict\":{\"requiredKeywords\":[\"DONE\"]}}}".getBytes(StandardCharsets.UTF_8));
        System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, rulesFile.toString());
        try {
            saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "verdict DONE shipped", "dev-model");
            establishBaselines();
            saveRecord("r2", "s1", 2000L, null, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\",\"extra\":1}", "dev-model");

            String json = exportPack(tempDir.resolve("verify.db").toString(), false);

            assertTrue(json.contains("\"taskKey\":\"查订单\""), "任务照常入包（承诺仍良定义）: " + json);
            assertFalse(output.toString().contains("violates its own content rules"), "链末偏离不得误诊为基线自违: " + output);
            Object parsed = RecursiveJsonParser.parse(json);
            Map<?, ?> task = (Map<?, ?>) ((List<?>) ((Map<?, ?>) parsed).get("tasks")).get(0);
            assertEquals(1, ((Number) task.get("unadjudicatedSteps")).intValue(), "偏离走出厂检测出口: " + json);
        } finally {
            System.clearProperty(ConfigLoader.RULES_PATH_PROPERTY);
        }
    }

    @Test
    @DisplayName("字段缺省语义：缺 unadjudicatedSteps 字段的包 fromMap 缺省 0，verify 照常")
    void packField_defaultZero_whenAbsent() throws Exception {
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        String stripped = json.replace(",\"unadjudicatedSteps\":0", "");
        assertFalse(stripped.contains("unadjudicatedSteps"), "夹具必须真的剥掉字段: " + stripped);
        AcceptancePack pack = PackCodec.fromJson(stripped);
        assertEquals(0, pack.getTasks().get(0).getUnadjudicatedSteps(), "缺字段缺省 0（解析健壮性，非版本迁移）");

        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        assertEquals(0, runner.run(stripped, HashUtil.sha256(stripped), null, null, false), "缺字段包照常判定: " + output);
    }

    @Test
    @DisplayName("指纹真源+同尺钉：导出时规则缺席（仅声明集漂移）→ 不计偏离（与门禁同尺），包步骤携带画像批准指纹")
    void export_stepsCarryApprovedFingerprints() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.write(rulesFile, "{\"invocations\":{\"verdict\":{\"requiredKeywords\":[\"DONE\"]}}}".getBytes(StandardCharsets.UTF_8));
        System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, rulesFile.toString());
        try {
            saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
            establishBaselines(InvocationRulesConfig.fromJson("{\"invocations\":{\"verdict\":{\"requiredKeywords\":[\"DONE\"]}}}"));
        } finally {
            System.clearProperty(ConfigLoader.RULES_PATH_PROPERTY);
        }
        // 导出时规则文件缺席：链末现场提取只缺声明集（结构维一致），门禁会判 PASS——
        // 偏离检测不得比门禁更严（否则警告指路的裁决对象不存在）→ 计数 0；
        // 包步骤指纹仍携带画像批准真相（声明规则维在场）
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        Object parsed = RecursiveJsonParser.parse(json);
        Map<?, ?> task = (Map<?, ?>) ((List<?>) ((Map<?, ?>) parsed).get("tasks")).get(0);
        assertEquals(0, ((Number) task.get("unadjudicatedSteps")).intValue(), "仅声明集漂移=门禁 PASS=非偏离（同尺）: " + json);
        assertFalse(output.toString().contains("unadjudicated steps in the pack"), "不出假警告: " + output);
        assertTrue(json.contains("DONE"), "步骤指纹携带画像的声明规则维: " + json);
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
        saveRecord("r1", "s1", 1000L, "V1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            // 客户侧只执行了 "V10"（前缀同名任务）——它不是 "V1" 的证据
            saveRecord("c1", customerDb, 9000L, "V10", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
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
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            // 包导出之后又录了新任务：范围外链的典型成因
            saveRecord("c2", customerDb, 9500L, "查物流", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", null, false, null, null, null, null);
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
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c1", customerDb, 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "customer-local-model");
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", null, false, null, null, null, null);
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
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
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
        saveRecord("r1", "s1", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
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
        saveRecord("r1", "s1", 1000L, "我的密码是 secret123", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();

        String json = exportPack(tempDir.resolve("verify.db").toString(), true);

        // 样本整值掩码；任务键=请求原文属配对语义（敏感任务用声明 taskKey 规避）
        assertTrue(json.contains("***"), "样本必须整值掩码: " + json);
        assertFalse(json.contains("\"sampleInput\":\"我的密码是 secret123\""), "样本字段不得保留原文: " + json);
    }

    @Test
    @DisplayName("参照等价：包=批准真相定格——本地链末偏离判红；库内路径两轮结构变化照判")
    void referenceEquivalence() throws Exception {
        saveRecord("b1", "s-old", 1000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        saveRecord("n1", "s-new", 9000L, "查订单", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"status\":\"FAILED\"}", "dev-model");
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        // 包路径：包指纹=画像批准真相（b1 形态）；本地链末（n1 形态）偏离 → 如实 CHANGED
        VerifyRunner packRunner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int packExit = packRunner.run(json, "digest", null, null, false);
        assertEquals(1, packExit, "包=批准真相：本地链末偏离承诺必须判红: " + output);

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
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", null, false, null, null, null, null);

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
            new BaselineService(customerDb).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", null, false, null, null, null, null);
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

    @Test
    @DisplayName("自违守卫：违规基线链被排除并警告，洁净包自往返必 PASS")
    void verify_rulesEmbedded_selfRoundtripPassesWithGuard() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.write(rulesFile, "{\"invocations\":{\"refund\":{\"requiredKeywords\":[\"order\"]}}}".getBytes(StandardCharsets.UTF_8));
        System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, rulesFile.toString());
        try {
            saveRecord("r1", "s1", 1000L, "clean request", "invocation:order:h-order", "order", "h-order", "your order 123 shipped", "dev-model");
            saveRecord("r2", "s2", 2000L, "refund request", "invocation:refund:h-refund", "refund", "h-refund", "done", "dev-model");
            establishBaselines(InvocationRulesConfig.fromJson("{\"invocations\":{\"refund\":{\"requiredKeywords\":[\"order\"]}}}"));
            String json = exportPack(tempDir.resolve("verify.db").toString(), false);
            assertTrue(json.contains("\"rules\""), "声明规则段必须随包出境: " + json);
            assertTrue(output.toString().contains("violate their own declared content rules"), "自违排除必须警告: " + output);
            assertFalse(json.contains("refund request"), "违规基线链不得入包: " + json);

            VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            int exit = runner.run(json, "digest", null, null, false);
            assertEquals(0, exit, "洁净基线包自往返必 PASS: " + output);
            assertTrue(output.toString().contains("PASS 1 | CHANGED 0"), output.toString());
        } finally {
            System.clearProperty(ConfigLoader.RULES_PATH_PROPERTY);
        }
    }

    @Test
    @DisplayName("检出力：验收侧记录缺失必需关键词 → 维度 3 判 CHANGED")
    void verify_rulesEmbedded_detectsAcceptanceSideKeywordLoss() throws Exception {
        Path rulesFile = tempDir.resolve("rules.json");
        Files.write(rulesFile, "{\"invocations\":{\"refund\":{\"requiredKeywords\":[\"order\"]}}}".getBytes(StandardCharsets.UTF_8));
        System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, rulesFile.toString());
        try {
            saveRecord("r1", "s1", 1000L, "refund request", "invocation:refund:h-refund", "refund", "h-refund", "your order 123 shipped", "dev-model");
            establishBaselines(InvocationRulesConfig.fromJson("{\"invocations\":{\"refund\":{\"requiredKeywords\":[\"order\"]}}}"));
            String json = exportPack(tempDir.resolve("verify.db").toString(), false);

            SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
            customerDb.initialize();
            try {
                saveRecord("c1", customerDb, 9000L, "refund request", "invocation:refund:h-refund", "refund", "h-refund", "done", "cust-model");
                VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
                Path reportPath = tempDir.resolve("detect-report.md");
                int exit = runner.run(json, "digest", null, reportPath.toString(), false);
                assertEquals(1, exit, "验收侧丢必需关键词 → 维度 3 必须参与判定: " + output);
                String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
                assertTrue(markdown.contains("content rules mismatch"), "维度 3 失配必须呈现: " + markdown);
            } finally {
                customerDb.close();
            }
        } finally {
            System.clearProperty(ConfigLoader.RULES_PATH_PROPERTY);
        }
    }

    @Test
    @DisplayName("降级：无规则段的包跳过维度 3/4 并在报告注记")
    void verify_withoutRulesSection_degradesWithNote() throws Exception {
        saveRecord("r1", "s1", 1000L, "refund request", "invocation:refund:h-refund", "refund", "h-refund", "your order 123 shipped", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);
        assertFalse(json.contains("\"rules\""), "无声明时不得入规则段: " + json);

        Path reportPath = tempDir.resolve("verify-report.md");
        VerifyRunner runner = new VerifyRunner(repository, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
        int exit = runner.run(json, "digest", null, reportPath.toString(), false);
        assertEquals(0, exit, "无规则段降级：维度 3/4 两侧默认 match: " + output);
        String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("no pack rules section (dimensions 3/4 skipped"), "降级必须注记: " + markdown);
    }

    @Test
    @DisplayName("verify 缩域运行：范围外链只出计数，不逐条列举")
    void verify_narrowedRun_suppressesOutOfScopeList() throws Exception {
        saveRecord("r1", "s1", 1000L, "V1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c0", customerDb, 8900L, "V1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            saveRecord("c1", customerDb, 9000L, "W1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            saveRecord("c2", customerDb, 9100L, "W2", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            Path reportPath = tempDir.resolve("narrow-report.md");
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            int exit = runner.run(json, "digest", "V1", reportPath.toString(), false);
            assertEquals(0, exit, "缩域内无偏差: " + output);
            String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
            assertTrue(markdown.contains("expected in a narrowed run, not listed"), "缩域运行必须注记预期性: " + markdown);
            assertFalse(markdown.contains("W1"), "缩域运行不得逐条列举范围外链: " + markdown);
        } finally {
            customerDb.close();
        }
    }

    @Test
    @DisplayName("verify 全量运行：范围外明细封顶 20 条后计数收尾")
    void verify_fullRun_capsOutOfScopeList() throws Exception {
        saveRecord("r1", "s1", 1000L, "V1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "dev-model");
        establishBaselines();
        String json = exportPack(tempDir.resolve("verify.db").toString(), false);

        SqliteStorageRepository customerDb = new SqliteStorageRepository(tempDir.resolve("customer.db").toString());
        customerDb.initialize();
        try {
            saveRecord("c0", customerDb, 8900L, "V1", "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            for (int i = 1; i <= 25; i++) {
                saveRecord("c" + i, customerDb, 9000L + i, "W" + i, "invocation:verdict:h-verdict", "verdict", "h-verdict", "{\"verdict\":\"DONE\"}", "cust-model");
            }
            Path reportPath = tempDir.resolve("full-report.md");
            VerifyRunner runner = new VerifyRunner(customerDb, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output, true), new PrintStream(output, true), false);
            int exit = runner.run(json, "digest", null, reportPath.toString(), false);
            assertEquals(0, exit, "范围外不影响退出码: " + output);
            assertTrue(output.toString().contains("out-of-scope chains 25"), "stdout 汇总计数必须完整: " + output);
            String markdown = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
            assertTrue(markdown.contains("; ... and 5 more"), "超出上限必须计数收尾: " + markdown);
            assertTrue(markdown.contains("W20"), "上限内的明细必须在场: " + markdown);
            assertFalse(markdown.contains("W21"), "上限外的明细不得出现: " + markdown);
        } finally {
            customerDb.close();
        }
    }
}
