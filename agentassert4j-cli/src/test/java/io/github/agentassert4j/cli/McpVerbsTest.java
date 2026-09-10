package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpVerbs 通道化词表测试 — CLI 命令指称映射为 MCP 工具名，CLI 专属旗标子句摘除。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class McpVerbsTest {

    @Test
    @DisplayName("CLI 命令指称映射为工具名形态")
    void mapsCommandFormsToToolForms() {
        String mapped = McpVerbs.channelize("Run `agentassert4j baseline` locally to review, then retry.");
        assertTrue(mapped.contains("the `establish` tool"), mapped);
        assertFalse(mapped.contains("agentassert4j baseline"), mapped);

        assertEquals("See the `report` tool output.", McpVerbs.channelize("See `agentassert4j status` output."));
        assertEquals("Run the `check` tool after a template change.", McpVerbs.channelize("Run `agentassert4j replay` after a template change."));
    }

    @Test
    @DisplayName("--ci 逃生舱子句摘除（MCP 面恒 ci 语义不可达）")
    void stripsCliOnlyClause() {
        String mapped = McpVerbs.channelize("Run the hint, or drop --ci to auto-establish.");
        assertFalse(mapped.contains("--ci"), mapped);
        assertTrue(mapped.contains("Run the hint."), mapped);
    }

    @Test
    @DisplayName("反引号内带参数的命令整段改写（参数保留、无嵌套反引号）")
    void backtickedWithArgs_rewrittenCleanly() {
        String mapped = McpVerbs.channelize("Accept via `agentassert4j accept --invocation <prefix>`, or reject.");
        assertTrue(mapped.contains("the `accept` tool --invocation <prefix>"), mapped);
        assertFalse(mapped.contains("` + `") || mapped.contains("`the"), mapped);
    }

    @Test
    @DisplayName("rollback 已有 MCP 等价物，反引号带参数整段改写")
    void rollback_backtickedWithArgs_rewritten() {
        String mapped = McpVerbs.channelize("Recover via `agentassert4j rollback --version v1`.");
        assertTrue(mapped.contains("the `rollback` tool --version v1"), mapped);
        assertFalse(mapped.contains("agentassert4j rollback"), mapped);
    }

    @Test
    @DisplayName("null 与空串直通")
    void nullAndEmptyPassThrough() {
        assertNull(McpVerbs.channelize(null));
        assertEquals("", McpVerbs.channelize(""));
    }
}
