package io.github.agentassert4j.util;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;


/**
 * RecursiveJsonParser 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
@SuppressWarnings("unchecked")
class RecursiveJsonParserTest {

    @Test
    void parse_null_returnsNull() {
        assertNull(RecursiveJsonParser.parse("null"));
    }

    @Test
    void parse_true() {
        assertEquals(Boolean.TRUE, RecursiveJsonParser.parse("true"));
    }

    @Test
    void parse_false() {
        assertEquals(Boolean.FALSE, RecursiveJsonParser.parse("false"));
    }

    @Test
    void parse_integer() {
        assertEquals(42L, RecursiveJsonParser.parse("42"));
    }

    @Test
    void parse_negativeInteger() {
        assertEquals(-7L, RecursiveJsonParser.parse("-7"));
    }

    @Test
    void parse_zero() {
        assertEquals(0L, RecursiveJsonParser.parse("0"));
    }

    @Test
    void parse_float() {
        assertEquals(3.14, RecursiveJsonParser.parse("3.14"));
    }

    @Test
    void parse_negativeFloat() {
        assertEquals(-0.5, RecursiveJsonParser.parse("-0.5"));
    }

    @Test
    void parse_exponent() {
        Object result = RecursiveJsonParser.parse("1e10");
        assertInstanceOf(Double.class, result);
        assertEquals(1e10, (Double) result, 0.001);
    }

    @Test
    void parse_string() {
        assertEquals("hello", RecursiveJsonParser.parse("\"hello\""));
    }

    @Test
    void parse_emptyJsonString() {
        assertEquals("", RecursiveJsonParser.parse("\"\""));
    }

    @Test
    void parse_stringWithEscapes() {
        // \" \\ \/ \b \f \n \r \t
        assertEquals("a\"b", RecursiveJsonParser.parse("\"a\\\"b\""));
        assertEquals("a\\b", RecursiveJsonParser.parse("\"a\\\\b\""));
        assertEquals("a/b", RecursiveJsonParser.parse("\"a\\/b\""));
        assertEquals("a\bb", RecursiveJsonParser.parse("\"a\\bb\""));
        assertEquals("a\fb", RecursiveJsonParser.parse("\"a\\fb\""));
        assertEquals("a\nb", RecursiveJsonParser.parse("\"a\\nb\""));
        assertEquals("a\rb", RecursiveJsonParser.parse("\"a\\rb\""));
        assertEquals("a\tb", RecursiveJsonParser.parse("\"a\\tb\""));
    }

    @Test
    void parse_unicodeEscape() {
        assertEquals("A", RecursiveJsonParser.parse("\"\\u0041\""));
        assertEquals("中文", RecursiveJsonParser.parse("\"\\u4e2d\\u6587\""));
    }

    @Test
    void parse_emptyObject() {
        Object result = RecursiveJsonParser.parse("{}");
        assertInstanceOf(Map.class, result);
        assertTrue(((Map<?, ?>) result).isEmpty());
    }

    @Test
    void parse_simpleObject() {
        Object result = RecursiveJsonParser.parse("{\"name\":\"test\",\"value\":42}");
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("test", map.get("name"));
        assertEquals(42L, map.get("value"));
    }

    @Test
    void parse_objectPreservesOrder() {
        Object result = RecursiveJsonParser.parse("{\"a\":1,\"b\":2,\"c\":3}");
        Map<String, Object> map = (Map<String, Object>) result;
        String[] keys = map.keySet().toArray(new String[0]);
        assertArrayEquals(new String[]{"a", "b", "c"}, keys);
    }

    @Test
    void parse_emptyArray() {
        Object result = RecursiveJsonParser.parse("[]");
        assertInstanceOf(List.class, result);
        assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    void parse_numberArray() {
        Object result = RecursiveJsonParser.parse("[1, 2, 3]");
        List<Object> list = (List<Object>) result;
        assertEquals(Arrays.asList(1L, 2L, 3L), list);
    }

    @Test
    void parse_mixedArray() {
        Object result = RecursiveJsonParser.parse("[\"a\", 1, true, null, 3.14]");
        List<Object> list = (List<Object>) result;
        assertEquals(5, list.size());
        assertEquals("a", list.get(0));
        assertEquals(1L, list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
        assertNull(list.get(3));
        assertEquals(3.14, list.get(4));
    }

    @Test
    void parse_nestedObject() {
        String json = "{\"a\":{\"b\":{\"c\":42}}}";
        Object result = RecursiveJsonParser.parse(json);
        Map<String, Object> map = (Map<String, Object>) result;
        Map<String, Object> a = (Map<String, Object>) map.get("a");
        Map<String, Object> b = (Map<String, Object>) a.get("b");
        assertEquals(42L, b.get("c"));
    }

    @Test
    void parse_arrayOfObjects() {
        String json = "[{\"name\":\"a\"},{\"name\":\"b\"}]";
        Object result = RecursiveJsonParser.parse(json);
        List<Object> list = (List<Object>) result;
        assertEquals(2, list.size());
        Map<String, Object> first = (Map<String, Object>) list.get(0);
        assertEquals("a", first.get("name"));
    }

    @Test
    void parse_objectWithArray() {
        String json = "{\"items\":[1,2,3]}";
        Object result = RecursiveJsonParser.parse(json);
        Map<String, Object> map = (Map<String, Object>) result;
        List<Object> items = (List<Object>) map.get("items");
        assertEquals(Arrays.asList(1L, 2L, 3L), items);
    }

    @Test
    void parse_toolCallsStructure() {
        String json = "[{\"toolName\":\"queryOrderDB\",\"arguments\":{\"orderId\":\"ORD-001\"}}]";
        Object result = RecursiveJsonParser.parse(json);
        List<Object> list = (List<Object>) result;
        Map<String, Object> tc = (Map<String, Object>) list.get(0);
        assertEquals("queryOrderDB", tc.get("toolName"));
        Map<String, Object> args = (Map<String, Object>) tc.get("arguments");
        assertEquals("ORD-001", args.get("orderId"));
    }

    @Test
    void parse_fingerprintStructure() {
        String json = "{\"toolParamTypes\":{\"orderId\":\"String\"},\"outputFields\":[\"orderId\",\"amount\"]}";
        Object result = RecursiveJsonParser.parse(json);
        Map<String, Object> map = (Map<String, Object>) result;
        Map<String, Object> types = (Map<String, Object>) map.get("toolParamTypes");
        assertEquals("String", types.get("orderId"));
        List<Object> fields = (List<Object>) map.get("outputFields");
        assertEquals(Arrays.asList("orderId", "amount"), fields);
    }

    @Test
    void parse_graphStructure() {
        String json = "{\"adj\":{\"queryOrderDB\":{\"formatOrder\":2}}}";
        Object result = RecursiveJsonParser.parse(json);
        Map<String, Object> map = (Map<String, Object>) result;
        Map<String, Object> adj = (Map<String, Object>) map.get("adj");
        Map<String, Object> qo = (Map<String, Object>) adj.get("queryOrderDB");
        assertEquals(2L, qo.get("formatOrder"));
    }

    @Test
    void parse_nullInput() {
        assertNull(RecursiveJsonParser.parse(null));
    }

    @Test
    void parse_blankInput_returnsNull() {
        assertNull(RecursiveJsonParser.parse(""));
        assertNull(RecursiveJsonParser.parse("   "));
    }

    @Test
    void parse_blankString() {
        assertNull(RecursiveJsonParser.parse("   "));
    }

    @Test
    void parse_whitespaceAround() {
        assertEquals(42L, RecursiveJsonParser.parse("  42  "));
        assertEquals(Boolean.TRUE, RecursiveJsonParser.parse("\n true \t"));
    }

    @Test
    void parse_invalidJson_returnsNull() {
        assertNull(RecursiveJsonParser.parse("not json"));
        assertNull(RecursiveJsonParser.parse("{invalid}"));
        assertNull(RecursiveJsonParser.parse("[1,2,"));
    }

    @Test
    void parse_trailingGarbage_returnsNull() {
        assertNull(RecursiveJsonParser.parse("42extra"));
    }

    @Test
    void parse_largeNumber() {
        Object result = RecursiveJsonParser.parse("9999999999999999999");
        // 超出 Long 范围应降级为 Double
        assertInstanceOf(Number.class, result);
    }

    @Test
    void serialize_null() {
        assertEquals("null", RecursiveJsonParser.serialize(null));
    }

    @Test
    void serialize_string() {
        assertEquals("\"hello\"", RecursiveJsonParser.serialize("hello"));
    }

    @Test
    void serialize_number() {
        assertEquals("42", RecursiveJsonParser.serialize(42L));
        assertEquals("3.14", RecursiveJsonParser.serialize(3.14));
    }

    @Test
    void serialize_boolean() {
        assertEquals("true", RecursiveJsonParser.serialize(true));
        assertEquals("false", RecursiveJsonParser.serialize(false));
    }

    @Test
    void serialize_list() {
        assertEquals("[1,2,3]", RecursiveJsonParser.serialize(Arrays.asList(1L, 2L, 3L)));
    }

    @Test
    void serialize_map() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("a", 1L);
        map.put("b", "x");
        assertEquals("{\"a\":1,\"b\":\"x\"}", RecursiveJsonParser.serialize(map));
    }

    @Test
    void serialize_escapesSpecialChars() {
        assertEquals("\"a\\nb\"", RecursiveJsonParser.serialize("a\nb"));
        assertEquals("\"a\\tb\"", RecursiveJsonParser.serialize("a\tb"));
        assertEquals("\"a\\\"b\"", RecursiveJsonParser.serialize("a\"b"));
        assertEquals("\"a\\\\b\"", RecursiveJsonParser.serialize("a\\b"));
    }

    @Test
    void roundTrip_simpleObject() {
        String json = "{\"name\":\"test\",\"count\":42,\"active\":true}";
        Object parsed = RecursiveJsonParser.parse(json);
        String serialized = RecursiveJsonParser.serialize(parsed);
        Object reparsed = RecursiveJsonParser.parse(serialized);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_nestedStructure() {
        String json = "{\"items\":[{\"id\":1,\"tags\":[\"a\",\"b\"]}],\"meta\":{}}";
        Object parsed = RecursiveJsonParser.parse(json);
        String serialized = RecursiveJsonParser.serialize(parsed);
        Object reparsed = RecursiveJsonParser.parse(serialized);
        assertEquals(parsed, reparsed);
    }

    @Test
    void roundTrip_toolCalls() {
        String json = "[{\"toolName\":\"queryOrderDB\",\"arguments\":{\"orderId\":\"ORD-001\",\"amount\":99.5}}]";
        Object parsed = RecursiveJsonParser.parse(json);
        String serialized = RecursiveJsonParser.serialize(parsed);
        Object reparsed = RecursiveJsonParser.parse(serialized);
        assertEquals(parsed, reparsed);
    }

    @Test
    void extractFieldPaths_flatObject() {
        Object parsed = RecursiveJsonParser.parse("{\"name\":\"test\",\"count\":42}");
        Set<String> paths = RecursiveJsonParser.extractFieldPaths(parsed);
        assertEquals(new HashSet<>(Arrays.asList("count", "name")), paths);
    }

    @Test
    void extractFieldPaths_nestedObject() {
        Object parsed = RecursiveJsonParser.parse("{\"a\":{\"b\":1}}");
        Set<String> paths = RecursiveJsonParser.extractFieldPaths(parsed);
        assertEquals(Collections.singleton("a.b"), paths);
    }

    @Test
    void extractFieldPaths_arrayOfObjects() {
        Object parsed = RecursiveJsonParser.parse("{\"items\":[{\"name\":\"x\"}]}");
        Set<String> paths = RecursiveJsonParser.extractFieldPaths(parsed);
        assertTrue(paths.contains("items[].name"));
    }

    @Test
    void extractFieldPaths_primitiveArray() {
        Object parsed = RecursiveJsonParser.parse("{\"tags\":[\"a\",\"b\"]}");
        Set<String> paths = RecursiveJsonParser.extractFieldPaths(parsed);
        assertTrue(paths.contains("tags[]"));
    }

    @Test
    void extractFieldPaths_complexStructure() {
        String json = "{\"toolParamTypes\":{\"orderId\":\"String\"},\"outputFields\":[\"orderId\",\"amount\"]}";
        Object parsed = RecursiveJsonParser.parse(json);
        Set<String> paths = RecursiveJsonParser.extractFieldPaths(parsed);
        assertTrue(paths.contains("toolParamTypes.orderId"));
        assertTrue(paths.contains("outputFields[]"));
    }

    @Test
    void extractFieldTypeMap_mixedTypes() {
        String json = "{\"name\":\"test\",\"count\":42,\"active\":true,\"data\":null}";
        Object parsed = RecursiveJsonParser.parse(json);
        Map<String, String> types = RecursiveJsonParser.extractFieldTypeMap(parsed);
        assertEquals("string", types.get("name"));
        assertEquals("number", types.get("count"));
        assertEquals("boolean", types.get("active"));
        assertEquals("null", types.get("data"));
    }

    @Test
    void extractFieldTypeMap_nestedWithArray() {
        String json = "{\"a\":{\"b\":1},\"items\":[{\"x\":true}]}";
        Object parsed = RecursiveJsonParser.parse(json);
        Map<String, String> types = RecursiveJsonParser.extractFieldTypeMap(parsed);
        assertEquals("number", types.get("a.b"));
        assertEquals("boolean", types.get("items[].x"));
    }

    @Test
    void extractFieldTypeMap_emptyInput() {
        assertTrue(RecursiveJsonParser.extractFieldTypeMap(null).isEmpty());
        assertTrue(RecursiveJsonParser.extractFieldTypeMap("string").isEmpty());
    }

    /**
     * 深度攻击防护：超限嵌套必须按解析失败契约安全退化为 null，
     * 绝不能以 StackOverflowError 逃逸——Error 不在 catch(Exception) 的拦截面内，
     * 一旦穿透会击穿 RegressionTestExecutor 的单条记录异常隔离。
     */
    @Test
    void parse_deepNestingBeyondLimit_degradesToNullWithoutStackOverflow() {
        StringBuilder json = new StringBuilder();
        int depth = 100_000;
        for (int i = 0; i < depth; i++) json.append('[');
        for (int i = 0; i < depth; i++) json.append(']');
        assertNull(RecursiveJsonParser.parse(json.toString()), "超过深度上限的嵌套必须安全返回 null，而不是抛 StackOverflowError");
    }

    @Test
    void parse_deepNestingWithinLimit_parsesCorrectly() {
        int depth = 64;
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < depth; i++) json.append("{\"a\":");
        json.append("\"leaf\"");
        for (int i = 0; i < depth; i++) json.append('}');

        Map<String, Object> parsed = (Map<String, Object>) RecursiveJsonParser.parse(json.toString());
        assertNotNull(parsed, "上限内的合法嵌套必须正常解析");
        for (int i = 0; i < depth - 1; i++) {
            parsed = (Map<String, Object>) parsed.get("a");
            assertNotNull(parsed);
        }
        assertEquals("leaf", parsed.get("a"));
    }

    @Test
    void extractFieldPaths_emptyContainers_produceExplicitEntries() {
        Map<String, Object> doc = (Map<String, Object>) RecursiveJsonParser.parse("{\"a\":{}, \"b\":[]}");
        assertEquals(new HashSet<>(Arrays.asList("a", "b[]")), RecursiveJsonParser.extractFieldPaths(doc), "空对象与空数组必须留下显式路径，不能退化成空集");
    }

    @Test
    void extractFieldTypeMap_emptyContainers_distinguishedFromNonEmpty() {
        Map<String, Object> doc = (Map<String, Object>) RecursiveJsonParser.parse("{\"a\":{}, \"b\":[], \"c\":[1,2], \"d\":{\"x\":1}}");
        Map<String, String> types = RecursiveJsonParser.extractFieldTypeMap(doc);
        assertEquals("empty-object", types.get("a"));
        assertEquals("empty-array", types.get("b[]"));
        assertEquals("array", types.get("c[]"));
        assertEquals("number", types.get("d.x"));
        assertFalse(types.containsKey("d"), "非空容器自身不产生类型条目");
    }

    @Test
    void extractPaths_structureDiff_onEmptyContainerChange_isVisible() {
        // 历史缺陷探针：{"a":{}} 与 {"b":[]} 曾双双产出空路径集——结构差异完全不可见
        Map<String, Object> base = (Map<String, Object>) RecursiveJsonParser.parse("{\"a\":{}}");
        Map<String, Object> current = (Map<String, Object>) RecursiveJsonParser.parse("{\"b\":[]}");
        Set<String> basePaths = RecursiveJsonParser.extractFieldPaths(base);
        Set<String> currentPaths = RecursiveJsonParser.extractFieldPaths(current);
        assertNotEquals(basePaths, currentPaths, "空容器差异必须反映在路径集上");
    }

    @Test
    void toJson_nonFiniteDouble_writesNullInsteadOfInvalidLiteral() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("x", Double.NaN);
        doc.put("y", Double.NEGATIVE_INFINITY);
        String json = RecursiveJsonParser.serialize(doc);
        assertFalse(json.contains("NaN"), "JSON 无 NaN 字面量: " + json);
        assertFalse(json.contains("Infinity"), "JSON 无 Infinity 字面量: " + json);
        Object roundTripped = RecursiveJsonParser.parse(json);
        assertNotNull(roundTripped, "非有限值写 null 后必须是合法 JSON");
    }
}
