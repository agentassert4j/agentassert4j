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
    @DisplayName("种子响应缺少必需关键词：建档完成但打印违规告警")
    void seedMissingRequiredKeyword_establishSucceedsWithWarning() {
        repository.saveInteraction(makeRecord("rec-1", "skill-1", 1000L, "已为您查询订单状态。"));
        repository.saveInteraction(makeRecord("rec-2", "skill-1", 2000L, "已为您查询订单状态。"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        PrintStream out = new PrintStream(output, true);

        int established = new BaselineService(repository).establishMissing(out, "tester", false, null, rules);

        assertEquals(1, established, "规则违例不阻断建档");
        String report = output.toString();
        assertTrue(report.contains("种子记录不满足声明规则"), "必须输出种子违例告警: " + report);
        assertTrue(report.contains("缺少必需关键词「订单号」"), "告警列出违例关键词: " + report);
        InvocationProfile profile = repository.findInvocationByKey(invocationKeyOf("skill-1"));
        assertNotNull(profile, "基线已建立");
        assertNotNull(profile.getFingerprint(), "基线指纹已落库");
    }

    @Test
    @DisplayName("种子响应满足全部声明：不输出告警")
    void seedSatisfiesRules_noWarning() {
        repository.saveInteraction(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001 已出库"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]}}}");
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", false, null, rules);

        assertFalse(output.toString().contains("种子记录不满足声明规则"), "合规种子不得误报: " + output);
    }

    @Test
    @DisplayName("禁用关键词与正则同样参与种子断言")
    void seedForbiddenAndRegex_checked() {
        repository.saveInteraction(makeRecord("rec-1", "skill-1", 1000L, "订单号 ORD-001，抱歉给您带来困扰"));
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单号\"]," + "\"forbiddenKeywords\":[\"抱歉\"]," + "\"regexPatterns\":[{\"pattern\":\"状态[:：]\\\\w+\",\"description\":\"状态行\"}]}}}");
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", false, null, rules);

        String report = output.toString();
        assertTrue(report.contains("出现禁用关键词「抱歉」"), "禁用关键词违例可见: " + report);
        assertTrue(report.contains("正则不命中"), "正则违例可见: " + report);
    }

    @Test
    @DisplayName("建档种子取桶内规范序最早记录，与录制插入顺序无关")
    void seedIsCanonicalEarliest_regardlessOfInsertionOrder() {
        InteractionRecord late = makeRecord("rec-late", "skill-1", 2000L, "{\"late\":true}");
        InteractionRecord early = makeRecord("rec-early", "skill-1", 1000L, "{\"early\":true}");
        repository.saveInteraction(late);
        repository.saveInteraction(early);
        PrintStream out = new PrintStream(output, true);

        new BaselineService(repository).establishMissing(out, "tester", false, null, null);

        InvocationProfile profile = repository.findInvocationByKey(invocationKeyOf("skill-1"));
        assertNotNull(profile, "基线已建立");
        assertEquals(FingerprintExtractor.extract(early), profile.getFingerprint(), "种子指纹必须来自规范序最早记录");
        assertNotEquals(FingerprintExtractor.extract(late), profile.getFingerprint(), "晚于种子的记录不得成为基线");
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
