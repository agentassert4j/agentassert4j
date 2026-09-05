package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

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

    private TaskReplayRunner newRunner(boolean jsonMode) {
        return new TaskReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), jsonMode);
    }

    private InteractionRecord saveRecord(String recordId, String sessionId, long ts, String response) {
        return saveRecord(recordId, sessionId, ts, "查订单", "order", "hash-a", response, null);
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
        repository.saveInteraction(r);
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
        repository.saveInteraction(r);
        return r;
    }

    /**
     * 声明任务键的记录：metadata 携带 taskKey（任务规则只对声明链生效）。
     */
    private void saveDeclaredTaskRecord(String recordId, String sessionId, long ts, String response) {
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
        repository.saveInteraction(r);
    }

    private InvocationProfile establishedProfile(String invocationKey, String label, String templateHash) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(invocationKey);
        p.setLabel(label);
        p.setTemplateHash(templateHash);
        p.setInvocationName(label);
        p.setInvocationType(InvocationType.PURE_CHAT);
        p.setFingerprint(new DeterministicFingerprint());
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1");
        p.setAlgoVersion(JudgmentSemantics.VERSION);
        repository.saveInvocationProfile(p);
        return p;
    }

    /**
     * 两个同名任务链（两条 session），逐步记录相同响应 → 对齐应全 PASS。
     */
    private void seedIdenticalChains(String response) {
        saveRecord("a-1", "session-a", 1000L, response);
        saveRecord("b-1", "session-b", 2000L, response);
    }

    @Nested
    @DisplayName("真实对齐：逐任务最新链 vs 次新链")
    class Alignment {

        @Test
        @DisplayName("同构链全 PASS → 退出码 0")
        void identicalChains_pass() {
            seedIdenticalChains("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("步骤行为差异（结构指纹不同）→ 退出码 1 并落候选")
        void stepDiff_changed_exits1_andRegistersCandidate() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"changed\":true}", null);
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            int exit = runner.run(null, null, false, false, false, false, null, null);

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

            int exit = runner.run(null, null, false, false, false, false, null, null);

            assertEquals(1, exit);
            assertTrue(output.toString().contains("missing step"));
        }

        @Test
        @DisplayName("新增步骤（新链出现基线没有的调用点）→ 退出码 1")
        void addedStep_exits1() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"result\":\"ok\"}", null);
            saveRecord("b-2", "session-b", 2100L, "查订单", "confirm", "hash-c", "{\"result\":\"ok\"}", null);

            assertEquals(1, runner.run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("仅一条链 → 自建基线，退出码 0")
        void singleChain_selfEstablish() {
            saveRecord("a-1", "session-a", 1000L, "{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("first recording becomes the baseline"));
        }

        @Test
        @DisplayName("同文本多链取最新 vs 次新，多轮执行不触发歧义")
        void multipleChains_sameTask_pairsLatestTwo() {
            saveRecord("a-1", "session-a", 1000L, "{\"v\":1}");
            saveRecord("b-1", "session-b", 2000L, "{\"v\":1}");
            saveRecord("c-1", "session-c", 3000L, "{\"v\":1}");

            assertEquals(0, runner.run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("任务规则必备步骤缺失 → 退出码 1 并报告违规")
        void taskRuleViolation_exits1() {
            saveDeclaredTaskRecord("a-1", "session-a", 1000L, "{\"result\":\"ok\"}");
            saveDeclaredTaskRecord("b-1", "session-b", 2000L, "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"tasks\":{\"查订单\":{\"requiredSteps\":[\"confirm\"]}}}");
            TaskReplayRunner ruledRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), rules, TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);

            assertEquals(1, ruledRunner.run(null, null, false, false, false, false, null, null));
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

            assertEquals(0, runner.run("V1 请求", null, false, false, false, false, null, null));
            assertFalse(output.toString().contains("V1 请求扩展"), "精确命中不得扩选到前缀家族: " + output);
        }

        @Test
        @DisplayName("前缀命中多个任务文本 → 歧义报错退出码 2")
        void ambiguousPrefix_exits2() {
            saveRecord("a-1", "session-a", 1000L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("b-1", "session-b", 2000L, "V2 请求", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(2, runner.run("V", null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("前缀唯一候选直接采用")
        void uniquePrefix_adopted() {
            saveRecord("a-1", "session-a", 1000L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);
            saveRecord("a-2", "session-a2", 1100L, "V1 请求", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(0, runner.run("V", null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("前缀无命中 → 退出码 2")
        void noMatch_exits2() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);

            assertEquals(2, runner.run("不存在的任务", null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("--invocation 缩域到含该键的任务链，域外任务不对齐")
        void invocationNarrowing_filtersChains() {
            saveRecord("a-1", "session-a", 1000L, "查订单");
            saveRecord("x-1", "session-x", 1500L, "写诗");
            saveRecord("b-1", "session-b", 2000L, "查订单");

            assertEquals(0, runner.run(null, "invocation:order:hash-a", false, false, false, false, null, null));
            assertFalse(output.toString().contains("写诗"), "缩域外任务不应进入对齐输出: " + output);
        }

        @Test
        @DisplayName("--task 与 --invocation 复合 AND：交集为空退出码 2")
        void compositeAnd_emptyIntersection_exits2() {
            saveRecord("a-1", "session-a", 1000L, "查订单");
            saveRecord("x-1", "session-x", 1500L, "写诗");

            assertEquals(2, runner.run("查订单", "invocation:order:hash-b", false, false, false, false, null, null));
        }
    }

    @Nested
    @DisplayName("漂移处置状态机：收编 / 候选 / 挂起")
    class DriftStateMachine {

        /**
         * 骨架键画像（哈希停留在旧全文）+ 两条骨架链（新链全文哈希已变、行为一致）。
         */
        private void seedSkeletonDrift(String newResponse) {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", newResponse);
            establishedProfile("invocation:order:skl-1", "order", "hash-old");
        }

        @Test
        @DisplayName("漂移 + 对齐 PASS（开发态）→ 自动收编前移身份，退出码 0，检测收敛")
        void driftPass_dev_collects() {
            seedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, false, false, null, null));

            assertEquals("hash-new", repository.findInvocationByKey("invocation:order:skl-1").getTemplateHash(), "画像身份必须前移到最新记录哈希");
            assertTrue(output.toString().contains("Collected:"));
            runner.run(null, null, false, false, false, false, null, null);
            assertTrue(output.toString().contains("template identities consistent"), "收编后检测不应再命中");
        }

        @Test
        @DisplayName("漂移 + 对齐 PASS（--ci）→ 不收编，警告可见，退出码 0，身份不动")
        void driftPass_ci_keepsStale() {
            seedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, true, false, false, false, null, null));

            assertEquals("hash-old", repository.findInvocationByKey("invocation:order:skl-1").getTemplateHash(), "CI 模式不得落治理写");
            assertTrue(output.toString().contains("Identity not collected"));
        }

        @Test
        @DisplayName("漂移 + 对齐 CHANGED → 落候选，退出码 1")
        void driftChanged_registersCandidate() {
            seedSkeletonDrift("{\"changed\":true}");

            assertEquals(1, runner.run(null, null, false, false, false, false, null, null));

            InvocationProfile profile = repository.findInvocationByKey("invocation:order:skl-1");
            assertNotNull(profile.getCandidateFingerprint(), "漂移 + CHANGED 必须落候选");
            assertEquals("hash-old", profile.getTemplateHash(), "候选路径不得前移身份");
        }

        @Test
        @DisplayName("漂移 + 缺步骤 → 挂起：不收编不落候选，退出码 1")
        void driftWithMissingStep_hangs() {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:skl-1", "order", "hash-old");

            assertEquals(1, runner.run(null, null, false, false, false, false, null, null));

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

            assertEquals(1, runner.run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("Hung:"));
        }

        @Test
        @DisplayName("缩域外漂移只进检测报告不处置，不贡献退出码")
        void externalDrift_reportedOnly() {
            establishedProfile("invocation:lonely:skl-1", "lonely", "hash-old");
            saveSkeletonRecord("l-1", "session-l", 1500L, "查订单", "lonely", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            saveRecord("x-1", "session-x", 1000L, "写诗任务", "poem", "hash-p", "{\"result\":\"ok\"}", null);
            saveRecord("x-2", "session-x2", 2000L, "写诗任务", "poem", "hash-p", "{\"result\":\"ok\"}", null);

            assertEquals(0, runner.run("写诗", null, false, false, false, false, null, null));
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

            assertEquals(1, runner.run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("Hung:"));
            assertFalse(output.toString().contains("Drift outside scope"), "缩域内的键不得误标域外: " + output);
        }

        @Test
        @DisplayName("标签裂键（声明无骨架全文变更）→ 开发态建档即收编，对齐 PASS 出 0")
        void labelSplit_dev_establishes() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-old", "{\"result\":\"ok\"}", null);
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-new", "{\"result\":\"ok\"}", null);
            establishedProfile("invocation:order:hash-old", "order", "hash-old");

            assertEquals(0, runner.run(null, null, false, false, false, false, null, null));

            assertNotNull(repository.findInvocationByKey("invocation:order:hash-new"), "裂键新档必须已建立");
            assertTrue(output.toString().contains("new profile established"));
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

            assertEquals(2, runner.run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("Judgment semantics version mismatch"));
        }

        @Test
        @DisplayName("未标记语义版本的基线同样拒绝")
        void unstampedAlgoVersion_refuses() {
            seedIdenticalChains("{\"v\":1}");
            InvocationProfile profile = establishedProfile("invocation:order:hash-a", "order", "hash-a");
            profile.setAlgoVersion(null);
            repository.saveInvocationProfile(profile);

            assertEquals(2, runner.run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("--ci 未建档调用点拒绝判定并列出名单，退出码 2")
        void ciRefusesUnbaselined() {
            saveIdenticalLabeledChains();

            assertEquals(2, runner.run(null, null, true, false, false, false, null, null));
            assertTrue(output.toString().contains("Refusing to judge in --ci mode"));
        }

        @Test
        @DisplayName("--ci 基线齐备时正常判定放行")
        void ciPassesWhenAllEstablished() {
            saveIdenticalLabeledChains();
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            assertEquals(0, runner.run(null, null, true, false, false, false, null, null));
        }

        @Test
        @DisplayName("换模型执行时告警（判定结果不与基线直接可比）")
        void modelDiffers_warns() {
            seedIdenticalChains("{\"v\":1}");
            TestExecutionConfig withModel = new TestExecutionConfig().timeoutMs(1000).temperature(null).model("another-model");
            TaskReplayRunner modelRunner = new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), withModel, new PrintStream(output, true), new PrintStream(output, true), false);

            modelRunner.run(null, null, false, false, false, false, null, null);

            assertTrue(output.toString().contains("differs from recorded models"), "换模型必须告警: " + output);
        }

        @Test
        @DisplayName("基线与新链 served 模型族不相交 → 模型身份变更注记")
        void servedModelPairNoted() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-a");
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-b");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            runner.run(null, null, false, false, false, false, null, null);

            assertTrue(output.toString().contains("model identity changed"));
        }

        @Test
        @DisplayName("逐步 served 模型不一致就地标注")
        void servedModelStepNoted() {
            saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", "model-a");
            saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":2}", "model-b");
            establishedProfile("invocation:order:hash-a", "order", "hash-a");

            runner.run(null, null, false, false, false, false, null, null);

            assertTrue(output.toString().contains("(served: model-b, baseline: model-a)"));
        }

        @Test
        @DisplayName("dry-run 只读：不建档不落图快照，输出对齐计划，退出码 0")
        void dryRun_readOnly() {
            saveIdenticalLabeledChains();

            assertEquals(0, runner.run(null, null, false, true, false, false, null, null));

            assertNull(repository.findInvocationByKey("invocation:order:hash-a"), "dry-run 不得建档");
            assertNull(repository.loadGraph(), "dry-run 不得落图快照");
            assertTrue(output.toString().contains("Alignment plan"));
        }
    }

    private void saveIdenticalLabeledChains() {
        saveRecord("a-1", "session-a", 1000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
        saveRecord("b-1", "session-b", 2000L, "查订单", "order", "hash-a", "{\"v\":1}", null);
    }

    @Nested
    @DisplayName("受控重驱：逐漂移点以最新归档模板真重驱")
    class ReDrive {

        private void seedArchivedSkeletonDrift(String newResponse) {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", newResponse);
            establishedProfile("invocation:order:skl-1", "order", "hash-old");
            repository.saveTemplateText("hash-new", "新模板全文");
            repository.saveTemplateText("hash-old", "旧模板全文");
        }

        @Test
        @DisplayName("重驱 PASS → 对齐收编 + 重驱通过，退出码 0，恰一次调用")
        void reDrive_pass() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, false, true, false, null, null));
            assertEquals(1, stubClient.calls, "仅漂移点重驱，恰一次调用");
            assertTrue(output.toString().contains("Re-drive:"));
            assertTrue(output.toString().contains("re-drive PASS"));
        }

        @Test
        @DisplayName("重驱 CHANGED → 退出码 1 并经执行器落候选")
        void reDrive_changed() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            stubClient.setScriptedContent("{\"verdict\":\"flipped\"}");

            assertEquals(1, runner.run(null, null, false, false, true, false, null, null));
            assertNotNull(repository.findInvocationByKey("invocation:order:skl-1").getCandidateFingerprint(), "重驱 CHANGED 必须落候选");
        }

        @Test
        @DisplayName("预算池恰发：两漂移点上限 1 次 → 1 调用 + 1 跳过，退出码 2")
        void reDrive_budget() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            saveSkeletonRecord("c-1", "session-c", 3000L, "写诗", "other", "skl-2", "hash-o2", "{\"result\":\"ok\"}");
            saveSkeletonRecord("c-2", "session-c2", 3100L, "写诗", "other", "skl-2", "hash-o2", "{\"result\":\"ok\"}");
            establishedProfile("invocation:other:skl-2", "other", "hash-old");
            repository.saveTemplateText("hash-o2", "另一模板全文");

            assertEquals(2, runner.run(null, null, false, false, true, false, 1, null));
            assertEquals(1, stubClient.calls);
            assertTrue(output.toString().contains("budget exhausted"));
        }

        @Test
        @DisplayName("重驱全败（无任何比对结果）→ 退出码 2 而非误报回归")
        void reDrive_allFailed() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");
            stubClient.failWith(new io.github.agentassert4j.spi.LlmApiException("simulated outage"));

            assertEquals(2, runner.run(null, null, false, false, true, false, null, null));
            assertTrue(output.toString().contains("All re-drive calls failed"));
        }

        @Test
        @DisplayName("归档模板原文缺席 → 跳过计数可见，退出码 2")
        void reDrive_missingTemplateText() {
            saveSkeletonRecord("a-1", "session-a", 1000L, "查订单", "order", "skl-1", "hash-old", "{\"result\":\"ok\"}");
            saveSkeletonRecord("b-1", "session-b", 2000L, "查订单", "order", "skl-1", "hash-new", "{\"result\":\"ok\"}");
            establishedProfile("invocation:order:skl-1", "order", "hash-old");

            assertEquals(2, runner.run(null, null, false, false, true, false, null, null));
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
            repository.saveTemplateText("hash-p", "普通模板全文");

            runner.run(null, null, false, false, true, true, null, null);
            assertTrue(stubClient.calls >= 2, "扩域后非漂移点也应重驱: " + stubClient.calls);
        }

        @Test
        @DisplayName("缩域即重驱域：--re-drive --task 无漂移也重驱缩域内调用点")
        void reDrive_narrowedScope_drivesWithoutDrift() {
            saveIdenticalLabeledChains();
            repository.saveTemplateText("hash-a", "归档模板全文");
            stubClient.setScriptedContent("{\"v\":1}");

            assertEquals(0, runner.run("查订单", null, false, false, true, false, null, null));
            assertEquals(1, stubClient.calls, "缩域内调用点应被重驱");
            assertTrue(output.toString().contains("(all invocations in scope)"), output.toString());
        }

        @Test
        @DisplayName("bare 重驱无漂移仍为零目标（漂移点裁剪不因缩域缺省生效）")
        void reDrive_bare_zeroDrift_zeroTargets() {
            saveIdenticalLabeledChains();

            assertEquals(0, runner.run(null, null, false, false, true, false, null, null));
            assertEquals(0, stubClient.calls);
            assertTrue(output.toString().contains("(drift points only)"), output.toString());
        }

        @Test
        @DisplayName("dry-run 重驱：只出成本预估，零调用")
        void reDrive_dryRun_estimateOnly() {
            seedArchivedSkeletonDrift("{\"result\":\"ok\"}");

            assertEquals(0, runner.run(null, null, false, true, true, false, null, null));
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

            assertEquals(0, jsonRunner.run(null, null, false, false, false, false, null, null));

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

            assertEquals(0, jsonRunner.run(null, null, false, false, false, false, null, null));

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

    /**
     * 可编程桩：缺省路径（检测/对齐/处置）永不触发 chat；受控重驱按脚本应答，
     * 调用计数供预算与目标集断言。
     */
    static class StubLlmClient implements LlmClient {
        private String scriptedContent = "{\"result\":\"ok\"}";
        private io.github.agentassert4j.spi.LlmApiException failure;
        int calls;

        void setScriptedContent(String content) {
            this.scriptedContent = content;
        }

        void failWith(io.github.agentassert4j.spi.LlmApiException failure) {
            this.failure = failure;
        }

        @Override
        public io.github.agentassert4j.model.LlmResponse chat(io.github.agentassert4j.model.LlmRequest request, long timeoutMs) throws io.github.agentassert4j.spi.LlmTimeoutException, io.github.agentassert4j.spi.LlmApiException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            io.github.agentassert4j.model.LlmResponse response = new io.github.agentassert4j.model.LlmResponse();
            response.setContent(scriptedContent);
            response.setInputTokens(10);
            response.setOutputTokens(5);
            return response;
        }

        @Override
        public String name() {
            return "stub-model";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
