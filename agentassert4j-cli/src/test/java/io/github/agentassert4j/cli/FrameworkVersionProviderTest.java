package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 框架版本单源化契约：版本值经构建期资源过滤来自根 POM，禁止回到手写字面量。
 *
 * @author axy-yxa
 * @since 2026-09-19
 */
class FrameworkVersionProviderTest {

    @Test
    @DisplayName("版本值来自构建期过滤：形如 x.y.z（过滤失效会漏出占位符或缺资源）")
    void frameworkVersion_isSemverFromPom() {
        assertTrue(AgentAssert4jCli.FRAMEWORK_VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), "FRAMEWORK_VERSION 必须形如 x.y.z[后缀]，实际: " + AgentAssert4jCli.FRAMEWORK_VERSION);
        assertFalse(AgentAssert4jCli.FRAMEWORK_VERSION.contains("${"), "resource filtering 未生效");
        assertNotEquals("unknown", AgentAssert4jCli.FRAMEWORK_VERSION, "版本资源缺失（构件未按构建配置打包）");
    }

    @Test
    @DisplayName("versionProvider 输出带产品名前缀")
    void providerOutput_hasProductPrefix() throws Exception {
        String[] lines = new FrameworkVersionProvider().getVersion();
        assertEquals(1, lines.length);
        assertEquals("AgentAssert4j " + AgentAssert4jCli.FRAMEWORK_VERSION, lines[0]);
    }
}
