package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一重放引擎的单元测试 — 身份检测、逐任务对齐、漂移处置三出口、守卫与退出码矩阵。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class TaskReplayRunnerTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;
    private StubLlmClient stubClient;
    private TaskReplayRunner runner;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("task.db").toString());
        repository.initialize();
        output = new ByteArrayOutputStream();
        stubClient = new StubLlmClient();
        runner = newRunner(false);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    /**
     * 模板原文种子：saveTemplateText 已是存储实现私有，经同一 SQL 语义（INSERT OR
     * IGNORE 首写为准）直插 prompt_texts——不走生产写入路径，避免为种文本引入
     * 多余交互记录污染重驱计数。
     */
    private void saveTemplateText(String hash, String templateText) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("task.db").toString());
             PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO prompt_texts (prompt_hash, prompt_text, created_at) VALUES (?,?,?)")) {
            ps.setString(1, hash);
            ps.setString(2, templateText);
            ps.setLong(3, 1L);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("template text seed failed: " + hash, e);
        }
    }

    private TaskReplayRunner newRunner(boolean jsonMode) {
        return new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), jsonMode);
    }

    /**
     * 声明无骨架记录：全文哈希即键细分（invocation:order:hash-a）。
     */
    private InteractionRecord saveRecord(String recordId, String sessionId, long ts, String userInput, String label, String templateHash, String response, String servedModel) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setUserInput(userInput);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        r.setModelResponse(response);
        r.setServedModel(servedModel);
        r.setModel("stub-model");
        repository.saveInteractionIfAbsent(r);
        return r;
    }

    /**
     * 骨架锚点记录：身份按骨架定格，全文哈希可自由变化（同键漂移的载体形态）。
     */
    private InteractionRecord saveSkeletonRecord(String recordId, String sessionId, long ts, String userInput, String label, String skeletonHash, String templateHash, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setUserInput(userInput);
        r.setInvocationId(label);
        r.setSkeletonHash(skeletonHash);
        r.setTemplateHash(templateHash);
        r.setInvocationKey("invocation:" + label + ":" + skeletonHash);
        r.setModelResponse(response);
        r.setModel("stub-model");
        repository.saveInteractionIfAbsent(r);
        return r;
    }

    /**
     * 声明任务键的记录：metadata 携带 taskKey（任务规则只对声明链生效）。
     */
    private InteractionRecord saveDeclaredTaskRecord(String recordId, String sessionId, long ts, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setUserInput("查订单");
        r.setInvocationId("order");
        r.setTemplateHash("hash-a");
        r.setInvocationKey("invocation:order:hash-a");
        r.setModelResponse(response);
        r.setMetadata("{\"taskKey\":\"查订单\"}");
        repository.saveInteractionIfAbsent(r);
        return r;
    }

    private InvocationProfile establishedProfile(String invocationKey, String label, String templateHash) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(invocationKey);
        p.setLabel(label);
        p.setTemplateHash(templateHash);
        p.setInvocationName(label);
        p.setInvocationType(InvocationType.PURE_CHAT);
        p.setFingerprints(new ArrayList<>(Collections.singletonList(new DeterministicFingerprint())));
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1");
        p.setAlgoVersion(JudgmentSemantics.VERSION);
        repository.saveInvocationProfile(p);
        return p;
    }

    /**
     * 以真实记录行为建档（指纹现场提取）——CI 基线对照消费画像活跃指纹，
     * 空指纹画像在 ci-align 下恒 CHANGED；PASS 场景的画像必须与记录行为一致。
     */
    private InvocationProfile establishFromRecord(InteractionRecord seed) {
        InvocationProfile p = establishedProfile(seed.getInvocationKey(), seed.getInvocationId(), seed.getTemplateHash());
        p.setFingerprints(new ArrayList<>(Collections.singletonList(FingerprintExtractor.extract(seed, new InvocationRulesConfig(), seed.getInvocationId()))));
        p.setApprovedAt(12345L);
        repository.saveInvocationProfile(p);
        return p;
    }

    /**
     * 两个同名任务链（两条 session），逐步记录相同响应 → 对齐应全 PASS。
     */
    private void seedIdenticalChains(String response) {
        saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", response, null);
        saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", response, null);
    }

    @Nested
    @DisplayName("真实对齐：逐任务最新链 vs 次新链")
    class Alignment {

        @Test
        @DisplayName("同构链全 PASS → 退出码 0")
        void identicalChains_pass() {
            seedIdenticalChains("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("步骤行为差异（结构指纹不同）→ 退出码 1 并落候选")
        void stepDiff_changed_exits1_andRegistersCandidate() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            int exit = runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertEquals(1, exit);
            InvocationProfile profile = repository.findInvocationByKey("invocation:order:hash-a");
            assertNotNull(profile.getCandidateFingerprint(), "CHANGED 步必须落候选（显式 replay 即测试行为）");
            assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());
            assertTrue(output.toString().contains("Candidate registered"));
        }

        @Test
        @DisplayName("缺步骤（基线调用点在新链整组缺席）→ 退出码 1")
        void missingStep_exits1() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("a-2", "session-a", 1100L, "查订单", "confirm", "hash-c", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);

            int exit = runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertEquals(1, exit);
            assertTrue(output.toString().contains("missing step"));
        }

        @Test
        @DisplayName("新增步骤（新链出现基线没有的调用点）→ 退出码 1")
        void addedStep_exits1() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-2", "session-b", 2100L, "查订单", "confirm", "hash-c", "{\"result\":\"ok\"}", null);

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("仅一条链 → 自建基线，退出码 0")
        void singleChain_selfEstablish() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);

            assertEquals(0, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("first recording becomes the baseline"));
        }

        @Test
        @DisplayName("同文本多链取最新 vs 次新，多轮执行不触发歧义")
        void multipleChains_sameTask_pairsLatestTwo() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(0, runner.run(null, null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("任务规则必备步骤缺失 → 退出码 1 并报告违规")
        void taskRuleViolation_exits1() {
            saveDeclaredTaskRecord("a-1", "session-a", 1000L, "{\"result\":\"ok\"}");
            saveDeclaredTaskRecord("b-1", "session-b", 2000L, "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"查订单\":{\"requiredSteps\":[\"confirm\"]}}}");
            TaskReplayRunner ruledRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), rules, TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);

            assertEquals(1, ruledRunner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Task rule violation"));
        }
    }

    @Nested
    @DisplayName("缩域：--task 与 --invocation 复合 AND")
    class Scoping {

        @Test
        @DisplayName("精确命中优先，前缀家族不被静默扩选")
        void exactBeatsPrefixFamily() {
            saveRecord("a-1", "session-a", 1000L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("a-2", "session-a2", 1100L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "V1 请求扩展", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-2", "session-b2", 2100L, "V1 请求扩展", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(0, runner.run("V1 请求", null, false, false, false, null, false, false, false, null, null));
            assertFalse(output.toString().contains("V1 请求扩展"), "精确命中不得扩选到前缀家族: " + output);
        }

        @Test
        @DisplayName("前缀命中多个任务文本 → 歧义报错退出码 2")
        void ambiguousPrefix_exits2() {
            saveRecord("a-1", "session-a", 1000L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "V2 请求", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(2, runner.run("V", null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("前缀唯一候选直接采用")
        void uniquePrefix_adopted() {
            saveRecord("a-1", "session-a", 1000L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("a-2", "session-a2", 1100L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(0, runner.run("V", null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("前缀无命中 → 退出码 2")
        void noMatch_exits2() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(2, runner.run("不存在的任务", null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("--invocation 缩域到含该键的任务链，域外任务不对齐")
        void invocationNarrowing_filtersChains() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "查订单", null);
            saveRecord("x-1", "session-x", 1500L, "查订单", "order", "hash-a", "写诗", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "查订单", null);

            assertEquals(0, runner.run(null, "invocation:order:hash-a", false, false, false, null, false, false, false, null, null));
            assertFalse(output.toString().contains("写诗"), "缩域外任务不应进入对齐输出: " + output);
        }

        @Test
        @DisplayName("--task 与 --invocation 复合 AND：交集为空退出码 2")
        void compositeAnd_emptyIntersection_exits2() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "查订单", null);
            saveRecord("x-1", "session-x", 1500L, "查订单", "order", "hash-a", "写诗", null);

            assertEquals(2, runner.run("查订单", "invocation:order:hash-b", false, false, false, null, false, false, false, null, null));
        }
    }

    @Nested
    @DisplayName("漂移处置状态机：收编 / 候选 / 挂起")
    class DriftStateMachine {

        /**
         * 骨架键画像（哈希停留在旧全文）+ 两条骨架链（新链全文哈希已变、行为一致）。
         */
        private void seedSkeletonDrift(String newResponse) {
            InteractionRecord seed = saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", newResponse);
            // CI 基线对照消费画像活跃指纹：种子取真实行为提取，行为一致时 PASS 才成立
            establishFromRecord(seed);
        }

        @Test
        @DisplayName("漂移 + 对齐 PASS（开发态）→ 自动收编前移身份，退出码 0，检测收敛")
        void driftPass_dev_collects() {
            seedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, null, false, false, false, null, null));

            assertEquals("hash-new", repository.findInvocationByKey("invocation:order:skl-1").getTemplateHash(), "画像身份必须前移到最新记录哈希");
            assertTrue(output.toString().contains("Collected:"));
            runner.run(null, null, false, false, false, null, false, false, false, null, null);
            assertTrue(output.toString().contains("template identities consistent"), "收编后检测不应再命中");
        }

        @Test
        @DisplayName("漂移 + 对齐 PASS（--ci）→ 不收编，警告可见，退出码 0，身份不动")
        void driftPass_ci_keepsStale() {
            seedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null));

            assertEquals("hash-old", repository.findInvocationByKey("invocation:order:skl-1").getTemplateHash(), "CI 模式不得落治理写");
            assertTrue(output.toString().contains("Identity not collected"));
        }

        @Test
        @DisplayName("漂移 + 对齐 CHANGED → 落候选，退出码 1")
        void driftChanged_registersCandidate() {
            seedSkeletonDrift("{\"changed\":true}");

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));

            InvocationProfile profile = repository.findInvocationByKey("invocation:order:skl-1");
            assertNotNull(profile.getCandidateFingerprint(), "漂移 + CHANGED 必须落候选");
            assertEquals("hash-old", profile.getTemplateHash(), "候选路径不得前移身份");
        }

        @Test
        @DisplayName("漂移 + 缺步骤 → 挂起：不收编不落候选，退出码 1")
        void driftWithMissingStep_hangs() {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:skl-1", "order", "hash-old");

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));

            InvocationProfile profile = repository.findInvocationByKey("invocation:order:skl-1");
            assertEquals("hash-old", profile.getTemplateHash());
            assertNull(profile.getCandidateFingerprint());
            assertTrue(output.toString().contains("Hung:"));
        }

        @Test
        @DisplayName("漂移键不在任何对齐任务（bare）→ 挂起退出码 1")
        void driftWithoutAnyChain_hangs() {
            establishedProfile("invocation:lonely:skl-1", "lonely", "hash-old");
            saveSkeletonRecord("l-1", "session-l", 1500L, "查订单", "lonely", "skl-1", "hash-new", "{\"result\":\"ok\"}");

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Hung:"));
        }

        @Test
        @DisplayName("缩域外漂移只进检测报告不处置，不贡献退出码")
        void externalDrift_reportedOnly() {
            establishedProfile("invocation:lonely:skl-1", "lonely", "hash-old");
            saveSkeletonRecord("l-1", "session-l", 1500L, "查订单", "lonely", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            saveRecord("x-1", "session-x", 1000L, "写诗任务", "poem", "hash-p", "{\"result\":\"ok\"}", null);
            saveRecord("x-2", "session-x2", 2000L, "写诗任务", "poem", "hash-p", "{\"result\":\"ok\"}", null);

            assertEquals(0, runner.run("写诗", null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Drift outside scope"));
        }

        @Test
        @DisplayName("缩域命中但任务无对齐步结果（单链任务）→ 挂起而非误标域外")
        void narrowedInScopeDriftWithoutSteps_hangs() {
            establishedProfile("invocation:lonely:skl-1", "lonely", "hash-old");
            // 漂移链排为最早：最新 vs 次新配对落在 order 键上，lonely 键无对齐步结果
            saveSkeletonRecord("l-1", "session-l", 900L, "查订单", "lonely", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            saveRecord("x-1", "session-x", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("x-2", "session-x2", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Hung:"));
            assertFalse(output.toString().contains("Drift outside scope"), "缩域内的键不得误标域外: " + output);
        }

        @Test
        @DisplayName("标签裂键 → 裸重放不再自动收编：注记指路显式 establish，新档不落")
        void labelSplit_bareReplay_leavesForExplicitEstablish() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-old", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-new", "{\"result\":\"ok\"}", null);
            establishedProfile("invocation:order:hash-old", "order", "hash-old");

            runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertNull(repository.findInvocationByKey("invocation:order:hash-new"), "裂键新档必须留给显式 establish（自动建档只收编全新键）");
            assertTrue(output.toString().contains("left for explicit establish"), "注记指路显式建档: " + output);
            assertTrue(output.toString().contains("baseline --invocation invocation:order:hash-new"), "指路必须带可复制的键: " + output);
        }
    }

    @Nested
    @DisplayName("守卫继承")
    class Guards {

        @Test
        @DisplayName("判定语义版本不一致 → 拒绝判定退出码 2")
        void staleAlgoVersion_refuses() {
            seedIdenticalChains("{\"v\":1}");
            InvocationProfile profile = establishedProfile("invocation:order:hash-a", "order", "hash-a");
            profile.setAlgoVersion("det-v0");
            repository.saveInvocationProfile(profile);

            assertEquals(2, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Judgment semantics version mismatch"));
        }

        @Test
        @DisplayName("未标记语义版本的基线同样拒绝")
        void unstampedAlgoVersion_refuses() {
            seedIdenticalChains("{\"v\":1}");
            InvocationProfile profile = establishedProfile("invocation:order:hash-a", "order", "hash-a");
            profile.setAlgoVersion(null);
            repository.saveInvocationProfile(profile);

            assertEquals(2, runner.run(null, null, false, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("--ci 未建档调用点拒绝判定并列出名单，退出码 2")
        void ciRefusesUnbaselined() {
            saveIdenticalLabeledChains();

            assertEquals(2, runner.run(null, null, true, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Refusing to judge in --ci mode"));
        }

        @Test
        @DisplayName("--ci 基线齐备时正常判定放行")
        void ciPassesWhenAllEstablished() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            establishFromRecord(seed);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("换模型告警只挂重驱：零调用判定路径不告警，重驱前必须告警")
        void modelDiffers_warnsOnlyBeforeReDrive() {
            seedIdenticalChains("{\"v\":1}");
            TestExecutionConfig withModel = new TestExecutionConfig().timeoutMs(1000).temperature(null).model("another-model");
            TaskReplayRunner modelRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), withModel, new PrintStream(output, true), new PrintStream(output, true), false);

            modelRunner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertFalse(output.toString().contains("differs from recorded models"), "判定与对齐层零 LLM 调用，不消费重放模型，不得告警: " + output);

            output.reset();
            modelRunner.run(null, null, false, false, false, null, false, true, false, null, null);

            assertTrue(output.toString().contains("differs from recorded models"), "重驱真实消费重放模型，必须告警: " + output);
        }

        @Test
        @DisplayName("基线与新链 served 模型族不相交 → 模型身份变更注记")
        void servedModelPairNoted() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-a");
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-b");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertTrue(output.toString().contains("model identity changed"));
        }

        @Test
        @DisplayName("逐步 served 模型不一致就地标注")
        void servedModelStepNoted() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-a");
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":2}", "model-b");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertTrue(output.toString().contains("(served: model-b, baseline: model-a)"));
        }

        @Test
        @DisplayName("dry-run 只读：不建档不落图快照，输出对齐计划，退出码 0")
        void dryRun_readOnly() {
            saveIdenticalLabeledChains();

            assertEquals(0, runner.run(null, null, false, true, false, null, false, false, false, null, null));

            assertNull(repository.findInvocationByKey("invocation:order:hash-a"), "dry-run 不得建档");
            assertTrue(output.toString().contains("Alignment plan"));
        }
    }

    private void saveIdenticalLabeledChains() {
        saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
        saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
    }

    /**
     * 从多行输出中取出含指定片段的报告行（JSON 钉的共用提取器）。
     */
    private static String reportLine(String output, String marker) {
        for (String line : output.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.contains(marker)) {
                return trimmed;
            }
        }
        return null;
    }

    @Nested
    @DisplayName("CI 基线对照：最新链 vs 画像活跃指纹")
    class CiAlign {

        @Test
        @DisplayName("A2 闭环：行为变更 CI 出 1 → accept → 同库再跑 CI 出 0")
        void changedThenAccept_ciTurnsGreen() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            assertEquals(1, runner.run(null, null, true, false, false, null, false, false, false, null, null), "行为差异必须让 CI 变红");
            assertNotNull(repository.findInvocationByKey("invocation:order:hash-a").getCandidateFingerprint(), "CI 的 CHANGED 步照落候选");

            new BaselineManager(repository).accept("invocation:order:hash-a", null, "tester", null);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "accept 后同一证据必须转绿（裁决对门禁生效）");
        }

        @Test
        @DisplayName("A1 闭环：同会话好在前坏在后 → 链末坏形态仍挡门，早记录注记在场")
        void sameSessionIteration_allJudged() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            int exit = runner.run(null, null, true, false, false, null, false, false, false, null, null);

            assertEquals(1, exit, "链末坏形态必须挡门（好→坏追加，坏是链末）");
            String out = output.toString();
            assertFalse(out.contains("surplus unpaired"), "链末判定每调用点一份步骤，不得有富余排除: " + out);
            assertTrue(out.contains("CHANGED"));
            assertTrue(out.contains("--ci gates the latest execution per invocation"), "草稿透明层注记在场: " + out);
            assertFalse(out.contains("unapproved shape"), "早记录=已批准种子形态，M=0 不追加未批准子句: " + out);

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            jsonRunner.run(null, null, true, false, false, null, false, false, false, null, null);
            String ciLine = reportLine(output.toString(), "\"mode\":\"ci-align\"");
            assertNotNull(ciLine, "ci-align 报告行在场");
            assertTrue(ciLine.contains("\"earlierRecords\":1"), "早记录计数进 JSON: " + ciLine);
            assertTrue(ciLine.contains("\"unapprovedEarlier\":0"), "零值恒输出——消费端不区分「无草稿」与「字段缺席」两种形态: " + ciLine);
        }

        @Test
        @DisplayName("生产路径钉：establishMissing 以桶内最新记录播种（认可时刻定标当前行为），坏草稿在前的混合链建档即绿")
        void productionEstablish_seedsLatest_mixedChainGoesGreen() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);
            InteractionRecord chainFinal = saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            new BaselineService(repository).establishMissing(new PrintStream(output, true), "tester", null, false, null, null, null, null);

            InvocationProfile profile = repository.findInvocationByKey("invocation:order:hash-a");
            assertNotNull(profile, "生产建档路径必须产出画像");
            assertEquals(FingerprintExtractor.extract(chainFinal, null, null), profile.getFingerprints().get(0), "种子指纹=桶内最新记录——N 版迭代在认可时刻定标当前行为");
            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "链末好形态=种子 → CI 建档即绿（判定与播种同取最新，缺口闭合）");
            assertNull(repository.findInvocationByKey("invocation:order:hash-a").getCandidateFingerprint(), "PASS 不落候选");
        }

        @Test
        @DisplayName("混形指路：同调用点跨任务链一绿一红时输出收敛路径注记")
        void mixedShapes_acrossTaskChains_notePointsToConvergence() {
            InteractionRecord good = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(good);
            saveRecord("b-1", "session-b", 2000L, "退货", "order", "hash-a", "{\"changed\":true}", null);

            int exit = runner.run(null, null, true, false, false, null, false, false, false, null, null);

            assertEquals(1, exit, "坏链末必须挡门");
            String out = output.toString();
            assertTrue(out.contains("Mixed shapes on order@"), "混形注记点名调用点: " + out);
            assertTrue(out.contains("ends different shapes across task chains"), "注记说明跨链混形因果: " + out);
            assertTrue(out.contains("\"查订单\" PASS"), "绿链任务就地可见: " + out);
            assertTrue(out.contains("\"退货\" CHANGED"), "红链任务就地可见: " + out);
            assertTrue(out.contains("accept the candidate to add the new shape to the approved set"), "注记给出集合语义的处置路径（逐形态 accept 入集）: " + out);
        }

        @Test
        @DisplayName("多形态工作流钉：跨任务混形 accept 入集后双绿，链末回到任一认可形态不再落候选")
        void mixedShapes_acceptSecondShape_bothTasksGreen() {
            InteractionRecord good = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(good);
            saveRecord("b-1", "session-b", 2000L, "退货", "order", "hash-a", "{\"changed\":true}", null);

            assertEquals(1, runner.run(null, null, true, false, false, null, false, false, false, null, null), "第二任务链末为集合外形态 → 先红并落候选");
            assertNotNull(repository.findInvocationByKey("invocation:order:hash-a").getCandidateFingerprint());

            new BaselineManager(repository).accept("invocation:order:hash-a", null, "tester", null);

            assertEquals(2, repository.findInvocationByKey("invocation:order:hash-a").getFingerprints().size(), "认可集合=两形态（追加非替换）");
            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "两任务链末各属一认可形态 → 双绿");
            assertNull(repository.findInvocationByKey("invocation:order:hash-a").getCandidateFingerprint(), "双绿后无候选残留（成员守卫按集合判定）");
        }

        @Test
        @DisplayName("链末判定哲学钉：坏草稿在前、好链末在后 → PASS + exit 0 + 透明层计数（草稿不挡门）")
        void badDraftFirst_ciGatesChainFinal_passes() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);
            InteractionRecord good = saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(good);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "判定对象=链末执行（与画像一致），草稿不挡门");
            String human = output.toString();
            assertTrue(human.contains("--ci gates the latest execution per invocation"), "早记录注记在人读通道: " + human);
            assertTrue(human.contains("1 earlier record"), "组内早记录计数 N=1: " + human);
            assertTrue(human.contains("unapproved shape"), "坏草稿的未批准计数 M=1: " + human);
            assertNull(repository.findInvocationByKey(good.getInvocationKey()).getCandidateFingerprint(), "链末与画像一致，无候选登记");

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            assertEquals(0, jsonRunner.run(null, null, true, false, false, null, false, false, false, null, null));
            String ciLine = reportLine(output.toString(), "\"mode\":\"ci-align\"");
            assertNotNull(ciLine, "必须有 ci-align 报告行");
            assertTrue(ciLine.contains("\"earlierRecords\":1"), "早记录计数进 JSON: " + ciLine);
            assertTrue(ciLine.contains("\"unapprovedEarlier\":1"), "未批准草稿计数进 JSON: " + ciLine);
        }

        @Test
        @DisplayName("幂等：画像建立后旧证据 CI 判 PASS")
        void establishedProfile_oldEvidence_passes() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null));
        }

        @Test
        @DisplayName("首航：单链任务 CI 也判定（不再 selfEstablished 跳过）；任务规则违规折 exit 1")
        void singleChain_judged_andTaskRulesFold() {
            InteractionRecord seed = saveDeclaredTaskRecord("a-1", "session-a", 1000L, "{\"result\":\"ok\"}");
            establishFromRecord(seed);
            InvocationRulesConfig taskRules = InvocationRulesConfig.fromJson("{\"tasks\":{\"查订单\":{\"requiredSteps\":[\"confirm\"]}}}");

            TaskReplayRunner ruleRunner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), taskRules, TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
            output.reset();
            int exit = ruleRunner.run(null, null, true, false, false, null, false, false, false, null, null);

            assertEquals(1, exit, "requiredSteps 缺失在 CI 首航即批改");
            assertTrue(output.toString().contains("baseline comparison (--ci)"), "单链任务必须发生基线对照: " + output);
            assertFalse(output.toString().contains("first recording becomes the baseline"), "CI 模式不得走 selfEstablished 跳过");
        }

        @Test
        @DisplayName("报告钉：mode=ci-align 可区分，步骤显示 baselineVersion，成本只出 current 侧")
        void reportShape_ciAlign() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            runner.run(null, null, true, false, false, null, false, false, false, null, null);
            assertTrue(output.toString().contains("approved fingerprints, no recorded cost"), "Cost 行只出 current 侧并就地说明");
            assertFalse(output.toString().contains("Cost: baseline"), "不得出基线侧成本");

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            jsonRunner.run(null, null, true, false, false, null, false, false, false, null, null);

            String ciLine = reportLine(output.toString(), "\"mode\":\"ci-align\"");
            assertNotNull(ciLine);
            assertTrue(ciLine.contains("\"baselineVersion\":\"v1\""), "步骤必须显示画像版本: " + ciLine);
            assertFalse(ciLine.contains("\"baseline\":{\"tokens\""), "基线侧无记录，不得出基线成本对象");
            assertTrue(ciLine.contains("\"current\":{\"tokens\""), "current 侧成本在场");
            assertTrue(ciLine.contains("\"baselineTime\":"), "baselineTime=approvedAt 在场");
        }

        @Test
        @DisplayName("member-check 守护：ciMode 语义下成员判定仍走链采样（mode=member-check）")
        void memberCheck_keepsChainSampling() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            jsonRunner.run(null, null, true, false, true, null, false, false, false, null, null);

            String out = output.toString();
            assertNotNull(reportLine(out, "\"mode\":\"member-check\""), "成员判定不得被基线对照改写: " + out);
            assertNull(reportLine(out, "\"mode\":\"ci-align\""), "member-check 与 ci-align 互斥");
        }

        @Test
        @DisplayName("两链窗口钉：C2 vs C3 链对链 PASS，而 CI 对照画像 CHANGED（窗口外漂移被点破）")
        void twoChainWindow_ciExposesUnadjudicatedDrift() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            int devExit = runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertEquals(0, devExit, "链对链（C2 vs C3 同行为）PASS——两链窗口看不见历史漂移");

            TaskReplayRunner ciRunner = newRunner(false);
            output.reset();
            assertEquals(1, ciRunner.run(null, null, true, false, false, null, false, false, false, null, null), "CI 对照批准指纹点破窗口外漂移");
        }

        @Test
        @DisplayName("处置对账：行为候选已落时 candidatesRegistered=1 而 candidatePoints=0（双口径分列）")
        void dispositionCounts_behaviorCandidateWithoutDrift() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            assertEquals(1, jsonRunner.run(null, null, true, false, false, null, false, false, false, null, null));

            String dispositionLine = reportLine(output.toString(), "\"mode\":\"drift-disposition\"");
            assertNotNull(dispositionLine, "必须有处置报告行: " + output);
            assertTrue(dispositionLine.contains("\"candidatePoints\":0"), "无模板漂移点时漂移域计数为 0: " + dispositionLine);
            assertTrue(dispositionLine.contains("\"candidatesRegistered\":1"), "对齐域候选计数必须与 Candidate registered 行同源对账: " + dispositionLine);
        }

        @Test
        @DisplayName("skippedPairs 释义移驻 bare 链对链：首 CHANGED 早停后未检配对有人读注记（ci 面链末判定下恒 0）")
        void skippedPairs_explainedInReport_barePairing() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 3000L, "查订单", "order", "hash-a", "{\"v\":2,\"w\":3}", null);
            saveRecord("b-2", "session-b", 4000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            int exit = runner.run(null, null, false, false, false, null, false, false, false, null, null);

            assertEquals(1, exit, "bare 链对链首配对即差异");
            String out = output.toString();
            assertTrue(out.contains("not examined after the first difference"), "早停未检配对必须就地释义: " + out);
            assertTrue(out.contains("the step verdict is already CHANGED"), "释义必须点明步骤判定已定: " + out);
        }

        @Test
        @DisplayName("T2 闭环：混合链 accept 后同链复检即绿（CC Round5 原始预期）")
        void mixedChain_acceptThenSameChainRecheck_green() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);

            assertEquals(1, runner.run(null, null, true, false, false, null, false, false, false, null, null), "链末坏形态先挡门");
            assertNotNull(repository.findInvocationByKey("invocation:order:hash-a").getCandidateFingerprint(), "CHANGED 步照落候选");

            new BaselineManager(repository).accept("invocation:order:hash-a", null, "tester", null);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "accept 提升链末形态后，同一证据复检必须绿");
        }

        @Test
        @DisplayName("多形态收敛：同键稳定两形态——accept 链末形态后复检恒绿不摆振")
        void multiShapeInvocation_acceptConverges_noPendulum() {
            InteractionRecord first = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"shape\":\"one\"}", null);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"shape\":\"two\",\"extra\":true}", null);
            establishFromRecord(first);

            assertEquals(1, runner.run(null, null, true, false, false, null, false, false, false, null, null), "建档种子=形态一，链末形态二 → CHANGED 落候选");
            new BaselineManager(repository).accept("invocation:order:hash-a", null, "tester", null);

            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "accept 形态二后同链复检绿");
            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "再复检仍绿——判定对象=链末，不随历史摆振");
        }

        @Test
        @DisplayName("链末判定对象=最新链的组末：第二链链末复检对已 accept 形态 PASS")
        void chainFinal_judgedObjectIsLatestChainGroupEnd() {
            InteractionRecord first = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"shape\":\"one\"}", null);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"shape\":\"two\",\"extra\":true}", null);
            establishFromRecord(first);
            saveRecord("b-1", "session-b", 3000L, "查订单", "order", "hash-a", "{\"shape\":\"one\"}", null);
            saveRecord("b-2", "session-b", 4000L, "查订单", "order", "hash-a", "{\"shape\":\"two\",\"extra\":true}", null);

            assertEquals(1, runner.run(null, null, true, false, false, null, false, false, false, null, null), "最新链(session-b)链末=形态二 → CHANGED");
            new BaselineManager(repository).accept("invocation:order:hash-a", null, "tester", null);
            assertEquals(0, runner.run(null, null, true, false, false, null, false, false, false, null, null), "复检判定最新链链末（已批准形态）→ PASS");
        }

        @Test
        @DisplayName("守卫窄化：草稿键未建档不拒绝判定（judged 键已建档照常对照）；裂键缺口由漂移层披露")
        void ciGuard_narrowedToJudgedKeys() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-old", "{\"result\":\"ok\"}", null);
            InteractionRecord finalShape = saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(finalShape);

            int exit = runner.run(null, null, true, false, false, null, false, false, false, null, null);

            String out = output.toString();
            assertFalse(out.contains("Refusing to judge in --ci mode"), "判定不再被草稿键拒绝——judged 键已建档: " + out);
            assertTrue(out.contains("PASS 1"), "链末对照照常判定且通过: " + out);
            assertTrue(out.contains("unapproved shape"), "无画像草稿计为未批准（透明层）: " + out);
            assertNull(repository.findInvocationByKey("invocation:order:hash-old"), "ci 模式不顺手为草稿键建档");
            assertEquals(1, exit, "同标签异哈希草稿=标签裂键，作为证据缺口由漂移层挂起（身份卫生问题独立于基线对照）");
            assertTrue(out.contains("Hung: order@hash-old"), "挂起点就地指名: " + out);
        }

        @Test
        @DisplayName("dry-run --ci 计划面：baselineVersions 列出对照版本，未建档键显式 null")
        void dryRunCi_planCarriesBaselineVersions() {
            InteractionRecord seed = saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            establishFromRecord(seed);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-b", "{\"result\":\"ok\"}", null);

            TaskReplayRunner jsonRunner = newRunner(true);
            output.reset();
            assertEquals(0, jsonRunner.run(null, null, true, true, false, null, false, false, false, null, null));

            String planLine = reportLine(output.toString(), "\"mode\":\"task-dry-run\"");
            assertNotNull(planLine, "必须有计划行: " + output.toString());
            assertTrue(planLine.contains("\"ciAlign\":true"), planLine);
            assertTrue(planLine.contains("\"baselineVersions\":[{\"invocationKey\":\"invocation:order:hash-b\",\"versionTag\":null}]"), "计划只列链末判定将对照的键（同标签早记录键不入列，未建档 null 显式）: " + planLine);
            assertTrue(planLine.contains("\"newSteps\":1"), "计划步数=链末调用点数（同标签两记录裁剪为一）: " + planLine);
        }
    }

    @Nested
    @DisplayName("受控重驱：逐漂移点以最新归档模板真重驱")
    class ReDrive {

        private void seedArchivedSkeletonDrift(String newResponse) {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", newResponse);
            establishedProfile("invocation:order:skl-1", "order", "hash-old");
            saveTemplateText("hash-new", "新模板全文");
            saveTemplateText("hash-old", "旧模板全文");
        }

        @Test
        @DisplayName("重驱 PASS → 对齐收编 + 重驱通过，退出码 0，恰一次调用")
        void reDrive_pass() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, null, false, true, false, null, null));
            assertEquals(1, stubClient.calls, "仅漂移点重驱，恰一次调用");
            assertTrue(output.toString().contains("Re-drive:"));
            assertTrue(output.toString().contains("re-drive PASS"));
        }

        @Test
        @DisplayName("重驱 CHANGED → 退出码 1 并经执行器落候选")
        void reDrive_changed() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            stubClient.setScriptedContent("{\"verdict\":\"flipped\"}");

            assertEquals(1, runner.run(null, null, false, false, false, null, false, true, false, null, null));
            assertNotNull(repository.findInvocationByKey("invocation:order:skl-1").getCandidateFingerprint(), "重驱 CHANGED 必须落候选");
        }

        @Test
        @DisplayName("预算池恰发：两漂移点上限 1 次 → 1 调用 + 1 跳过，退出码 2")
        void reDrive_budget() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            saveSkeletonRecord("c-1", "session-c", 3000L, "写诗", "other", "skl-2", "hash-o2", "{\"result\":\"ok\"}");
            saveSkeletonRecord("c-2", "session-c2", 3100L, "写诗", "other", "skl-2", "hash-o2", "{\"result\":\"ok\"}");
            establishedProfile("invocation:other:skl-2", "other", "hash-old");
            saveTemplateText("hash-o2", "另一模板全文");

            assertEquals(2, runner.run(null, null, false, false, false, null, false, true, false, 1, null));
            assertEquals(1, stubClient.calls);
            assertTrue(output.toString().contains("budget exhausted"));
        }

        @Test
        @DisplayName("重驱全败（无任何比对结果）→ 退出码 2 而非误报回归")
        void reDrive_allFailed() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            stubClient.failWith(new LlmApiException("simulated outage"));

            assertEquals(2, runner.run(null, null, false, false, false, null, false, true, false, null, null));
            assertTrue(output.toString().contains("All re-drive calls failed"));
        }

        @Test
        @DisplayName("归档模板原文缺席 → 跳过计数可见，退出码 2")
        void reDrive_missingTemplateText() {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:skl-1", "order", "hash-old");

            assertEquals(2, runner.run(null, null, false, false, false, null, false, true, false, null, null));
            assertEquals(0, stubClient.calls);
            assertTrue(output.toString().contains("archived template text missing"));
        }

        @Test
        @DisplayName("--full-chain 扩域：非漂移调用点同样重驱")
        void reDrive_fullChain() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            saveRecord("x-1", "session-x", 1500L, "查订单", "plain", "hash-p", "{\"result\":\"ok\"}", null);
            saveRecord("x-2", "session-x2", 2500L, "查订单", "plain", "hash-p", "{\"result\":\"ok\"}", null);
            establishedProfile("invocation:plain:hash-p", "plain", "hash-p");
            saveTemplateText("hash-p", "普通模板全文");

            runner.run(null, null, false, false, false, null, false, true, true, null, null);
            assertTrue(stubClient.calls >= 2, "扩域后非漂移点也应重驱: " + stubClient.calls);
        }

        @Test
        @DisplayName("缩域即重驱域：--re-drive --task 无漂移也重驱缩域内调用点")
        void reDrive_narrowedScope_drivesWithoutDrift() {
            saveIdenticalLabeledChains();
            saveTemplateText("hash-a", "归档模板全文");
            stubClient.setScriptedContent("{\"v\":1}");

            assertEquals(0, runner.run("查订单", null, false, false, false, null, false, true, false, null, null));
            assertEquals(1, stubClient.calls, "缩域内调用点应被重驱");
            assertTrue(output.toString().contains("(all invocations in scope)"), output.toString());
        }

        @Test
        @DisplayName("bare 重驱无漂移仍为零目标（漂移点裁剪不因缩域缺省生效）")
        void reDrive_bare_zeroDrift_zeroTargets() {
            saveIdenticalLabeledChains();

            assertEquals(0, runner.run(null, null, false, false, false, null, false, true, false, null, null));
            assertEquals(0, stubClient.calls);
            assertTrue(output.toString().contains("(drift points only)"), output.toString());
        }

        @Test
        @DisplayName("dry-run 重驱：只出成本预估，零调用")
        void reDrive_dryRun_estimateOnly() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, true, false, null, false, true, false, null, null));
            assertEquals(0, stubClient.calls);
            assertTrue(output.toString().contains("Re-drive plan"));
        }
    }

    @Nested
    @DisplayName("JSON 报告契约（task-report/1）")
    class JsonContract {

        @Test
        @DisplayName("bare 执行产出 检测/对齐/处置 三段单行 JSON")
        void bareEmitsThreeStages() {
            saveIdenticalLabeledChains();
            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            TaskReplayRunner jsonRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);

            assertEquals(0, jsonRunner.run(null, null, false, false, false, null, false, false, false, null, null));

            String[] lines = jsonOut.toString().trim().split("\n");
            assertEquals("agentassert4j.task-report/1", extractJsonField(lines[0], "schema").replace("\"", ""));
            assertTrue(lines[0].contains("\"mode\":\"drift-detection\""), lines[0]);
            assertTrue(hasLineWithMode(lines, "task-align"), "必须有对齐报告行");
            assertTrue(hasLineWithMode(lines, "drift-disposition"), "必须有处置报告行");
        }

        @Test
        @DisplayName("漂移 + PASS 的处置 JSON 动作为 collected")
        void dispositionJson_collected() {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:skl-1", "order", "hash-old");
            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            TaskReplayRunner jsonRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);

            assertEquals(0, jsonRunner.run(null, null, false, false, false, null, false, false, false, null, null));

            assertTrue(jsonOut.toString().contains("\"action\":\"collected\""), jsonOut.toString());
            assertTrue(jsonOut.toString().contains("\"mode\":\"drift-detection\""));
            assertTrue(jsonOut.toString().contains("\"sameKey\":1"));
        }

        private String extractJsonField(String line, String field) {
            int at = line.indexOf("\"" + field + "\":");
            if (at < 0) {
                return "";
            }
            int start = line.indexOf(':', at) + 1;
            int end = start;
            while (end < line.length() && line.charAt(end) != ',' && line.charAt(end) != '}') {
                end++;
            }
            return line.substring(start, end);
        }

        private boolean hasLineWithMode(String[] lines, String mode) {
            for (String line : lines) {
                if (line.contains("\"mode\":\"" + mode + "\"")) {
                    return true;
                }
            }
            return false;
        }
    }

    private TaskReplayRunner jsonRunner(ByteArrayOutputStream jsonOut) {
        return new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(jsonOut, true), true);
    }

    private static boolean hasLineWithMode(String[] lines, String mode) {
        for (String line : lines) {
            if (line.contains("\"mode\":\"" + mode + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 无标签多步链载体：同会话两条同文本记录并链（链派生契约），两步均无声明标签。
     */
    private void seedUnlabeledMultiStepChain(String sessionId, long baseTs) {
        InteractionRecord first = new InteractionRecord();
        first.setRecordId(sessionId + "-1");
        first.setSessionId(sessionId);
        first.setTimestamp(baseTs);
        first.setSeq(baseTs);
        first.setUserInput("问一下库存");
        first.setTemplateHash("hash-a");
        first.setInvocationKey("invocation:stock:hash-a");
        first.setModelResponse("{\"stock\":1}");
        repository.saveInteractionIfAbsent(first);
        InteractionRecord second = new InteractionRecord();
        second.setRecordId(sessionId + "-2");
        second.setSessionId(sessionId);
        second.setTimestamp(baseTs + 1000);
        second.setSeq(baseTs + 1000);
        second.setUserInput("问一下库存");
        second.setTemplateHash("hash-a");
        second.setInvocationKey("invocation:stock:hash-a");
        second.setModelResponse("{\"stock\":2}");
        repository.saveInteractionIfAbsent(second);
    }

    @Nested
    @DisplayName("成员判定：--member-check 样本窗")
    class MemberCheck {

        @Test
        @DisplayName("窗口=1：样本可见性随窗口伸缩——只看最近一条时不命中，窗口=2 命中")
        void windowN_limitsSampleVisibility() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1,\"w\":{}}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            TaskReplayRunner narrow = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
            assertEquals(1, narrow.run(null, null, false, false, true, Integer.valueOf(1), false, false, false, null, null), "窗口=1 只见最近一条（形态不同）→ 不命中");
            assertTrue(output.toString().contains("against the 1 most recent chain(s)"), output.toString());
            assertTrue(output.toString().contains("(window 1)"), "人读行披露解析后窗口: " + output);

            output.reset();
            TaskReplayRunner wide = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
            assertEquals(0, wide.run(null, null, false, false, true, Integer.valueOf(2), false, false, false, null, null), "窗口=2 见 [b,a]，a 命中");
        }

        @Test
        @DisplayName("window=all：穿透全部历史链——考古命中可辨（matched 1 of N 旧会话）")
        void windowAll_scansEveryChain_archaeologyDisclosed() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1,\"w\":{}}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"v\":1,\"w\":{},\"x\":{}}", null);
            saveRecord("d-1", "session-d", 4000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            TaskReplayRunner archaeologist = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), true);

            assertEquals(0, archaeologist.run(null, null, false, false, true, null, true, false, false, null, null));
            String report = output.toString();
            assertTrue(report.contains("\"window\":\"all\""), "JSON 披露 all 窗口: " + report);
            assertTrue(report.contains("\"matched\":1"), "只命中 1 条旧链（考古命中非稳定信号，计数可辨）: " + report);
            assertTrue(report.contains("\"matchedSessions\":[\"session-a\"]"), "命中会话列表在场: " + report);
        }

        @Test
        @DisplayName("默认窗 5：解析阶梯缺省侧（显式与配置都缺席）→ 内置 5")
        void defaultWindow_five() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            TaskReplayRunner memberRunner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
            assertEquals(0, memberRunner.run(null, null, false, false, true, null, false, false, false, null, null));
            assertTrue(output.toString().contains("(window 5)"), "缺省窗口=内置 5: " + output);
        }

        @Test
        @DisplayName("新链匹配任一历史链即成员（默认配对下本应 CHANGED）→ 退出码 0")
        void memberMatchesHistoricalChain_pass() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":2}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            TaskReplayRunner memberRunner = new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);

            assertEquals(0, memberRunner.run(null, null, false, false, true, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Member: behavior matches 2 of 2 sampled chain(s) (sessions session-a, session-b)"), output.toString());
        }

        @Test
        @DisplayName("全样本不匹配 → CHANGED，最接近样本平局取最早")
        void noMemberMatch_closestTieTakesEarliest() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("c-1", "session-c", 3000L, "查订单", "order", "hash-a", "{\"v\":9,\"extra\":true}", null);

            assertEquals(1, runner.run(null, null, false, false, true, null, false, false, false, null, null));
            String report = output.toString();
            assertTrue(report.contains("No member match: closest historical chain is session session-a"), report);
        }

        @Test
        @DisplayName("全样本无可比对步骤（缺/新增）→ 最接近样本仍取最早，不 NPE")
        void noComparableEvidence_closestStillDefined() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "other", "hash-b", "{\"v\":1}", null);

            assertEquals(1, runner.run(null, null, false, false, true, null, false, false, false, null, null));
            String report = output.toString();
            assertTrue(report.contains("No member match: closest historical chain is session session-a"), report);
        }

        @Test
        @DisplayName("样本窗封顶 5：7 条历史链只核最近 5 条，JSON 报告携带窗与成员字段")
        void sampleWindowCappedAtFive_jsonReport() {
            for (int i = 1; i <= 7; i++) {
                saveRecord("r-" + i, "session-" + i, 1000L * i, "查订单", "order", "hash-a", "{\"v\":1}", null);
            }

            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            assertEquals(0, jsonRunner(jsonOut).run(null, null, false, false, true, null, false, false, false, null, null));

            String[] lines = jsonOut.toString().trim().split("\n");
            assertTrue(hasLineWithMode(lines, "member-check"), "必须有成员判定报告行");
            String memberLine = "";
            for (String line : lines) {
                if (line.contains("\"mode\":\"member-check\"")) {
                    memberLine = line;
                }
            }
            assertTrue(memberLine.contains("\"checked\":5,\"window\":5"), memberLine);
            assertTrue(memberLine.contains("\"isMember\":true"), memberLine);
            assertTrue(memberLine.contains("\"matchedSession\":\"session-2\""), "样本窗为最近 5 条（session-2..6），升序迭代首个匹配即报告: " + memberLine);
        }
    }

    @Nested
    @DisplayName("首航批改与出口健康摘要")
    class FirstVoyageAndExitHealth {

        @Test
        @DisplayName("首航即批改：单链任务违反声明任务规则 → 退出码 1 并报告违规")
        void firstVoyage_taskRuleViolation_exits1() {
            saveDeclaredTaskRecord("a-1", "session-a", 1000L, "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"查订单\":{\"requiredSteps\":[\"confirm\"]}}}");
            TaskReplayRunner ruledRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), rules, TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);

            assertEquals(1, ruledRunner.run(null, null, false, false, false, null, false, false, false, null, null));
            assertTrue(output.toString().contains("Task rule violation"), output.toString());
            assertTrue(output.toString().contains("Task rules apply from the first recording"), output.toString());
        }

        @Test
        @DisplayName("出口健康摘要：人读行携带自建与零标签计数，全零不打印")
        void exitHealth_humanLine() {
            seedUnlabeledMultiStepChain("session-a", 1000L);

            assertEquals(0, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            String report = output.toString();
            assertTrue(report.contains("Health: 0 label splits, 1 self-established task, 1 multi-step unlabeled chain"), report);
        }

        @Test
        @DisplayName("出口健康摘要：--json 模式以 exit-health 报告行收尾")
        void exitHealth_reportLine() {
            seedUnlabeledMultiStepChain("session-a", 1000L);

            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            assertEquals(0, jsonRunner(jsonOut).run(null, null, false, false, false, null, false, false, false, null, null));

            String[] lines = jsonOut.toString().trim().split("\n");
            assertTrue(lines[lines.length - 1].contains("\"mode\":\"exit-health\""), lines[lines.length - 1]);
            assertTrue(lines[lines.length - 1].contains("\"selfEstablishedTasks\":1"), lines[lines.length - 1]);
        }
    }

    @Nested
    @DisplayName("优化信号与稳定性注记")
    class SignalAndStability {

        /**
         * 同键双记录：基线链两步同响应；新链首步结构变化、次步恢复。
         */
        private void seedPairChainWithEarlyStop() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("a-2", "session-a", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 3000L, "查订单", "order", "hash-a", "{\"v\":2,\"w\":3}", null);
            saveRecord("b-2", "session-b", 4000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
        }

        @Test
        @DisplayName("优化信号与配对计数：首个 CHANGED 早停后 comparedPairs=1、skippedPairs=1")
        void signalAndPairCounts_json() {
            seedPairChainWithEarlyStop();

            ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
            assertEquals(1, jsonRunner(jsonOut).run(null, null, false, false, false, null, false, false, false, null, null));

            String[] lines = jsonOut.toString().trim().split("\n");
            String alignLine = "";
            for (String line : lines) {
                if (line.contains("\"mode\":\"task-align\"") && line.contains("\"steps\":[")) {
                    alignLine = line;
                }
            }
            assertFalse(alignLine.isEmpty(), "必须有 task-align 报告行");
            assertTrue(alignLine.contains("\"comparedPairs\":1,\"skippedPairs\":1"), alignLine);
            assertTrue(alignLine.contains("\"signal\":{\"similarity\":0."), alignLine);
            assertTrue(alignLine.contains("\"steps\":1}"), alignLine);
        }

        @Test
        @DisplayName("稳定性注记：跨历史波动的人读行提示不追噪音")
        void stabilityNote_fluctuatingPoint() {
            seedPairChainWithEarlyStop();

            assertEquals(1, runner.run(null, null, false, false, false, null, false, false, false, null, null));
            String report = output.toString();
            assertTrue(report.contains("Stability: 2 executions, 1 of 1 invocation point fluctuated across history"), report);
            assertTrue(report.contains("do not chase noise"), report);
        }
    }

    /**
     * 可编程桩：缺省路径（检测/对齐/处置）永不触发 chat；受控重驱按脚本应答，
     * 调用计数供预算与目标集断言。
     */
    static class StubLlmClient implements LlmClient {
        private String scriptedContent = "{\"result\":\"ok\"}";
        private LlmApiException failure;
        int calls;

        void setScriptedContent(String content) {
            this.scriptedContent = content;
        }

        void failWith(LlmApiException failure) {
            this.failure = failure;
        }

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            LlmResponse response = new LlmResponse();
            response.setContent(scriptedContent);
            response.setInputTokens(10);
            response.setOutputTokens(5);
            return response;
        }

        @Override
        public String name() {
            return "stub-model";
        }
    }
}
