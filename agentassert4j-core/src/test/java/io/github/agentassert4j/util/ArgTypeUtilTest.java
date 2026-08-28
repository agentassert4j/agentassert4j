package io.github.agentassert4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数类型派生词表测试 — 捕获侧与重放侧共用的类型映射契约。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class ArgTypeUtilTest {

    @Test
    @DisplayName("六类值形态各归其位，键统一小写")
    void derive_matchesVocabulary() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("order_id", "SO-1");
        args.put("amount", 12.5);
        args.put("count", 3L);
        args.put("urgent", Boolean.TRUE);
        args.put("meta", new LinkedHashMap<String, Object>());
        args.put("tags", new ArrayList<String>());
        args.put("missing", null);

        Map<String, String> types = ArgTypeUtil.derive(args);

        assertEquals("string", types.get("order_id"));
        assertEquals("number", types.get("amount"));
        assertEquals("number", types.get("count"));
        assertEquals("boolean", types.get("urgent"));
        assertEquals("object", types.get("meta"));
        assertEquals("array", types.get("tags"));
        assertEquals("null", types.get("missing"));
    }

    @Test
    @DisplayName("键大写入参派生后小写化")
    void derive_lowercasesKeys() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("OrderId", "SO-1");

        Map<String, String> types = ArgTypeUtil.derive(args);

        assertEquals("string", types.get("orderid"));
    }

    @Test
    @DisplayName("null 或空入参返回空 Map")
    void derive_nullOrEmpty_returnsEmptyMap() {
        assertTrue(ArgTypeUtil.derive(null).isEmpty());
        assertTrue(ArgTypeUtil.derive(new LinkedHashMap<String, Object>()).isEmpty());
    }

    @Test
    @DisplayName("土耳其 locale 下键小写化不发生 i→ı 替换（跨环境分组一致）")
    void derive_turkishLocale_keysMatchRootLowercasing() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Map<String, String> types = ArgTypeUtil.derive(Collections.singletonMap((String) "userID", (Object) "SO-1"));
            assertTrue(types.containsKey("userid"), "tr-TR 下必须与 Locale.ROOT 口径一致，实际键集: " + types.keySet());
        } finally {
            Locale.setDefault(original);
        }
    }
}
