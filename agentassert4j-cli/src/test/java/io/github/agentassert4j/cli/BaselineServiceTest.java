package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.algorithm.InvocationResolver;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 建档服务种子规则断言测试——种子响应不满足声明规则时建档照常完成但输出告警。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class BaselineServiceTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("seed.db").toString());
        repository.initialize();
        output = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("键集合缩域：仅解析出的键被建档（桶选择与解析核心一一对应）")
    void establishMissing_keySetScoping() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "{\"ok\":true}"));
        repository.saveInteractionIfAbsent(makeRecord("rec-2", "skill-2", 1000L, "{\"ok\":true}"));
        PrintStream out = new PrintStream(output, true);

        String key1 = invocationKeyOf("skill-1");
        int established = new BaselineService(repository).establishMissing(out, "tester", null, false, new LinkedHashSet<>(Collections.singletonList(key1)), null, null, null);

        assertEquals(1, established, "仅缩域键建档");
        assertNotNull(repository.findInvocationByKey(key1));
        assertNull(repository.findInvocationByKey(invocationKeyOf("skill-2")), "缩域外键不得建档");
    }

    @Test
    @DisplayName("人读行回显已落库的申报锚：建档行与 exists 行都带 (ref X)")
    void humanLinesEchoStoredCodeRef() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "{\"ok\":true}"));
        PrintStream out = new PrintStream(output, true);
        BaselineService service = new BaselineService(repository);

        service.establishMissing(out, "tester", "abc1234", false, null, null, null, null);
        assertTrue(output.toString().contains(": baseline established (seed record rec-1) (ref abc1234)"), output.toString());

        output.reset();
        service.establishMissing(out, "tester", "def5678", false, null, null, null, null);
        String rerun = output.toString();
        assertTrue(rerun.contains(": baseline exists (v1) (ref abc1234)"), "exists 行回显已落库的锚而非本次声明: " + rerun);
    }

    @Test
    @DisplayName("种子响应缺少必需关键词：建档完成但打印违规告警")
    void seedMissingRequiredKeyword_establishSucceedsWithWarning() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "已为您查询订单状态。"));
        repository.saveInteractionIfAbsent(makeRecord("rec-2", "skill-1", 2000L, "已为您查询订单状态。"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        PrintStream out = new PrintStream(output, true);

        int established = new BaselineService(repository).establishMissing(out, "tester", null, false, null, rules, null, null);

        assertEquals(1, established, "规则违例不阻断建档");
        String report = output.toString();
        assertTrue(report.contains("violates the declared rules"), "必须输出种子违例告警: " + report);
        assertTrue(report.contains("missing required keyword '订单号'"), "告警列出违例关键词: " + report);
        InvocationProfile profile = repository.findInvocationByKey(invocationKeyOf("skill-1"));
        assertNotNull(profile, "基线已建立");
        assertFalse(profile.getFingerprints().isEmpty(), "基线形态集合已落库");
    }

    @Test
    @DisplayName("种子响应满足全部声明：不输出告警")
    void seedSatisfiesRules_noWarning() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001 已出库"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", null, false, null, rules, null, null);

        assertFalse(output.toString().contains("violates the declared rules"), "合规种子不得误报: " + output);
    }

    @Test
    @DisplayName("禁用关键词与正则同样参与种子断言")
    void seedForbiddenAndRegex_checked() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001，抱歉给您带来困扰"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]," + "\"forbiddenKeywords\":[\"抱歉\"]," + "\"regexPatterns\":[{\"pattern\":\"状态[:：]\\\\w+\",\"description\":\"状态行\"}]}}}");
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", null, false, null, rules, null, null);

        String report = output.toString();
        assertTrue(report.contains("forbidden keyword '抱歉' present"), "禁用关键词违例可见: " + report);
        assertTrue(report.contains("not matched"), "正则违例可见: " + report);
    }

    @Test
    @DisplayName("建档种子取桶内规范序最新记录（认可时刻定标当前行为），与录制插入顺序无关")
    void seedIsLatest_regardlessOfInsertionOrder() {
        InteractionRecord late = makeRecord("rec-late", "skill-1", 2000L, "{\"late\":true}");
        InteractionRecord early = makeRecord("rec-early", "skill-1", 1000L, "{\"early\":true}");
        repository.saveInteractionIfAbsent(late);
        repository.saveInteractionIfAbsent(early);
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", null, false, null, null, null, null);

        InvocationProfile profile = repository.findInvocationByKey(invocationKeyOf("skill-1"));
        assertNotNull(profile, "基线已建立");
        assertEquals(FingerprintExtractor.extract(late, null, null), profile.getFingerprints().get(0), "种子指纹必须来自规范序最新记录——提示词工程 N 版迭代，用户只在认可当前行为时定标");
        assertNotEquals(FingerprintExtractor.extract(early, null, null), profile.getFingerprints().get(0), "更早的迭代草稿不参与播种");
    }

    @Test
    @DisplayName("种子记录在建档与 force 重建行上就地披露：用户能当场看到批准的是哪条记录")
    void seedRecordId_disclosedOnCreateAndForce() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "{\"ok\":true}"));
        repository.saveInteractionIfAbsent(makeRecord("rec-2", "skill-1", 2000L, "{\"ok\":true}"));
        PrintStream out = new PrintStream(output, true);
        BaselineService service = new BaselineService(repository);

        service.establishMissing(out, "tester", null, false, null, null, null, null);
        assertTrue(output.toString().contains("(seed record rec-2)"), "建档行披露种子 recordId（桶内最新）: " + output);

        output.reset();
        service.establishMissing(out, "tester", null, true, null, null, null, null);
        String force = output.toString();
        assertTrue(force.contains("re-established under the current judgment semantics (v2) (seed record rec-2)"), "force 重建行同样披露种子（规范序最新）: " + force);
    }

    @Test
    @DisplayName("既有基线遇不同规则声明：exists 行就地告警并指路两条刷新路径")
    void rulesDrift_warnedOnExistingBaseline() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001 已出库"));
        PrintStream out = new PrintStream(output, true);
        BaselineService service = new BaselineService(repository);
        InvocationRulesConfig pinned = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        service.establishMissing(out, "tester", null, false, null, pinned, null, null);

        output.reset();
        InvocationRulesConfig changed = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"出库\"]}}}");
        service.establishMissing(out, "tester", null, false, null, changed, null, null);
        String report = output.toString();
        assertTrue(report.contains(": baseline exists (v1)"), "既有基线幂等不重建: " + report);
        assertTrue(report.contains("rules declarations for skill-1 differ"), "声明差异必须告警: " + report);
        assertTrue(report.contains("pinned required=[订单号]"), "告警披露基线侧钉定声明: " + report);
        assertTrue(report.contains("file required=[出库]"), "告警披露文件侧新声明: " + report);
        assertTrue(report.contains("accept the candidate it lands"), "指路无重播种的 check→accept 路径: " + report);
        assertTrue(report.contains("--force"), "指路 --force 并带重播种提示: " + report);
    }

    @Test
    @DisplayName("声明一致或无规则文件：exists 行静默（删除规则文件不是漂移）")
    void rulesDrift_silentWhenSameOrNoRules() {
        repository.saveInteractionIfAbsent(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001 已出库"));
        PrintStream out = new PrintStream(output, true);
        BaselineService service = new BaselineService(repository);
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        service.establishMissing(out, "tester", null, false, null, rules, null, null);

        output.reset();
        service.establishMissing(out, "tester", null, false, null, rules, null, null);
        assertFalse(output.toString().contains("rules declarations"), "同声明不得告警: " + output);

        output.reset();
        service.establishMissing(out, "tester", null, false, null, null, null, null);
        assertFalse(output.toString().contains("rules declarations"), "无规则文件（合法删除态）不得告警: " + output);
    }

    @Test
    @DisplayName("扫建路径裂键豁免：同标签兄弟已建档时草稿新键只披露、不并入基线；定向 --invocation 照建")
    void sweepSkipsSplitKeys_targetedEstablishes() {
        PrintStream out = new PrintStream(output, true);
        // 原键（模板 hash-old）先行建档；同标签换模板（hash-new）落成草稿新键
        repository.saveInteractionIfAbsent(makeRecord("rec-orig", "splitAgent", 1000L, "{\"v\":1}"));
        new BaselineService(repository).establishMissing(out, "tester", null, false, null, null, null, null);
        output.reset();

        InteractionRecord draft = makeRecord("rec-draft", "splitAgent", 2000L, "{\"v\":1}");
        draft.setTemplateHash("hash-new");
        repository.saveInteractionIfAbsent(draft);

        // 扫建：草稿新键只披露、不并入基线
        int established = new BaselineService(repository).establishMissing(out, "tester", null, false, null, null, null, null);
        assertEquals(0, established, "裂键不进扫建");
        assertTrue(output.toString().contains("Split key "), "裂键披露在场: " + output);
        assertTrue(output.toString().contains("left for explicit establish"), "指路规则与 replay 同源: " + output);
        assertNull(repository.findInvocationByKey(invocationKeyOfDraft()), "扫建不产出新键画像");

        // 定向 --invocation：逐键显式意图，照建
        output.reset();
        Set<String> targeted = Collections.singleton(
                InvocationResolver.resolve(repository.findByInvocationId("splitAgent").get(1)).getInvocationKey());
        int targetedEstablished = new BaselineService(repository).establishMissing(out, "tester", null, false, targeted, null, null, null);
        assertEquals(1, targetedEstablished, "定向显式建档不受豁免影响");
        assertNotNull(repository.findInvocationByKey(targeted.iterator().next()));
    }

    private String invocationKeyOfDraft() {
        for (InteractionRecord record : repository.findByInvocationId("splitAgent")) {
            if ("hash-new".equals(record.getTemplateHash())) {
                return InvocationResolver.resolve(record).getInvocationKey();
            }
        }
        return null;
    }

    private InteractionRecord makeRecord(String recordId, String invocationId, long timestamp, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-1");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setInvocationId(invocationId);
        r.setTemplateHash("hash-old");
        r.setUserInput("查订单 ORD-001");
        r.setTurnIndex(0);
        r.setModelResponse(response);
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        return r;
    }

    private String invocationKeyOf(String invocationId) {
        return InvocationResolver.resolve(repository.findByInvocationId(invocationId).get(0)).getInvocationKey();
    }
}
