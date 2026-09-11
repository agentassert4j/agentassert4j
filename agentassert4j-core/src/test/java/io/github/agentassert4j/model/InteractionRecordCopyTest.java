package io.github.agentassert4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * InteractionRecord.copy() 的拷贝完整性契约：全字段值相等 + 可变结构不共享实例。
 *
 * <p>字段清单以反射从模型自身枚举而非手写第二份——模型新增字段时本测试自动
 * 覆盖新字段，copy() 漏拷当场红；手写逐字段断言清单反而是又一份需要养的数据。</p>
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
class InteractionRecordCopyTest {

    @Test
    @DisplayName("copy 全字段值相等")
    void copyPreservesEveryFieldValue() throws Exception {
        InteractionRecord original = fullyPopulated();
        InteractionRecord copy = original.copy();

        for (Field field : InteractionRecord.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getName().equals("toolCalls") || field.getName().equals("previousTurns")) {
                continue;
            }
            assertEquals(field.get(original), field.get(copy), "字段 " + field.getName() + " 拷贝后值必须相等");
        }

        assertListCopied(original.getToolCalls(), copy.getToolCalls(), ToolCall.class);
        assertListCopied(original.getPreviousTurns(), copy.getPreviousTurns(), TurnContext.class);
    }

    @Test
    @DisplayName("修改副本的嵌套结构不泄漏回原件")
    void copyMutationsDoNotLeakToOriginal() {
        InteractionRecord original = fullyPopulated();
        InteractionRecord copy = original.copy();

        Map<String, Object> copiedArgs = copy.getToolCalls().get(0).getArguments();
        @SuppressWarnings("unchecked") List<Object> copiedList = (List<Object>) copiedArgs.get("nested");
        @SuppressWarnings("unchecked") Map<String, Object> copiedInner = (Map<String, Object>) copiedList.get(0);
        copiedInner.put("k", "mutated");

        Map<String, Object> originalArgs = original.getToolCalls().get(0).getArguments();
        @SuppressWarnings("unchecked") List<Object> originalList = (List<Object>) originalArgs.get("nested");
        @SuppressWarnings("unchecked") Map<String, Object> originalInner = (Map<String, Object>) originalList.get(0);
        assertEquals("v", originalInner.get("k"), "原件嵌套结构不得被副本修改波及");

        copy.setUserInput("changed");
        assertEquals("in", original.getUserInput());
    }

    private void assertListCopied(List<?> expected, List<?> actual, Class<?> elementType) throws Exception {
        assertEquals(expected == null, actual == null);
        if (expected == null) {
            return;
        }
        assertNotSame(expected, actual, "列表必须重建，不得共享实例");
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Object expectedItem = expected.get(i);
            Object actualItem = actual.get(i);
            assertEquals(expectedItem == null, actualItem == null, "null 元素的序列形状必须保持");
            if (expectedItem == null) {
                continue;
            }
            assertNotSame(expectedItem, actualItem, "元素必须重建，不得共享实例");
            for (Field field : elementType.getDeclaredFields()) {
                field.setAccessible(true);
                assertEquals(field.get(expectedItem), field.get(actualItem), elementType.getSimpleName() + " 字段 " + field.getName() + " 拷贝后值必须相等");
            }
        }
    }

    /**
     * 全字段显式填充（含嵌套值树与 null 元素），任何字段漏填会让反射比对空转失效。
     */
    private InteractionRecord fullyPopulated() {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setTimestamp(1L);
        r.setSeq(2L);
        r.setTemplateId("tpl");
        r.setTemplateHash("hash");
        r.setTemplateText("text");
        r.setTemplateSkeleton("skeleton");
        r.setSkeletonHash("skeleton-hash");
        r.setApiProtocol("openai-chat");
        r.setProvider("deepseek");
        r.setModel("m");
        r.setServedModel("served");
        r.setEndpoint("http://e");
        r.setUserInput("in");
        r.setTurnIndex(3);
        r.setToolsDefinition("[]");
        r.setSamplingParams("{}");
        r.setModelRequestRaw("raw-req");
        r.setFinishReason("stop");
        r.setModelResponse("resp");
        r.setModelResponseRaw("raw-resp");
        r.setInputTokens(10);
        r.setOutputTokens(20);
        r.setCacheReadTokens(30);
        r.setCacheWriteTokens(40);
        r.setReasoningTokens(50);
        r.setUsageRaw("{}");
        r.setLatencyMs(60L);
        r.setTtftMs(70L);
        r.setCostUsd(0.5);
        r.setHasToolCalls(true);
        r.setSessionId("sess");
        r.setInvocationId("inv");
        r.setInvocationKey("invocation:k");
        r.setMultimodalInput(true);
        r.setMultimodalContent("[]");
        r.setMetadata("{}");
        r.setRecorderVersion("v");

        ToolCall tc = new ToolCall();
        tc.setToolName("t");
        tc.setToolCallId("c1");
        tc.setSuccess(true);
        tc.setResult("r");
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "String");
        tc.setArgTypes(types);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", "A");
        List<Object> nested = new ArrayList<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", "v");
        nested.add(inner);
        args.put("nested", nested);
        tc.setArguments(args);

        List<ToolCall> calls = new ArrayList<>();
        calls.add(tc);
        calls.add(null);
        r.setToolCalls(calls);

        TurnContext turn = new TurnContext("user", "hello");
        turn.setToolCallId("c0");
        turn.setToolName("t0");
        turn.setToolArguments("{}");
        List<TurnContext> turns = new ArrayList<>();
        turns.add(turn);
        turns.add(null);
        r.setPreviousTurns(turns);
        return r;
    }
}
