package io.github.agentassert4j.cli;

import io.github.agentassert4j.config.InvocationRulesConfig;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * --invocation 目标解析的契约测试 — 业务标签、invocationKey、唯一前缀三种写法在
 * 选例类命令与画像操作类命令上的统一语义。
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
class CliSupportResolverTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("resolver.db").toString());
        repository.initialize();
        output = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private void saveRecord(String recordId, String invocationId, String templateHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-" + invocationId);
        r.setTimestamp(1000L);
        r.setSeq(1L);
        r.setInvocationId(invocationId);
        r.setTemplateHash(templateHash);
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("答");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteractionIfAbsent(r);
    }

    private void establishAll() {
        new BaselineService(repository).establishMissing(new PrintStream(output), "tester", null, false, null, null, null, null);
    }

    @Test
    @DisplayName("完整 invocationKey 精确命中优先于前缀匹配")
    void exactInvocationKey_shortCircuitsStrictPrefixSibling() {
        // invocation:sk:abc 是 invocation:sk:abcdef 的严格前缀——精确命中不得被误判为歧义前缀
        saveRecord("r1", "sk", "abc");
        saveRecord("r2", "sk", "abcdef");
        establishAll();

        assertEquals("invocation:sk:abc", CliSupport.resolveInvocationKeyTarget(repository, "invocation:sk:abc"));
        assertEquals("invocation:sk:abcdef", CliSupport.resolveInvocationKeyTarget(repository, "invocation:sk:abcdef"));
    }

    @Test
    @DisplayName("唯一前缀解析到完整 invocationKey")
    void uniquePrefix_resolvesToFullKey() {
        saveRecord("r1", "sk1", "abcdef");
        establishAll();

        String fullKey = "invocation:sk1:abcdef";
        assertEquals(fullKey, CliSupport.resolveInvocationKeyTarget(repository, fullKey.substring(0, 8)));
    }

    @Test
    @DisplayName("业务标签解析到其唯一分组键")
    void businessLabel_resolvesToInvocationKey() {
        saveRecord("r1", "queryOrder", "hash-a");
        establishAll();

        assertEquals("invocation:queryOrder:hash-a", CliSupport.resolveInvocationKeyTarget(repository, "queryOrder"));
    }

    @Test
    @DisplayName("业务标签覆盖多个分组时报错并列出全部分组（E-USAGE）")
    void businessLabel_multiGroup_errors() {
        saveRecord("r1", "queryOrder", "hash-a");
        saveRecord("r2", "queryOrder", "hash-b");

        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "queryOrder"));
        assertEquals(CliErrorCode.E_USAGE, e.errorCode);
        assertTrue(e.getMessage().contains("covers multiple invocations"));
        assertTrue(e.getMessage().contains("invocation:queryOrder:hash-a") && e.getMessage().contains("invocation:queryOrder:hash-b"));
    }

    @Test
    @DisplayName("无命中时报错并指引两种合法写法（E-NO-DATA）")
    void noMatch_errors() {
        saveRecord("r1", "sk1", "hash-a");
        establishAll();

        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "no-such"));
        assertEquals(CliErrorCode.E_NO_DATA, e.errorCode);
        assertTrue(e.getMessage().contains("No invocation matching"));
    }

    @Test
    @DisplayName("选例过滤器：唯一前缀解析到键并提示")
    void businessFilter_prefixMapsToKey() {
        saveRecord("r1", "queryOrder", "hash-a");
        establishAll();

        List<String> resolved = CliSupport.resolveInvocationKeys(repository, "invocation:queryOrder", true, new PrintStream(output));

        assertEquals(Collections.singletonList("invocation:queryOrder:hash-a"), resolved);
        assertTrue(output.toString().contains("matched invocationKey prefix"));
    }

    @Test
    @DisplayName("选例过滤器：业务标签解析到其全部键（扇出）")
    void businessFilter_labelFansOutAllKeys() {
        saveRecord("r1", "queryOrder", "hash-a");
        saveRecord("r2", "queryOrder", "hash-b");

        List<String> resolved = CliSupport.resolveInvocationKeys(repository, "queryOrder", true, new PrintStream(output));

        assertEquals(Arrays.asList("invocation:queryOrder:hash-a", "invocation:queryOrder:hash-b"), resolved);
    }

    @Test
    @DisplayName("阶梯等价断言：同输入两族解析出同一键集合（多键策略是唯一差异）")
    void ladder_singularPlural_sameKeySet() {
        saveRecord("r1", "queryOrder", "hash-a");
        saveRecord("r2", "hexCase", "abcdef12");

        assertEquals(
                CliSupport.resolveInvocationKeys(repository, "invocation:queryOrder:hash-a", true, null),
                CliSupport.resolveInvocationKeys(repository, "invocation:queryOrder:hash-a", false, null));
        assertEquals(
                CliSupport.resolveInvocationKeys(repository, "queryOrder", true, null),
                CliSupport.resolveInvocationKeys(repository, "queryOrder", false, null));
        assertEquals(
                CliSupport.resolveInvocationKeys(repository, "hexCase@abcdef12", true, null),
                CliSupport.resolveInvocationKeys(repository, "hexCase@abcdef12", false, null));
    }

    @Test
    @DisplayName("阶梯键空间断言：未建档已录键可解析（显示短形/唯一前缀/目标族）")
    void ladder_resolvesUnestablishedRecordedKeys() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");
        // 不 establishAll——键已录而无画像

        assertEquals(Collections.singletonList("invocation:queryOrder:abcdef1234567890"),
                CliSupport.resolveInvocationKeys(repository, "queryOrder@abcdef12", true, null));
        assertEquals(Collections.singletonList("invocation:queryOrder:abcdef1234567890"),
                CliSupport.resolveInvocationKeys(repository, "invocation:queryOrder:abcdef12", false, null));
        assertEquals("invocation:queryOrder:abcdef1234567890",
                CliSupport.resolveInvocationKeyTarget(repository, "queryOrder@abcdef12"));
    }

    @Test
    @DisplayName("显示短形直接可选（目标解析）：标签@8位反解到完整键")
    void displayForm_resolvesToFullKey() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");
        establishAll();

        assertEquals("invocation:queryOrder:abcdef1234567890", CliSupport.resolveInvocationKeyTarget(repository, "queryOrder@abcdef12"));
    }

    @Test
    @DisplayName("显示短形哈希段大小写不敏感")
    void displayForm_hashCaseInsensitive() {
        saveRecord("r1", "queryOrder", "ABCDEF1234567890");
        establishAll();

        String resolved = CliSupport.resolveInvocationKeyTarget(repository, "queryOrder@abcdef12");
        assertTrue(resolved.equalsIgnoreCase("invocation:queryOrder:ABCDEF1234567890"), resolved);
    }

    @Test
    @DisplayName("显示短形撞车（前 8 位相同）报错并列出完整键（E-USAGE）")
    void displayForm_collision_errors() {
        saveRecord("r1", "queryOrder", "abcdef1200000001");
        saveRecord("r2", "queryOrder", "abcdef1200000002");
        establishAll();

        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "queryOrder@abcdef12"));
        assertEquals(CliErrorCode.E_USAGE, e.errorCode);
        assertTrue(e.getMessage().contains("hash collision"));
        assertTrue(e.getMessage().contains("invocation:queryOrder:abcdef1200000001") && e.getMessage().contains("invocation:queryOrder:abcdef1200000002"));
    }

    @Test
    @DisplayName("末段非 8 位十六进制不视为显示短形，走原解析路径（E-NO-DATA）")
    void displayForm_nonHexSuffix_fallsThrough() {
        saveRecord("r1", "sk1", "abcdef1234567890");
        establishAll();

        // 「@toolong」不是 8 位 → 不按显示短形处理，走前缀/标签路径后无命中报错
        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "sk1@toolong"));
        assertEquals(CliErrorCode.E_NO_DATA, e.errorCode);
        assertTrue(e.getMessage().contains("No invocation matching"));
    }

    @Test
    @DisplayName("骨架/模板短形（skl@/tpl@）同样可选")
    void displayForm_skeletonAndTemplateForms() {
        // 阶梯键空间=已录键全集：零声明骨架键以记录形态入场（画像皆由记录建档）
        saveKeyedRecord("r1", "skeleton:0123456789abcdef");

        assertEquals("skeleton:0123456789abcdef", CliSupport.resolveInvocationKeyTarget(repository, "skl@01234567"));
    }

    /**
     * 零声明形态的记录（存储键直写，invocationId 落空串）。
     */
    private void saveKeyedRecord(String recordId, String invocationKey) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-skl");
        r.setTimestamp(1000L);
        r.setSeq(1L);
        r.setInvocationKey(invocationKey);
        r.setTemplateHash("tpl-hash");
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("答");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteractionIfAbsent(r);
    }

    @Test
    @DisplayName("选例过滤器：显示短形直返键并提示（不做键→标签往返）")
    void businessFilter_displayFormMapsToKey() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");
        establishAll();

        List<String> resolved = CliSupport.resolveInvocationKeys(repository, "queryOrder@abcdef12", true, new PrintStream(output));

        assertEquals(Collections.singletonList("invocation:queryOrder:abcdef1234567890"), resolved);
        assertTrue(output.toString().contains("display form"), output.toString());
    }

    @Test
    @DisplayName("选例过滤器：显示短形未命中 → E-NO-DATA 明确报错（静默裸返回已消灭）")
    void businessFilter_displayFormMiss_loudZeroHit() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");

        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.resolveInvocationKeys(repository, "queryOrder@zzzzzz", true, new PrintStream(output)));
        assertEquals(CliErrorCode.E_NO_DATA, e.errorCode);
        assertTrue(e.getMessage().contains("No invocation matching"));
    }

    @Test
    @DisplayName("未知 behavior 名在规则加载时告警并列出合法名")
    void unknownBehavior_warnedAtLoad() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"svc\":{\"behaviors\":[\"noSuchBehavior\"]}}}");

        CliSupport.warnUnknownBehaviors(rules, new PrintStream(output));

        String warning = output.toString();
        assertTrue(warning.contains("unknown behaviors: noSuchBehavior"), "笔误的 behavior 必须点破而非静默忽略: " + warning);
        assertTrue(warning.contains("mustUseChinese"), "告警必须列出合法行为名: " + warning);
    }

    @Test
    @DisplayName("合法 behavior 名不产生告警")
    void knownBehavior_noWarning() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"svc\":{\"behaviors\":[\"mustUseChinese\"]}}}");

        CliSupport.warnUnknownBehaviors(rules, new PrintStream(output));

        assertEquals("", output.toString());
    }
}
