package io.github.agentassert4j.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLoader 单元测试 — 配置加载器。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class ConfigLoaderTest {

    @AfterEach
    void cleanup() {
        // 清理测试期间可能设置的系统属性
        System.clearProperty(ConfigLoader.CONFIG_PATH_PROPERTY);
        System.clearProperty(ConfigLoader.RULES_PATH_PROPERTY);
    }

    @Nested
    @DisplayName("resolveEnvVars 环境变量替换")
    class ResolveEnvVars {

        @Test
        @DisplayName("null 输入 → null")
        void nullInput() {
            assertNull(ConfigLoader.resolveEnvVars(null));
        }

        @Test
        @DisplayName("无环境变量引用 → 原样返回")
        void noEnvVars() {
            assertEquals("hello world", ConfigLoader.resolveEnvVars("hello world"));
        }

        @Test
        @DisplayName("PATH 环境变量替换")
        void pathEnvVar() {
            String path = System.getenv("PATH");
            if (path != null) {
                String result = ConfigLoader.resolveEnvVars("${PATH}");
                assertEquals(path, result);
            }
        }

        @Test
        @DisplayName("未设置的环境变量 → 空字符串")
        void undefinedEnvVar_empty() {
            String result = ConfigLoader.resolveEnvVars("key=${AGENTASSERT_NONEXISTENT_VAR_12345}");
            assertEquals("key=", result);
        }

        @Test
        @DisplayName("混合文本中的环境变量替换")
        void mixedText() {
            String path = System.getenv("PATH");
            if (path != null) {
                String result = ConfigLoader.resolveEnvVars("prefix-${PATH}-suffix");
                assertEquals("prefix-" + path + "-suffix", result);
            }
        }

        @Test
        @DisplayName("多个环境变量引用")
        void multipleEnvVars() {
            String result = ConfigLoader.resolveEnvVars("a=${NONEXISTENT_A}_b=${NONEXISTENT_B}");
            assertEquals("a=_b=", result);
        }

        @Test
        @DisplayName("JSON 内的环境变量替换")
        void jsonWithEnvVars() {
            String json = """
                    {"llm": {"apiKey": "${AGENTASSERT_NONEXISTENT_KEY}", "model": "gpt-4o"}}
                    """;
            String result = ConfigLoader.resolveEnvVars(json);
            assertEquals("gpt-4o", result.contains("gpt-4o") ? "gpt-4o" : "fail");
            assertTrue(result.contains("\"apiKey\": \"\""));
        }
    }

    @Nested
    @DisplayName("loadFromFile 文件加载")
    class LoadFromFile {

        @Test
        @DisplayName("null 路径 → null")
        void nullPath() {
            assertNull(ConfigLoader.loadFromFile(null));
        }

        @Test
        @DisplayName("不存在的文件 → null")
        void nonexistentFile() {
            assertNull(ConfigLoader.loadFromFile("/nonexistent/path/test.json"));
        }

        @Test
        @DisplayName("存在的文件 → 内容读取")
        void existingFile() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-test", ".json");
            try {
                String content = "{\"test\": true}";
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                String loaded = ConfigLoader.loadFromFile(tempFile.toString());
                assertEquals(content, loaded);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Nested
    @DisplayName("loadFromClasspath classpath 加载")
    class LoadFromClasspath {

        @Test
        @DisplayName("不存在的资源 → null")
        void nonexistentResource() {
            assertNull(ConfigLoader.loadFromClasspath("nonexistent-config-abc.json"));
        }
    }

    @Nested
    @DisplayName("配置加载优先级")
    class LoadPriority {

        @Test
        @DisplayName("无配置文件 → 返回默认配置")
        void noConfigFile_defaults() {
            System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, "/nonexistent/path.json");
            AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
            assertEquals("sqlite", config.getStorage().getType());
        }

        @Test
        @DisplayName("系统属性指向有效文件 → 加载该文件")
        void systemPropertyFile_loaded() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-test", ".json");
            try {
                String content = """
                        {"storage": {"type": "mysql", "url": "jdbc:mysql://test/db"}}
                        """;
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());

                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertEquals("mysql", config.getStorage().getType());
                assertEquals("jdbc:mysql://test/db", config.getStorage().getUrl());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("环境变量在加载时被替换")
        void envVarsResolvedOnLoad() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-test", ".json");
            try {
                String content = """
                        {"llm": {"apiKey": "${AGENTASSERT_NONEXISTENT_KEY}", "model": "gpt-4o"}}
                        """;
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());

                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertEquals("", config.getLlm().getApiKey());
                assertEquals("gpt-4o", config.getLlm().getModel());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Nested
    @DisplayName("规则配置加载")
    class LoadRules {

        @Test
        @DisplayName("无规则文件 → 空配置")
        void noRulesFile_empty() {
            System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, "/nonexistent/rules.json");
            SkillRulesConfig config = ConfigLoader.loadRulesConfig();
            assertFalse(config.hasRules());
        }

        @Test
        @DisplayName("有效规则文件 → 正确解析")
        void validRulesFile() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-rules", ".json");
            try {
                String content = """
                        {"skills":{"queryOrder":{"requiredKeywords":["订单号"]}}}
                        """;
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, tempFile.toString());

                SkillRulesConfig config = ConfigLoader.loadRulesConfig();
                assertTrue(config.hasRules());
                assertEquals(1, config.getDeclaredSkillIds().size());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
