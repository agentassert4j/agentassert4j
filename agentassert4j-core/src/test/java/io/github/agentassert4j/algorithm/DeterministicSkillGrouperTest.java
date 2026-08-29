package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.util.HashUtil;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeterministicSkillGrouper 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
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
        InteractionRecord r = record("abc123", Collections.singletonList(tc("queryOrderDB", Collections.singletonMap("orderId", "String"))), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("queryOrderDB[orderid:string]", p.getGroupKey());
        assertEquals(SkillType.TOOL_SKILL, p.getSkillType());
        assertEquals("queryOrderDB", p.getSkillName());
        assertEquals(HashUtil.sha256(p.getGroupKey()), p.getSkillId());
        assertEquals("orderid:string", p.getParamSignature());
    }

    @Test
    void singleTool_noArgTypes_emptySignature() {
        InteractionRecord r = record("abc123", Collections.singletonList(tc("queryOrderDB", null)), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("queryOrderDB[]", p.getGroupKey());
        assertEquals("", p.getParamSignature());
    }

    @Test
    void multiTool_sortedNames() {
        InteractionRecord r = record("abc123", Arrays.asList(tc("queryOrderDB", Collections.singletonMap("orderId", "String")), tc("checkInventory", Collections.singletonMap("skuId", "String"))), true);
        SkillProfile p = DeterministicSkillGrouper.group(r);

        // sorted: checkInventory < queryOrderDB
        assertEquals("checkInventory+queryOrderDB[orderid:string,skuid:string]", p.getGroupKey());
        assertEquals("checkInventory+queryOrderDB", p.getSkillName());
    }

    @Test
    void multiTool_orderInsensitive() {
        InteractionRecord r1 = record("abc", Arrays.asList(tc("B", Collections.singletonMap("x", "int")), tc("A", Collections.singletonMap("y", "string"))), true);
        InteractionRecord r2 = record("abc", Arrays.asList(tc("A", Collections.singletonMap("y", "string")), tc("B", Collections.singletonMap("x", "int"))), true);

        SkillProfile p1 = DeterministicSkillGrouper.group(r1);
        SkillProfile p2 = DeterministicSkillGrouper.group(r2);

        assertEquals(p1.getGroupKey(), p2.getGroupKey());
        assertEquals(p1.getSkillId(), p2.getSkillId());
    }

    @Test
    void paramSignature_normalizeCase() {
        // "String" vs "string" → 相同
        InteractionRecord r1 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("id", "String"))), true);
        InteractionRecord r2 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("id", "string"))), true);

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(), DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    @Test
    void paramSignature_normalizeCaseKey() {
        // "OrderId" vs "orderid" → 相同（key 也 toLowerCase）
        InteractionRecord r1 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("OrderId", "String"))), true);
        InteractionRecord r2 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("orderid", "string"))), true);

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(), DeterministicSkillGrouper.group(r2).getGroupKey());
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

        assertEquals(DeterministicSkillGrouper.group(r1).getSkillId(), DeterministicSkillGrouper.group(r2).getSkillId());
    }

    @Test
    void differentPrompts_differentGroupKeys() {
        InteractionRecord r1 = record("hash1", null, false);
        InteractionRecord r2 = record("hash2", null, false);

        assertNotEquals(DeterministicSkillGrouper.group(r1).getGroupKey(), DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    @Test
    void pureChat_nullTemplateHash_defensiveEmptyAnchor() {
        // 未声明且无工具调用的纯对话由采集门过滤、不进入分组，本分支仅防御程序化构造：
        // 派生契约冻结为 "chat:" + templateHash，双缺失时锚空串——确定且不含字面 null
        // （user_input hash 与 no-anchor 两条兜底路径已随身份锚点收敛物理删除）
        InteractionRecord r = record(null, null, false);

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("chat:", p.getGroupKey(), "防御分支产出稳定空锚键，不得复活 user_input hash 或 no-anchor");
        assertFalse(p.getGroupKey().contains("null"), "groupKey 不得出现字面 null 坍缩");
        assertEquals(HashUtil.sha256("chat:"), p.getSkillId());
    }

    @Test
    void pureChat_userInputNeverEntersDerivation() {
        // user input 不参与身份派生：同模板同声明状态 → 同组，与输入内容无关
        InteractionRecord r1 = record("hash1", null, false);
        r1.setUserInput("查询订单状态");
        InteractionRecord r2 = record("hash1", null, false);
        r2.setUserInput("帮我写一首诗");

        assertEquals(DeterministicSkillGrouper.group(r1).getGroupKey(), DeterministicSkillGrouper.group(r2).getGroupKey(), "同模板未声明 → 同组，用户输入不是身份锚点");
    }

    @Test
    void pureChat_templateHashTakesPrecedenceOverUserInputFallback() {
        InteractionRecord r = record("template-hash-1", null, false);
        r.setUserInput("some input");

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("chat:template-hash-1", p.getGroupKey(), "模板 hash 存在时作为锚点，user_input 仅兜底");
    }

    @Test
    void goldenKey_declaredWithTemplate() {
        // 黄金键（D6 方案 A：派生规则冻结为身份契约）——以下字面键值一经发布即不可改变，
        // 变更即身份纪元事件（历史基线全部失配），必须升判定语义版本并走显式设计
        InteractionRecord r = record("tmpl-abc123", Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", "String"))), true);
        r.setSkillId("order-flow");

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("skill:order-flow:tmpl-abc123", p.getGroupKey(), "声明锚点 + 模板细分，形状不混入身份");
        assertEquals(HashUtil.sha256("skill:order-flow:tmpl-abc123"), p.getSkillId());
    }

    @Test
    void goldenKey_declaredWithoutTemplate() {
        InteractionRecord r = record(null, null, false);
        r.setSkillId("faq-bot");

        SkillProfile p = DeterministicSkillGrouper.group(r);

        assertEquals("skill:faq-bot", p.getGroupKey());
    }

    @Test
    void goldenKey_declaredShapeAgnostic_toolAndChatMerge() {
        // 声明记录的同桶多形状分支不靠身份拆分：带工具与不带工具的同声明同模板记录同组，
        // 形状差异交给指纹对比暴露（这正是回归要抓的对象）
        InteractionRecord withTool = record("tmpl-1", Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", "String"))), true);
        withTool.setSkillId("order-flow");
        InteractionRecord withoutTool = record("tmpl-1", null, false);
        withoutTool.setSkillId("order-flow");

        assertEquals(DeterministicSkillGrouper.group(withTool).getGroupKey(), DeterministicSkillGrouper.group(withoutTool).getGroupKey(), "声明锚点优先于形状派生，形状不参与声明记录的身份");
    }

    @Test
    void goldenKey_undeclaredToolShapeUnchanged() {
        // 未声明工具记录的形状派生键保持历史形态（冻结契约的一部分）
        InteractionRecord r = record("whatever", Collections.singletonList(tc("queryOrderDB", Collections.singletonMap("orderId", "String"))), true);

        assertEquals("queryOrderDB[orderid:string]", DeterministicSkillGrouper.group(r).getGroupKey());
    }

    @Test
    void injectivity_declaredSkillIdContainingColon_neverMergesWithSibling() {
        // 键文法单射：skillId 含冒号时与「前缀 skillId + 恰好同值模板」不得同键——
        // 团队命名规范（如 "module:skill"）不受框架文法约束
        InteractionRecord colonId = record(null, null, false);
        colonId.setSkillId("billing:refund");
        InteractionRecord prefixIdWithTemplate = record("refund", null, false);
        prefixIdWithTemplate.setSkillId("billing");

        SkillProfile p1 = DeterministicSkillGrouper.group(colonId);
        SkillProfile p2 = DeterministicSkillGrouper.group(prefixIdWithTemplate);

        assertNotEquals(p1.getGroupKey(), p2.getGroupKey(), "skillId 含冒号不得与兄弟声明同键");
        assertEquals("skill:billing%3Arefund", p1.getGroupKey());
    }

    @Test
    void injectivity_toolNameContainingSeparator_neverMergesWithSplitTools() {
        // 键文法单射：单个名为 "a+b" 的工具与 "a"、"b" 两个工具不得同键——
        // MCP 工具名由 server 侧控制，连接符字符必须不能伪造多工具形状
        InteractionRecord joined = record(null, Collections.singletonList(tc("a+b", Collections.singletonMap("id", "String"))), true);
        InteractionRecord split = record(null, Arrays.asList(tc("a", Collections.singletonMap("id", "String")), tc("b", Collections.singletonMap("id", "String"))), true);

        assertNotEquals(DeterministicSkillGrouper.group(joined).getGroupKey(), DeterministicSkillGrouper.group(split).getGroupKey());
        assertEquals("a%2Bb[id:string]", DeterministicSkillGrouper.group(joined).getGroupKey());
    }

    @Test
    void multiTool_differentParamSignature_differentSkill() {
        InteractionRecord r1 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("id", "String"))), true);
        InteractionRecord r2 = record("abc", Arrays.asList(tc("tool", stringMap("id", "String", "limit", "Integer"))), true);

        assertNotEquals(DeterministicSkillGrouper.group(r1).getGroupKey(), DeterministicSkillGrouper.group(r2).getGroupKey());
    }

    private static Map<String, String> stringMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

}
