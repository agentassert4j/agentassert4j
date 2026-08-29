package io.github.agentassert4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegexPattern 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class RegexPatternTest {

    @Nested
    @DisplayName("匹配判定")
    class Matching {

        @Test
        @DisplayName("find 语义——部分匹配即命中")
        void matches_partialHit() {
            RegexPattern p = new RegexPattern("\\d{4}-\\d{2}-\\d{2}", "日期格式");

            assertTrue(p.matches("下单时间 2026-08-27，共 1 件"));
        }

        @Test
        @DisplayName("无匹配返回 false")
        void matches_noHit() {
            RegexPattern p = new RegexPattern("\\d{4}-\\d{2}-\\d{2}", "日期格式");

            assertFalse(p.matches("没有日期的文本"));
        }

        @Test
        @DisplayName("null 文本或 null 模式返回 false（防御性）")
        void matches_nullInputs() {
            RegexPattern p = new RegexPattern("a", null);

            assertFalse(p.matches(null));
            assertFalse(new RegexPattern(null, "").matches("text"));
        }
    }

    @Nested
    @DisplayName("非法正则的确定性退化")
    class InvalidPattern {

        @Test
        @DisplayName("非法正则按不匹配处理，不抛异常")
        void invalidPattern_failsClosed() {
            RegexPattern p = new RegexPattern("([unclosed", "用户笔误");

            assertFalse(p.matches("any text"));
        }
    }

    @Nested
    @DisplayName("值等价（指纹值比较的传递依赖）")
    class ValueEquality {

        @Test
        @DisplayName("同模式同描述相等且哈希一致")
        void equal_instancesMatch() {
            RegexPattern a = new RegexPattern("\\d+", "数字");
            RegexPattern b = new RegexPattern("\\d+", "数字");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("模式或描述不同即不等")
        void different_fieldsBreakEquality() {
            RegexPattern base = new RegexPattern("\\d+", "数字");

            assertNotEquals(base, new RegexPattern("\\d{2}", "数字"));
            assertNotEquals(base, new RegexPattern("\\d+", "位数"));
        }

        @Test
        @DisplayName("与 null 及异类比较返回 false")
        void equals_nullAndForeignType() {
            assertNotEquals(new RegexPattern("\\d+", "数字"), null);
            assertNotEquals(new RegexPattern("\\d+", "数字"), "\\d+");
        }
    }
}
