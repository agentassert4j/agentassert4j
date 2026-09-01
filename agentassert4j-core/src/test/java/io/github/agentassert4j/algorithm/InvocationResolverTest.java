package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.InvocationType;
import io.github.agentassert4j.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InvocationResolver 的单元测试 — invocationKey 派生规则即身份契约，
 * 黄金键测试钉住字面键值：键值一经发布不可变，变更即身份纪元事件
 * （历史基线全部失配），必须走显式设计。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class InvocationResolverTest {

    private InteractionRecord record(String templateHash, List<ToolCall> calls, boolean hasToolCalls) {
        InteractionRecord r = new InteractionRecord();
        r.setTemplateHash(templateHash);
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

    @Nested
    @DisplayName("黄金键：派生规则的字面键值")
    class GoldenKeys {

        @Test
        void goldenKey_declaredWithTemplate() {
            InteractionRecord r = record("tmpl-abc123", Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", "String"))), true);
            r.setInvocationId("order-flow");

            InvocationProfile p = InvocationResolver.resolve(r);

            assertEquals("invocation:order-flow:tmpl-abc123", p.getInvocationKey(), "声明锚点 + 模板细分，形状不混入身份");
            assertEquals("order-flow", p.getLabel(), "声明标签落画像列");
            assertEquals("tmpl-abc123", p.getTemplateHash(), "模板哈希落画像列");
        }

        @Test
        void goldenKey_declaredWithoutTemplate() {
            InteractionRecord r = record(null, null, false);
            r.setInvocationId("faq-bot");

            assertEquals("invocation:faq-bot", InvocationResolver.resolve(r).getInvocationKey());
        }

        @Test
        void goldenKey_undeclaredTemplateAnchor() {
            // 未声明但有模板：template:<hash> 字面键值冻结
            InteractionRecord r = record("a1b2c3d4", null, false);

            assertEquals("template:a1b2c3d4", InvocationResolver.resolve(r).getInvocationKey());
        }

        @Test
        void goldenKey_undeclaredAdhocRawAnchor() {
            // 无模板应用的请求锚点：sha256(modelRequestRaw) 的字面十六进制值冻结
            InteractionRecord r = record(null, null, false);
            r.setModelRequestRaw("hello");

            assertEquals("adhoc:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", InvocationResolver.resolve(r).getInvocationKey(), "adhoc 锚点 = sha256(请求原文) 的字面键值");
        }

        @Test
        void goldenKey_undeclaredAdhocUserInputFallback() {
            InteractionRecord r = record(null, null, false);
            r.setUserInput("查询订单状态");

            assertEquals("adhoc:f649e7357d51a3767bf5cb6d8fc93867995789a98b718f35f61799d7a53b2f48", InvocationResolver.resolve(r).getInvocationKey(), "请求原文缺失时退 sha256(用户输入)");
        }

        @Test
        void goldenKey_adhocNoAnchor() {
            // 全缺失（程序化构造防御）：稳定的 no-anchor 桶，不含字面 null
            InteractionRecord r = record(null, null, false);

            assertEquals("adhoc:no-anchor", InvocationResolver.resolve(r).getInvocationKey());
            assertFalse(InvocationResolver.resolve(r).getInvocationKey().contains("null"), "键不得出现字面 null 坍缩");
        }

        private static final String SKELETON = "客服助手系统提示词。当前日期：{{date}}；环境：{{env}}";
        private static final String SKELETON_SHA256 = "01a6620a2e2419bc01d23b63d658872d0d100544d3e073a3aab5bcf059665a27";

        @Test
        void goldenKey_undeclaredSkeletonAnchor() {
            // 未声明、无全文但有骨架：skeleton:<sha256(骨架)> 字面键值冻结
            InteractionRecord r = record(null, null, false);
            r.setTemplateSkeleton(SKELETON);

            assertEquals("skeleton:" + SKELETON_SHA256, InvocationResolver.resolve(r).getInvocationKey());
        }

        @Test
        void goldenKey_sameSkeletonDifferentFullText_sameKey() {
            // 同骨架异全文同键——动态段漂移不再裂键（本批要消灭的病）
            InteractionRecord v1 = record("full-text-hash-v1", null, false);
            v1.setTemplateSkeleton(SKELETON);
            InteractionRecord v2 = record("full-text-hash-v2", null, false);
            v2.setTemplateSkeleton(SKELETON);

            assertEquals(InvocationResolver.resolve(v1).getInvocationKey(), InvocationResolver.resolve(v2).getInvocationKey());
            assertEquals("skeleton:" + SKELETON_SHA256, InvocationResolver.resolve(v2).getInvocationKey());
        }

        @Test
        void goldenKey_declaredSkeletonSubdivision() {
            // 声明标签的细分改用骨架哈希：同一声明标签不随组装漂移裂成多键
            InteractionRecord r = record("full-text-hash-v1", null, false);
            r.setInvocationId("order-flow");
            r.setTemplateSkeleton(SKELETON);

            assertEquals("invocation:order-flow:" + SKELETON_SHA256, InvocationResolver.resolve(r).getInvocationKey());
        }

        @Test
        void goldenKey_persistedProjection_rehashSameKey() {
            // 落库形态（骨架文本不落列，投影列是读侧唯一骨架信息源）：
            // 仅带 skeletonHash 的记录重算出同一骨架键——recordCandidate/建档键不分叉
            InteractionRecord inMemory = record(null, null, false);
            inMemory.setTemplateSkeleton(SKELETON);
            InteractionRecord fromDb = record(null, null, false);
            fromDb.setSkeletonHash(SKELETON_SHA256);

            assertEquals(InvocationResolver.resolve(inMemory).getInvocationKey(), InvocationResolver.resolve(fromDb).getInvocationKey());
        }

        @Test
        void skeletonText_beatsProjection_singleSource() {
            // 文本与投影并存时文本现算优先——真源唯一，即使投影被错误手工设置
            InteractionRecord r = record(null, null, false);
            r.setTemplateSkeleton(SKELETON);
            r.setSkeletonHash("stale-projection-hash");

            assertEquals("skeleton:" + SKELETON_SHA256, InvocationResolver.resolve(r).getInvocationKey());
        }

        @Test
        void skeletonAnchor_outranksTemplateAnchor() {
            // 未声明且骨架/全文并存：身份按骨架定格（模板锚点只在无骨架时生效）
            InteractionRecord r = record("full-text-hash-v1", null, false);
            r.setTemplateSkeleton(SKELETON);

            assertEquals("skeleton:" + SKELETON_SHA256, InvocationResolver.resolve(r).getInvocationKey());
            assertFalse(InvocationResolver.resolve(r).getInvocationKey().startsWith("template:"));
        }
    }

    @Nested
    @DisplayName("身份语义：形状退出身份、输入侧边界")
    class IdentitySemantics {

        @Test
        void undeclared_toolAndChatWithSameTemplate_shareKey() {
            // 形状不参与身份：同模板哈希的工具调用与纯对话记录是同一调用点——
            // 工具选择差异由指纹对比暴露，这正是回归要抓的对象
            InteractionRecord withTool = record("tmpl-1", Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", "String"))), true);
            InteractionRecord withoutTool = record("tmpl-1", null, false);

            assertEquals(InvocationResolver.resolve(withTool).getInvocationKey(), InvocationResolver.resolve(withoutTool).getInvocationKey(), "模板锚点统一工具与纯对话");
        }

        @Test
        void declared_toolAndChatWithSameLabelAndTemplate_shareKey() {
            InteractionRecord withTool = record("tmpl-1", Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", "String"))), true);
            withTool.setInvocationId("order-flow");
            InteractionRecord withoutTool = record("tmpl-1", null, false);
            withoutTool.setInvocationId("order-flow");

            assertEquals(InvocationResolver.resolve(withTool).getInvocationKey(), InvocationResolver.resolve(withoutTool).getInvocationKey(), "声明锚点优先，形状不参与声明记录的身份");
        }

        @Test
        void templateHashTakesPrecedenceOverUserInput() {
            // 有模板时输入内容不影响身份（输入侧不进身份的声明分支）
            InteractionRecord r1 = record("hash1", null, false);
            r1.setUserInput("查询订单状态");
            InteractionRecord r2 = record("hash1", null, false);
            r2.setUserInput("帮我写一首诗");

            assertEquals(InvocationResolver.resolve(r1).getInvocationKey(), InvocationResolver.resolve(r2).getInvocationKey(), "同模板未声明 → 同键，用户输入不是锚点");
        }

        @Test
        void adhoc_sameRequest_sameKey() {
            InteractionRecord r1 = record(null, null, false);
            r1.setModelRequestRaw("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q1\"}]}");
            InteractionRecord r2 = record(null, null, false);
            r2.setModelRequestRaw("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q1\"}]}");

            assertEquals(InvocationResolver.resolve(r1).getInvocationKey(), InvocationResolver.resolve(r2).getInvocationKey(), "同请求原文 → 同调用点（请求锚点的对齐根基）");
        }

        @Test
        void adhoc_differentRequest_differentKey() {
            InteractionRecord r1 = record(null, null, false);
            r1.setModelRequestRaw("request-1");
            InteractionRecord r2 = record(null, null, false);
            r2.setModelRequestRaw("request-2");

            assertNotEquals(InvocationResolver.resolve(r1).getInvocationKey(), InvocationResolver.resolve(r2).getInvocationKey());
        }

        @Test
        void differentTemplates_differentKeys() {
            InteractionRecord r1 = record("hash1", null, false);
            InteractionRecord r2 = record("hash2", null, false);

            assertNotEquals(InvocationResolver.resolve(r1).getInvocationKey(), InvocationResolver.resolve(r2).getInvocationKey());
        }
    }

    @Nested
    @DisplayName("键文法单射：组件内容永不伪造文法结构")
    class Injectivity {

        @Test
        void declaredLabelContainingColon_neverMergesWithSibling() {
            // 键文法单射：标签含冒号时与「前缀标签 + 恰好同值模板」不得同键——
            // 团队命名规范（如 "module:flow"）不受框架文法约束
            InteractionRecord colonId = record(null, null, false);
            colonId.setInvocationId("billing:refund");
            InteractionRecord prefixIdWithTemplate = record("refund", null, false);
            prefixIdWithTemplate.setInvocationId("billing");

            InvocationProfile p1 = InvocationResolver.resolve(colonId);
            InvocationProfile p2 = InvocationResolver.resolve(prefixIdWithTemplate);

            assertNotEquals(p1.getInvocationKey(), p2.getInvocationKey(), "标签含冒号不得与兄弟声明同键");
            assertEquals("invocation:billing%3Arefund", p1.getInvocationKey());
        }

        @Test
        void labelContainingGrammarChars_roundTripsThroughEncoding() {
            InteractionRecord r = record(null, null, false);
            r.setInvocationId("a%b:c+d[e],f");

            assertEquals("invocation:a%25b%3Ac%2Bd%5Be%5D%2Cf", InvocationResolver.resolve(r).getInvocationKey(), "全部文法字符转义，组件内容到键单射");
        }

        @Test
        void declaredAndUndeclaredNamespaces_neverCollide() {
            // 声明命名空间与模板命名空间分离：恰同字面的标签与哈希不得同键
            InteractionRecord declared = record(null, null, false);
            declared.setInvocationId("x");
            InteractionRecord undeclared = record(null, null, false);
            undeclared.setTemplateHash("x");

            assertNotEquals(InvocationResolver.resolve(declared).getInvocationKey(), InvocationResolver.resolve(undeclared).getInvocationKey(), "前缀命名空间隔离声明与模板锚点");
        }
    }

    @Nested
    @DisplayName("视图列：paramSignature 与类型分类")
    class ViewColumns {

        @Test
        void paramSignature_normalizesCase() {
            // "String" vs "string"、"OrderId" vs "orderid" → 同一视图签名
            InteractionRecord r1 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("OrderId", "String"))), true);
            InteractionRecord r2 = record("abc", Collections.singletonList(tc("tool", Collections.singletonMap("orderid", "string"))), true);

            assertEquals(InvocationResolver.resolve(r1).getParamSignature(), InvocationResolver.resolve(r2).getParamSignature());
            assertEquals("orderid:string", InvocationResolver.resolve(r1).getParamSignature());
        }

        @Test
        void paramSignature_emptyWithoutToolCalls() {
            InteractionRecord r = record("abc", null, false);

            assertEquals("", InvocationResolver.resolve(r).getParamSignature());
        }

        @Test
        void invocationType_viewClassification() {
            InteractionRecord tool = record("abc", Collections.singletonList(tc("tool", null)), true);
            InteractionRecord chat = record("abc", null, false);

            assertEquals(InvocationType.TOOL, InvocationResolver.resolve(tool).getInvocationType());
            assertEquals(InvocationType.PURE_CHAT, InvocationResolver.resolve(chat).getInvocationType());
        }

        @Test
        void undeclaredProfile_labelIsNull() {
            InteractionRecord r = record("abc", null, false);

            assertNull(InvocationResolver.resolve(r).getLabel(), "未声明调用点无标签，身份由键承载");
        }

        @Test
        void multiTool_paramSignatureAggregates() {
            InteractionRecord r = record("abc", Arrays.asList(tc("tool", stringMap("id", "String", "limit", "Integer"))), true);

            assertEquals("id:string,limit:integer", InvocationResolver.resolve(r).getParamSignature());
        }
    }

    private static Map<String, String> stringMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
