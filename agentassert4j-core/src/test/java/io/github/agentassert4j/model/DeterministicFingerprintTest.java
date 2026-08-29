package io.github.agentassert4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeterministicFingerprint 的值对象语义测试（equals/hashCode 全维度字段覆盖）。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
class DeterministicFingerprintTest {

    /**
     * 四个维度字段全部非空的基准指纹——各差异测试在拷贝上单字段变异。
     */
    private static DeterministicFingerprint base() {
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new HashSet<>(Arrays.asList("get_order", "cancel_order")));
        fp.setToolParamTypes(singletonMap("orderId", "String"));
        fp.setOutputContentType("json");
        fp.setOutputFieldPaths(new HashSet<>(Arrays.asList("orderId", "amount")));
        fp.setOutputFieldTypeMap(singletonMap("orderId", "String"));
        fp.setTextLengthMagnitude(2);
        fp.setRequiredKeywords(new HashSet<>(Arrays.asList("订单")));
        fp.setForbiddenKeywords(new HashSet<>(Arrays.asList("抱歉")));
        fp.setRegexPatterns(Collections.singletonList(new RegexPattern("\\d{4}", "订单号")));
        fp.setDeclaredBehaviors(new HashSet<>(Arrays.asList("noError")));
        fp.setHasError(false);
        return fp;
    }

    private static Map<String, String> singletonMap(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void equals_sameValuesDifferentCollectionImpls() {
        // 同值不同集合实现（HashSet vs LinkedHashSet、HashMap vs TreeMap）必须等值——
        // 跨存储层反序列化可能换集合类型，值比较不得依赖实现
        DeterministicFingerprint a = base();
        DeterministicFingerprint b = new DeterministicFingerprint();
        b.setToolCallSet(new LinkedHashSet<>(Arrays.asList("get_order", "cancel_order")));
        Map<String, String> orderedTypes = new TreeMap<>();
        orderedTypes.put("orderId", "String");
        b.setToolParamTypes(orderedTypes);
        b.setOutputContentType("json");
        b.setOutputFieldPaths(new LinkedHashSet<>(Arrays.asList("orderId", "amount")));
        b.setOutputFieldTypeMap(new TreeMap<>(orderedTypes));
        b.setTextLengthMagnitude(2);
        b.setRequiredKeywords(new LinkedHashSet<>(Arrays.asList("订单")));
        b.setForbiddenKeywords(new LinkedHashSet<>(Arrays.asList("抱歉")));
        b.setRegexPatterns(Collections.singletonList(new RegexPattern("\\d{4}", "订单号")));
        b.setDeclaredBehaviors(new LinkedHashSet<>(Arrays.asList("noError")));
        b.setHasError(false);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_selfTrue_nullFalse() {
        DeterministicFingerprint fp = base();

        assertTrue(fp.equals(fp));
        assertFalse(fp.equals(null));
        assertFalse(fp.equals("not a fingerprint"));
    }

    @Test
    void equals_allFieldsNull_stillEqual() {
        assertEquals(new DeterministicFingerprint(), new DeterministicFingerprint());
        assertEquals(new DeterministicFingerprint().hashCode(), new DeterministicFingerprint().hashCode());
    }

    @Nested
    @DisplayName("逐字段差异打破等值")
    class FieldDifferences {

        @Test
        @DisplayName("维度 1：工具集 / 参数类型签名")
        void dimension1_toolFields() {
            DeterministicFingerprint a = base();
            DeterministicFingerprint b = base();
            b.setToolCallSet(new HashSet<>(Arrays.asList("get_order")));
            assertNotEquals(a, b);

            DeterministicFingerprint c = base();
            c.setToolParamTypes(singletonMap("orderId", "Integer"));
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("维度 2：内容类型 / 字段路径 / 字段类型 / 文本量级")
        void dimension2_structureFields() {
            DeterministicFingerprint a = base();

            DeterministicFingerprint b = base();
            b.setOutputContentType("text");
            assertNotEquals(a, b);

            DeterministicFingerprint c = base();
            c.setOutputFieldPaths(new HashSet<>(Arrays.asList("orderId")));
            assertNotEquals(a, c);

            DeterministicFingerprint d = base();
            d.setOutputFieldTypeMap(singletonMap("amount", "Number"));
            assertNotEquals(a, d);

            DeterministicFingerprint e = base();
            e.setTextLengthMagnitude(3);
            assertNotEquals(a, e);
        }

        @Test
        @DisplayName("维度 3：必含词 / 禁用词 / 正则列表（列表按声明顺序比较）")
        void dimension3_ruleFields() {
            DeterministicFingerprint a = base();

            DeterministicFingerprint b = base();
            b.setRequiredKeywords(new HashSet<>(Arrays.asList("发货")));
            assertNotEquals(a, b);

            DeterministicFingerprint c = base();
            c.setForbiddenKeywords(Collections.<String>emptySet());
            assertNotEquals(a, c);

            DeterministicFingerprint d = base();
            d.setRegexPatterns(Collections.singletonList(new RegexPattern("\\d{2}", "两位数")));
            assertNotEquals(a, d);

            // 顺序敏感：相同元素不同声明顺序不等值（与 Set 的顺序无关形成对照）
            DeterministicFingerprint e = base();
            RegexPattern digits = new RegexPattern("\\d{4}", "订单号");
            RegexPattern phone = new RegexPattern("1\\d{10}", "手机号");
            e.setRegexPatterns(Arrays.asList(phone, digits));
            DeterministicFingerprint f = base();
            f.setRegexPatterns(Arrays.asList(digits, phone));
            assertNotEquals(e, f);
        }

        @Test
        @DisplayName("维度 4：声明行为 / 错误标志")
        void dimension4_behaviorFields() {
            DeterministicFingerprint a = base();

            DeterministicFingerprint b = base();
            b.setDeclaredBehaviors(new HashSet<>(Arrays.asList("returnsEmptyOnError")));
            assertNotEquals(a, b);

            DeterministicFingerprint c = base();
            c.setHasError(true);
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("等值对象改回同值后恢复等值（列表可变元素的值拷贝核对）")
        void regexElement_mutationRestoresEquality() {
            DeterministicFingerprint a = base();
            DeterministicFingerprint b = base();
            b.setRegexPatterns(new ArrayList<RegexPattern>(Arrays.asList(new RegexPattern("\\d{4}", "别的描述"))));
            assertNotEquals(a, b);

            b.setRegexPatterns(Collections.singletonList(new RegexPattern("\\d{4}", "订单号")));
            assertEquals(a, b);
        }
    }
}
