package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.util.HashUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicSkillGrouperTest {

    private InteractionRecord record(String promptHash, List<ToolCall> calls, boolean hasToolCalls) {
        InteractionRecord r = new InteractionRecord();
        r.setTemplateHash(promptHash);
        r.setToolCalls(calls);
        r.setHasToolCalls(hasToolCalls);
        return r;
    }

    private ToolCall tc(String name, Map<String, String> argTypes) {
        ToolCall tc = new ToolCall();
        tc.setToolName(name);
        tc.setArgTypes(argTypes);
        return tc;
    }

    @Test
    void singleTool_groupKey() {
        InteractionRecord r = record("abc123",
                List.of(tc("queryOrderDB", Map.of("orderId", "String"))), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("queryOrderDB[orderid:string]", p.getGroupKey());
        assertEquals(SkillType.TOOL_SKILL, p.getSkillType());
        assertEquals("queryOrderDB", p.getSkillName());
        assertEquals(HashUtil.sha256(p.getGroupKey()), p.getSkillId());
        assertEquals("orderid:string", p.getParamSignature());
    }

    @Test
    void singleTool_noArgTypes_emptySignature() {
        InteractionRecord r = record("abc123",
                List.of(tc("queryOrderDB", null)), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("queryOrderDB[]", p.getGroupKey());
        assertEquals("", p.getParamSignature());
    }

    @Test
    void multiTool_sortedNames() {
        InteractionRecord r = record("abc123",
                List.of(tc("queryOrderDB", Map.of("orderId", "String")),
                        tc("checkInventory", Map.of("skuId", "String"))), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        // sorted: checkInventory < queryOrderDB
        assertEquals("checkInventory+queryOrderDB[orderid:string,skuid:string]", p.getGroupKey());
        assertEquals("checkInventory+queryOrderDB", p.getSkillName());
    }

    @Test
    void multiTool_orderInsensitive() {
        InteractionRecord r1 = record("abc",
                List.of(tc("B", Map.of("x", "int")), tc("A", Map.of("y", "string"))), true);
        InteractionRecord r2 = record("abc",
                List.of(tc("A", Map.of("y", "string")), tc("B", Map.of("x", "int"))), true);

        SkillProfile p1 = DeterministicSkillGrouper.group(r1);
        SkillProfile p2 = DeterministicSkillGrouper.group(r2);

        assertEquals(p1.getGroupKey(), p2.getGroupKey());
        assertEquals(p1.getSkillId(), p2.getSkillId());
    }

    @Test
    void paramSignature_normalizeCase() {
        // "String" vs "string" → 相同
        InteractionRecord r1 = record("abc",
                List.of(tc("tool", Map.of("id", "String"))), true);
        InteractionRecord r2 = record("abc",
                List.of(tc("tool", Map.of("id", "string"))), true);

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(),
                DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    @Test
    void paramSignature_normalizeCaseKey() {
        // "OrderId" vs "orderid" → 相同（key 也 toLowerCase）
        InteractionRecord r1 = record("abc",
                List.of(tc("tool", Map.of("OrderId", "String"))), true);
        InteractionRecord r2 = record("abc",
                List.of(tc("tool", Map.of("orderid", "string"))), true);

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(),
                DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    @Test
    void pureChat_groupKey() {
        InteractionRecord r = record("a1b2c3d4", null, false);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("chat:a1b2c3d4", p.getGroupKey());
        assertEquals(SkillType.PURE_CHAT_SKILL, p.getSkillType());
        assertTrue(p.getSkillName().startsWith("chat:"));
    }

    @Test
    void pureChat_skillId_deterministic() {
        InteractionRecord r1 = record("hash1", null, false);
        InteractionRecord r2 = record("hash1", null, false);

        assertEquals(DeterministicSkillGrouper.group(r1).getSkillId(),
                DeterministicSkillGrouper.group(r2).getSkillId());
    }

    @Test
    void differentPrompts_differentGroupKeys() {
        InteractionRecord r1 = record("hash1", null, false);
        InteractionRecord r2 = record("hash2", null, false);

        assertNotEquals(DeterministicSkillGrouper.group(r1).getGroupKey(),
                DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    // ==================== 无模板锚点回退 ====================

    @Test
    void pureChat_nullTemplateHash_fallsBackToUserInputHash_notChatNull() {
        // templateHash 为 null 时绝不能坍缩为 "chat:null"，否则不同 prompt 的纯对话互相污染同一基线
        InteractionRecord r1 = record(null, null, false);
        r1.setUserInput("查询订单状态");
        InteractionRecord r2 = record(null, null, false);
        r2.setUserInput("帮我写一首诗");

        SkillProfile p1 = DeterministicSkillGrouper.group(r1);
        SkillProfile p2 = DeterministicSkillGrouper.group(r2);

        assertNotEquals(p1.getGroupKey(), p2.getGroupKey(),
                "无模板时必须按 user_input hash 回退分组，不同输入不得坍缩为 chat:null");
        assertNotEquals(p1.getSkillId(), p2.getSkillId());
        assertFalse(p1.getGroupKey().contains("null"), "groupKey 不得出现字面 null 坍缩");
    }

    @Test
    void pureChat_nullTemplateHash_sameUserInput_sameGroupKey() {
        InteractionRecord r1 = record(null, null, false);
        r1.setUserInput("相同的问题");
        InteractionRecord r2 = record(null, null, false);
        r2.setUserInput("相同的问题");

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(),
                DeterministicSkillGrouper.group(r2).getGroupKey(),
                "同输入同无模板 → 同组（确定性不因回退而破坏）");
    }

    @Test
    void pureChat_bothAnchorsAbsent_stableOrphanKey() {
        InteractionRecord r = record(null, null, false);

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("chat:no-anchor", p.getGroupKey(), "双锚点缺失时使用稳定孤儿键，不再含字面 null");
    }

    @Test
    void pureChat_templateHashTakesPrecedenceOverUserInputFallback() {
        InteractionRecord r = record("template-hash-1", null, false);
        r.setUserInput("some input");

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("chat:template-hash-1", p.getGroupKey(),
                "模板 hash 存在时优先作为锚点（三元组语义），user_input 仅兜底");
    }

    @Test
    void multiTool_differentParamSignature_differentSkill() {
        InteractionRecord r1 = record("abc",
                List.of(tc("tool", Map.of("id", "String"))), true);
        InteractionRecord r2 = record("abc",
                List.of(tc("tool", Map.of("id", "String", "limit", "Integer"))), true);

        assertNotEquals(DeterministicSkillGrouper.group(r1).getGroupKey(),
                DeterministicSkillGrouper.group(r2).getGroupKey());
    }
}
