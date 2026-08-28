package io.github.agentassert4j.cli;

import io.github.agentassert4j.config.SkillRulesConfig;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * --skill 目标解析的契约测试 — 业务标签、groupKey、唯一前缀三种写法在
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

    private void saveRecord(String recordId, String skillId, String templateHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-" + skillId);
        r.setTimestamp(1000L);
        r.setSeq(1L);
        r.setSkillId(skillId);
        r.setTemplateHash(templateHash);
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("答");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteraction(r);
    }

    private void establishAll() {
        new BaselineService(repository).establishMissing(new PrintStream(output), "tester", false, null);
    }

    @Test
    @DisplayName("完整 groupKey 精确命中优先于前缀匹配")
    void exactGroupKey_shortCircuitsStrictPrefixSibling() {
        // chat:abc 是 chat:abcdef 的严格前缀——精确命中不得被误判为歧义前缀
        saveRecord("r1", "sk1", "abc");
        saveRecord("r2", "sk2", "abcdef");
        establishAll();

        assertEquals("chat:abc", CliSupport.resolveGroupKeyTarget(repository, "chat:abc"));
        assertEquals("chat:abcdef", CliSupport.resolveGroupKeyTarget(repository, "chat:abcdef"));
    }

    @Test
    @DisplayName("唯一前缀解析到完整 groupKey")
    void uniquePrefix_resolvesToFullKey() {
        saveRecord("r1", "sk1", "abcdef");
        establishAll();

        String fullKey = "chat:abcdef";
        assertEquals(fullKey, CliSupport.resolveGroupKeyTarget(repository, fullKey.substring(0, 8)));
    }

    @Test
    @DisplayName("业务标签解析到其唯一分组键")
    void businessLabel_resolvesToGroupKey() {
        saveRecord("r1", "queryOrder", "hash-a");
        establishAll();

        assertEquals("chat:hash-a", CliSupport.resolveGroupKeyTarget(repository, "queryOrder"));
    }

    @Test
    @DisplayName("业务标签覆盖多个分组时报错并列出全部分组")
    void businessLabel_multiGroup_errors() {
        saveRecord("r1", "queryOrder", "hash-a");
        saveRecord("r2", "queryOrder", "hash-b");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveGroupKeyTarget(repository, "queryOrder"));
        assertTrue(e.getMessage().contains("覆盖多个分组"));
        assertTrue(e.getMessage().contains("chat:hash-a") && e.getMessage().contains("chat:hash-b"));
    }

    @Test
    @DisplayName("无命中时报错并指引两种合法写法")
    void noMatch_errors() {
        saveRecord("r1", "sk1", "hash-a");
        establishAll();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CliSupport.resolveGroupKeyTarget(repository, "no-such"));
        assertTrue(e.getMessage().contains("没有匹配"));
    }

    @Test
    @DisplayName("选例过滤器：唯一前缀换算回业务标签")
    void businessFilter_prefixMapsToLabel() {
        saveRecord("r1", "queryOrder", "hash-a");
        establishAll();

        String resolved = CliSupport.resolveBusinessSkillFilter(repository, "chat:hash-a", new PrintStream(output));

        assertEquals("queryOrder", resolved);
        assertTrue(output.toString().contains("业务标签 queryOrder"));
    }

    @Test
    @DisplayName("选例过滤器：业务标签精确命中按原义使用")
    void businessFilter_exactLabelPassthrough() {
        saveRecord("r1", "queryOrder", "hash-a");

        assertEquals("queryOrder", CliSupport.resolveBusinessSkillFilter(repository, "queryOrder", new PrintStream(output)));
    }

    @Test
    @DisplayName("未知 behavior 名在规则加载时告警并列出合法名")
    void unknownBehavior_warnedAtLoad() {
        SkillRulesConfig rules = SkillRulesConfig.fromJson("{\"skills\":{\"svc\":{\"behaviors\":[\"noSuchBehavior\"]}}}");

        CliSupport.warnUnknownBehaviors(rules, new PrintStream(output));

        String warning = output.toString();
        assertTrue(warning.contains("未知行为 noSuchBehavior"), "笔误的 behavior 必须点破而非静默忽略: " + warning);
        assertTrue(warning.contains("mustUseChinese"), "告警必须列出合法行为名: " + warning);
    }

    @Test
    @DisplayName("合法 behavior 名不产生告警")
    void knownBehavior_noWarning() {
        SkillRulesConfig rules = SkillRulesConfig.fromJson("{\"skills\":{\"svc\":{\"behaviors\":[\"mustUseChinese\"]}}}");

        CliSupport.warnUnknownBehaviors(rules, new PrintStream(output));

        assertEquals("", output.toString());
    }
}
