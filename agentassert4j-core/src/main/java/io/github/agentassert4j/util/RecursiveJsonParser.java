package io.github.agentassert4j.util;

import java.util.*;

/**
 * 递归下降 JSON 解析器，RFC 8259 兼容。
 * 零外部依赖，纯 java.base 实现。
 *
 * <p>解析规则：
 * <ul>
 *   <li>object  → LinkedHashMap&lt;String, Object&gt;</li>
 *   <li>array   → ArrayList&lt;Object&gt;</li>
 *   <li>string  → String</li>
 *   <li>number  → Long（整数）或 Double（小数）</li>
 *   <li>boolean → Boolean</li>
 *   <li>null    → null</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class RecursiveJsonParser {

    /**
     * 嵌套深度上限。递归下降没有限深时，恶意或异常的超深嵌套输入会耗尽
     * 调用栈抛出 StackOverflowError——Error 不在 catch(Exception) 的拦截面内，
     * 会穿透所有按"解析失败退化"设计的防御层。合法 JSON 业务结构远达不到此深度。
     */
    private static final int MAX_DEPTH = 128;

    private RecursiveJsonParser() {
    }

    /**
     * 解析 JSON 字符串，返回标准 Java 类型。
     * 输入 null / 空白 / 解析失败均返回 null（退化不中断）。
     */
    public static Object parse(String json) {
        if (TextUtil.isBlank(json)) {
            return null;
        }
        try {
            Parser p = new Parser(json.trim());
            Object result = p.parseValue();
            p.skipWhitespace();
            if (p.pos < p.len) {
                return null; // 尾部有垃圾字符
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将标准 Java 对象序列化为 JSON 字符串。
     * 支持 Map、List、String、Number、Boolean、null。
     */
    public static String serialize(Object obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, obj);
        return sb.toString();
    }

    /**
     * 递归提取 JSON 对象中所有字段路径（点分表示法）。
     * <pre>
     * {"a":{"b":1}}             → ["a.b"]
     * {"items":[{"name":"x"}]} → ["items[].name"]
     * </pre>
     */
    public static Set<String> extractFieldPaths(Object json) {
        TreeSet<String> paths = new TreeSet<>();
        collectPaths(json, "", paths);
        return paths;
    }

    /**
     * 推断每个字段路径的值类型。
     * 返回 Map: path → 类型名（"string","number","boolean","null","object","array"，
     * 空容器为 "empty-object"/"empty-array"）。
     */
    public static Map<String, String> extractFieldTypeMap(Object json) {
        Map<String, String> typeMap = new LinkedHashMap<>();
        collectTypes(json, "", typeMap);
        return typeMap;
    }

    private static void collectPaths(Object node, String prefix, Set<String> paths) {
        if (node instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (val instanceof Map || val instanceof List) {
                    if (isEmptyContainer(val)) {
                        // 空容器贡献显式路径条目：否则 {"a":{}} 与 {"b":[]} 都是空集，
                        // 结构维把「字段存在但为空」误判为「无结构」
                        paths.add(containerPath(path, val));
                    } else {
                        collectPaths(val, path, paths);
                    }
                } else {
                    paths.add(path);
                }
            }
        } else if (node instanceof List) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) node;
            String arrayPath = prefix + "[]";
            boolean hasComplex = false;
            for (Object item : list) {
                if (item instanceof Map || item instanceof List) {
                    hasComplex = true;
                    if (isEmptyContainer(item)) {
                        paths.add(containerPath(arrayPath, item));
                    } else {
                        collectPaths(item, arrayPath, paths);
                    }
                }
            }
            if (!hasComplex) {
                // 基本类型数组与空数组都以 arrayPath 为路径（类型维区分 empty-array/array）
                paths.add(arrayPath);
            }
        }
    }

    private static void collectTypes(Object node, String prefix, Map<String, String> typeMap) {
        if (node instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                String path = prefix.isEmpty() ? key : prefix + "." + key;

                if (val instanceof Map) {
                    if (((Map<?, ?>) val).isEmpty()) {
                        typeMap.put(path, "empty-object");
                    } else {
                        collectTypes(val, path, typeMap);
                    }
                } else if (val instanceof List) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) val;
                    String arrayPath = path + "[]";
                    if (list.isEmpty()) {
                        typeMap.put(arrayPath, "empty-array");
                    } else {
                        collectTypesInto(list, arrayPath, typeMap);
                    }
                } else {
                    typeMap.put(path, typeName(val));
                }
            }
        } else if (node instanceof List) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) node;
            String arrayPath = prefix + "[]";
            if (list.isEmpty()) {
                typeMap.put(arrayPath, "empty-array");
            } else {
                collectTypesInto(list, arrayPath, typeMap);
            }
        }
    }

    /**
     * 非空数组的类型收集：对象/嵌套数组元素递归展开，基本类型数组记为 "array"。
     */
    private static void collectTypesInto(List<Object> list, String arrayPath, Map<String, String> typeMap) {
        boolean hasComplex = false;
        for (Object item : list) {
            if (item instanceof Map || item instanceof List) {
                hasComplex = true;
                collectTypes(item, arrayPath, typeMap);
            }
        }
        if (!hasComplex) {
            typeMap.put(arrayPath, "array");
        }
    }

    private static boolean isEmptyContainer(Object value) {
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof List) {
            return ((List<?>) value).isEmpty();
        }
        return false;
    }

    private static String containerPath(String path, Object container) {
        return container instanceof List ? path + "[]" : path;
    }

    private static String typeName(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "string";
        if (val instanceof Boolean) return "boolean";
        if (val instanceof Long || val instanceof Double) return "number";
        if (val instanceof Map) return "object";
        if (val instanceof List) return "array";
        return "unknown";
    }

    private static void writeValue(StringBuilder sb, Object obj) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof Map) {
            writeObject(sb, (Map<?, ?>) obj);
        } else if (obj instanceof List) {
            writeArray(sb, (List<?>) obj);
        } else if (obj instanceof String) {
            writeString(sb, (String) obj);
        } else if (obj instanceof Boolean || obj instanceof Long) {
            sb.append(obj);
        } else if (obj instanceof Double) {
            // JSON 无 NaN/Infinity 字面量——非有限值写 null（与主流序列化器一致），避免产出非法 JSON
            Double d = (Double) obj;
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else if (obj instanceof Number) {
            // 兜底：其他 Number 子类
            Number n = (Number) obj;
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.floor(d)) {
                sb.append(n.longValue());
            } else {
                sb.append(d);
            }
        } else {
            writeString(sb, obj.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String input;
        final int len;
        int pos;
        int depth;

        Parser(String input) {
            this.input = input;
            this.len = input.length();
            this.pos = 0;
            this.depth = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= len) throw new ParseException("Unexpected end");
            char c = input.charAt(pos);
            switch (c) {
                case '{':
                case '[':
                    if (++depth > MAX_DEPTH) {
                        throw new ParseException("JSON nesting exceeds " + MAX_DEPTH + " levels");
                    }
                    break;
                default:
                    break;
            }
            return parseDispatch(c);
        }

        private Object parseDispatch(char c) {
            switch (c) {
                case '{':
                    try {
                        return parseObject();
                    } finally {
                        depth--;
                    }
                case '[':
                    try {
                        return parseArray();
                    } finally {
                        depth--;
                    }
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new ParseException("Unexpected char: " + c);
            }
        }

        // ------ object ------

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < len && input.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < len && input.charAt(pos) == ',') {
                    pos++;
                } else {
                    break;
                }
            }
            skipWhitespace();
            expect('}');
            return map;
        }

        // ------ array ------

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < len && input.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < len && input.charAt(pos) == ',') {
                    pos++;
                } else {
                    break;
                }
            }
            skipWhitespace();
            expect(']');
            return list;
        }

        // ------ string ------

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) throw new ParseException("Unexpected end in string escape");
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > len) throw new ParseException("Invalid unicode escape");
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new ParseException("Invalid unicode escape: \\u" + hex);
                            }
                            break;
                        default:
                            throw new ParseException("Invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("Unterminated string");
        }

        // ------ number ------

        Number parseNumber() {
            int start = pos;
            // 可选负号
            if (pos < len && input.charAt(pos) == '-') pos++;
            // 整数部分
            if (pos < len && input.charAt(pos) == '0') {
                pos++;
            } else {
                readDigits();
            }
            boolean isFloat = false;
            // 小数部分
            if (pos < len && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                readDigits();
            }
            // 指数部分
            if (pos < len && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                readDigits();
            }
            String numStr = input.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(numStr);
            } else {
                try {
                    return Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(numStr);
                }
            }
        }

        // ------ boolean ------

        Boolean parseBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new ParseException("Invalid boolean at pos " + pos);
        }

        // ------ null ------

        Object parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new ParseException("Invalid null at pos " + pos);
        }

        // ------ helpers ------

        void skipWhitespace() {
            while (pos < len) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        void expect(char ch) {
            if (pos >= len || input.charAt(pos) != ch) {
                throw new ParseException("Expected '" + ch + "' at pos " + pos);
            }
            pos++;
        }

        void readDigits() {
            if (pos >= len || input.charAt(pos) < '0' || input.charAt(pos) > '9') {
                throw new ParseException("Expected digit at pos " + pos);
            }
            while (pos < len && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
        }
    }

    private static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }
}
