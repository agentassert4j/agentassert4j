package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskChainView 的单元测试 — 任务链派生规则黄金测试：
 * 任务键 = (会话, 请求文本)，携带前推覆盖 tool 轮，声明 taskKey 优先。
 * 派生是纯函数，规则一经发布冻结。
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
class TaskChainViewTest {

    private InteractionRecord record(String id, long timestamp, String userInput) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(id);
        r.setSessionId("s1");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setUserInput(userInput);
        return r;
    }

    @Test
    @DisplayName("黄金派生：请求→tool 轮（空输入）→回答同归一链")
    void golden_requestCarriesToolRounds() {
        List<TaskChain> chains = TaskChainView.resolveSession("s1", Arrays.asList(record("r1", 1000L, "查订单 ORD-001"), record("r2", 2000L, null), record("r3", 3000L, null)));

        assertEquals(1, chains.size());
        TaskChain chain = chains.get(0);
        assertEquals("s1", chain.getSessionId());
        assertEquals("查订单 ORD-001", chain.getRequestText());
        assertEquals(3, chain.getRecords().size(), "tool 轮由携带前推归入当前链");
        assertFalse(chain.isDeclared());
    }

    @Test
    @DisplayName("黄金派生：两个提问成两条链，链序=规范序")
    void golden_twoQuestions_twoChains() {
        List<TaskChain> chains = TaskChainView.resolveSession("s1", Arrays.asList(record("r1", 1000L, "查订单"), record("r2", 1500L, null), record("r3", 2000L, "申请退款"), record("r4", 2500L, null)));

        assertEquals(2, chains.size());
        assertEquals("查订单", chains.get(0).getRequestText());
        assertEquals(2, chains.get(0).getRecords().size());
        assertEquals("申请退款", chains.get(1).getRequestText());
        assertEquals(2, chains.get(1).getRecords().size());
    }

    @Test
    @DisplayName("同会话同文本重复提问并入同链（键的字面定义）")
    void identicalReask_mergesIntoSameChain() {
        List<TaskChain> chains = TaskChainView.resolveSession("s1", Arrays.asList(record("r1", 1000L, "查订单"), record("r2", 2000L, "查订单")));

        assertEquals(1, chains.size());
        assertEquals(2, chains.get(0).getRecords().size(), "同键两条执行并链，配对层按调用点 1:1 处理");
    }

    @Test
    @DisplayName("会话开头无请求的记录不属于任何任务链")
    void leadingToolRecord_excludedFromTaskViews() {
        List<TaskChain> chains = TaskChainView.resolveSession("s1", Arrays.asList(record("r0", 500L, null), record("r1", 1000L, "查订单")));

        assertEquals(1, chains.size());
        assertEquals(1, chains.get(0).getRecords().size());
        assertEquals("r1", chains.get(0).getRecords().get(0).getRecordId());
    }

    @Test
    @DisplayName("声明 taskKey 优先于 userInput 且独立成链")
    void declaredTaskKey_overridesDerivation() {
        InteractionRecord declared = record("r1", 1000L, "界面上的问法");
        declared.setMetadata("{\"taskKey\":\"refund-flow\"}");
        InteractionRecord declaredToolRound = record("r2", 1500L, null);
        declaredToolRound.setMetadata("{\"taskKey\":\"refund-flow\"}");

        List<TaskChain> chains = TaskChainView.resolveSession("s1", Arrays.asList(declared, declaredToolRound));

        assertEquals(1, chains.size());
        assertEquals("refund-flow", chains.get(0).getRequestText());
        assertTrue(chains.get(0).isDeclared(), "声明链与派生链分链");
    }

    @Test
    @DisplayName("空白输入视同无请求；乱序输入按规范序重排")
    void blankAndOutOfOrder() {
        List<TaskChain> chains = TaskChainView.resolveSession("s1", new ArrayList<>(Arrays.asList(record("r3", 3000L, null), record("r1", 1000L, "  "), record("r2", 2000L, "查订单"))));

        assertEquals(1, chains.size());
        assertEquals("查订单", chains.get(0).getRequestText());
        assertEquals(Arrays.asList("r2", "r3"), Arrays.asList(chains.get(0).getRecords().get(0).getRecordId(), chains.get(0).getRecords().get(1).getRecordId()), "链内记录按规范序");
    }

    @Test
    @DisplayName("resolveAll：跨会话合并且按链首时间升序")
    void resolveAll_ordersChainsByFirstTimestamp() {
        InteractionRecord a1 = record("a1", 2000L, "查订单");
        a1.setSessionId("sA");
        InteractionRecord b1 = record("b1", 1000L, "查订单");
        b1.setSessionId("sB");
        InteractionQueryStoreAdapter repo = new InteractionQueryStoreAdapter(Arrays.asList(a1, b1));

        List<TaskChain> chains = TaskChainView.resolveAll(repo);

        assertEquals(2, chains.size());
        assertEquals("sB", chains.get(0).getSessionId(), "链首时间早者在前");
        assertEquals("sA", chains.get(1).getSessionId());
    }

    /**
     * 最小查询桩：仅供 resolveAll 走通会话枚举（测试内联，避免共享桩膨胀）
     */
    private static class InteractionQueryStoreAdapter implements io.github.agentassert4j.spi.InteractionQueryStore {
        private final List<InteractionRecord> records;

        InteractionQueryStoreAdapter(List<InteractionRecord> records) {
            this.records = records;
        }

        @Override
        public List<InteractionRecord> findByInvocationId(String invocationId) {
            return new ArrayList<>();
        }

        @Override
        public List<InteractionRecord> findByInvocationKey(String invocationKey) {
            return new ArrayList<>();
        }

        @Override
        public List<InteractionRecord> findByTemplateHash(String hash) {
            return new ArrayList<>();
        }

        @Override
        public java.util.Set<String> findInvocationKeysByTemplateHash(String hash) {
            return new java.util.HashSet<>();
        }

        @Override
        public List<InteractionRecord> findBySessionId(String sessionId) {
            List<InteractionRecord> result = new ArrayList<>();
            for (InteractionRecord r : records) {
                if (sessionId.equals(r.getSessionId())) {
                    result.add(r);
                }
            }
            return result;
        }

        @Override
        public List<String> findAllSessionIds() {
            return new ArrayList<>(Arrays.asList("sA", "sB"));
        }
    }
}
