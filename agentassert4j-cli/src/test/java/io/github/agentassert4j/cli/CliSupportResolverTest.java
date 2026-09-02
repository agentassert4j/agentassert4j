package io.github.agentassert4j.cli;

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
        repository.saveInteraction(r);
    }

    private void establishAll() {
        new BaselineService(repository).establishMissing(new PrintStream(output), "tester", false, null, null);
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
    @DisplayName("业务标签覆盖多个分组时报错并列出全部分组")
    void businessLabel_multiGroup_errors() {
        saveRecord("r1", "queryOrder", "hash-a");
        saveRecord("r2", "queryOrder", "hash-b");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "queryOrder"));
        assertTrue(e.getMessage().contains("覆盖多个调用点"));
        assertTrue(e.getMessage().contains("invocation:queryOrder:hash-a") && e.getMessage().contains("invocation:queryOrder:hash-b"));
    }

    @Test
    @DisplayName("无命中时报错并指引两种合法写法")
    void noMatch_errors() {
        saveRecord("r1", "sk1", "hash-a");
        establishAll();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "no-such"));
        assertTrue(e.getMessage().contains("没有匹配"));
    }

    @Test
    @DisplayName("选例过滤器：唯一前缀换算回业务标签")
    void businessFilter_prefixMapsToLabel() {
        saveRecord("r1", "queryOrder", "hash-a");
        establishAll();

        String resolved = CliSupport.resolveInvocationFilter(repository, "invocation:queryOrder", new PrintStream(output));

        assertEquals("queryOrder", resolved);
        assertTrue(output.toString().contains("业务标签 queryOrder"));
    }

    @Test
    @DisplayName("选例过滤器：业务标签精确命中按原义使用")
    void businessFilter_exactLabelPassthrough() {
        saveRecord("r1", "queryOrder", "hash-a");

        assertEquals("queryOrder", CliSupport.resolveInvocationFilter(repository, "queryOrder", new PrintStream(output)));
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
    @DisplayName("显示短形撞车（前 8 位相同）报错并列出完整键")
    void displayForm_collision_errors() {
        saveRecord("r1", "queryOrder", "abcdef1200000001");
        saveRecord("r2", "queryOrder", "abcdef1200000002");
        establishAll();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "queryOrder@abcdef12"));
        assertTrue(e.getMessage().contains("撞车"));
        assertTrue(e.getMessage().contains("invocation:queryOrder:abcdef1200000001") && e.getMessage().contains("invocation:queryOrder:abcdef1200000002"));
    }

    @Test
    @DisplayName("末段非 8 位十六进制不视为显示短形，走原解析路径")
    void displayForm_nonHexSuffix_fallsThrough() {
        saveRecord("r1", "sk1", "abcdef1234567890");
        establishAll();

        // 「@toolong」不是 8 位 → 不按显示短形处理，走前缀/标签路径后无命中报错
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveInvocationKeyTarget(repository, "sk1@toolong"));
        assertTrue(e.getMessage().contains("没有匹配"));
    }

    @Test
    @DisplayName("骨架/模板短形（skl@/tpl@）同样可选")
    void displayForm_skeletonAndTemplateForms() {
        // 零声明键不会经 baseline 入画像（按业务标签桶遍历），直落画像验证短形匹配契约本身
        InvocationProfile profile = new InvocationProfile();
        profile.setInvocationKey("skeleton:0123456789abcdef");
        profile.setInvocationName("skeleton:0123456789abcdef");
        repository.saveInvocationProfile(profile);

        assertEquals("skeleton:0123456789abcdef", CliSupport.resolveInvocationKeyTarget(repository, "skl@01234567"));
    }

    @Test
    @DisplayName("选例过滤器：显示短形换算回业务标签并提示")
    void businessFilter_displayFormMapsToLabel() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");
        establishAll();

        String resolved = CliSupport.resolveInvocationFilter(repository, "queryOrder@abcdef12", new PrintStream(output));

        assertEquals("queryOrder", resolved);
        assertTrue(output.toString().contains("显示短形"), output.toString());
        assertTrue(output.toString().contains("业务标签 queryOrder"));
    }

    @Test
    @DisplayName("选例过滤器：显示短形未命中画像时按原样返回由调用方兜底")
    void businessFilter_displayFormMissPassthrough() {
        saveRecord("r1", "queryOrder", "abcdef1234567890");

        assertEquals("queryOrder@zzzzzz", CliSupport.resolveInvocationFilter(repository, "queryOrder@zzzzzz", new PrintStream(output)));
    }

    @Test
    @DisplayName("未知 behavior 名在规则加载时告警并列出合法名")
    void unknownBehavior_warnedAtLoad() {
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"svc\":{\"behaviors\":[\"noSuchBehavior\"]}}}");

        CliSupport.warnUnknownBehaviors(rules, new PrintStream(output));

        String warning = output.toString();
        assertTrue(warning.contains("未知行为 noSuchBehavior"), "笔误的 behavior 必须点破而非静默忽略: " + warning);
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
