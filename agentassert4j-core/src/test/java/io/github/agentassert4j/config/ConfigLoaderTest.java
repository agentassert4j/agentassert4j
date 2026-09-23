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
    @DisplayName("directoryOf 主配置目录提取（平台无关分隔符）")
    class DirectoryOf {

        @Test
        @DisplayName("反斜杠路径取目录（Windows 形态，回退依赖此形态）")
        void windowsBackslashPath() {
            assertEquals("C:\\Users\\x", ConfigLoader.directoryOf(new StringBuilder("C:\\Users\\x\\agentassert4j.json")));
        }

        @Test
        @DisplayName("正斜杠路径取目录")
        void unixForwardSlashPath() {
            assertEquals("/etc/agentassert", ConfigLoader.directoryOf(new StringBuilder("/etc/agentassert/agentassert4j.json")));
        }

        @Test
        @DisplayName("classpath 标识与裸文件名返回 null")
        void nonFileSystemPaths() {
            assertNull(ConfigLoader.directoryOf(new StringBuilder("classpath:config/agentassert4j.json")));
            assertNull(ConfigLoader.directoryOf(new StringBuilder("agentassert4j.json")));
        }
    }

    @Nested
    @DisplayName("expandHome 用户主目录展开（CLI 与 starter storage url 的共用契约）")
    class ExpandHome {

        @Test
        @DisplayName("~/ 与 ~\\ 前缀展开为用户主目录")
        void tildeWithSeparatorExpands() {
            String home = System.getProperty("user.home");
            assertEquals(home + "/rest", ConfigLoader.expandHome("~/rest"));
            assertEquals(home + "\\rest", ConfigLoader.expandHome("~\\rest"));
        }

        @Test
        @DisplayName("裸 ~ 展开为主目录；~user 形态与普通路径原样保留；null 直通")
        void otherFormsPreserved() {
            assertEquals(System.getProperty("user.home"), ConfigLoader.expandHome("~"));
            assertEquals("~other/x", ConfigLoader.expandHome("~other/x"));
            assertEquals("/abs/path", ConfigLoader.expandHome("/abs/path"));
            assertNull(ConfigLoader.expandHome(null));
        }
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
            String json = "{\"llm\": {\"apiKey\": \"${AGENTASSERT_NONEXISTENT_KEY}\", \"model\": \"gpt-4o\"}}";
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
                Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
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
        @DisplayName("显式路径不可读 → 抛 IllegalStateException")
        void explicitPathUnreadable_failsFast() {
            // 旧断言锁定「显式路径失败静默换源」行为——该行为会让用户以为配置已生效，
            // 随显式路径 fail-fast 契约改写为必须显式报错
            System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, "/nonexistent/path.json");
            IllegalStateException ex = assertThrows(IllegalStateException.class, ConfigLoader::loadAgentAssert4jConfig);
            assertTrue(ex.getMessage().contains("/nonexistent/path.json"), "错误信息须指向显式路径: " + ex.getMessage());
        }

        @Test
        @DisplayName("系统属性指向有效文件 → 加载该文件")
        void systemPropertyFile_loaded() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-test", ".json");
            try {
                String content = "{\"storage\": {\"url\": \"/from/system/property.db\"}}";
                Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());

                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertEquals("/from/system/property.db", config.getStorage().getUrl());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("环境变量在加载时被替换")
        void envVarsResolvedOnLoad() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-test", ".json");
            try {
                String content = "{\"llm\": {\"apiKey\": \"${AGENTASSERT_NONEXISTENT_KEY}\", \"model\": \"gpt-4o\"}}";
                Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
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
        @DisplayName("规则配置显式路径不可读 → 抛 IllegalStateException（同一 fail-fast 契约）")
        void explicitRulesPathUnreadable_failsFast() {
            // 同主配置：显式指定的规则路径读不到必须显式报错，不静默退化为空配置
            System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, "/nonexistent/rules.json");
            assertThrows(IllegalStateException.class, ConfigLoader::loadRulesConfig);
        }

        @Test
        @DisplayName("有效规则文件 → 正确解析")
        void validRulesFile() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-rules", ".json");
            try {
                String content = "{\"invocations\":{\"queryOrder\":{\"requiredKeywords\":[\"订单号\"]}}}";
                Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.RULES_PATH_PROPERTY, tempFile.toString());

                InvocationRulesConfig config = ConfigLoader.loadRulesConfig();
                assertTrue(config.hasRules());
                assertEquals(1, config.getDeclaredInvocationIds().size());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Nested
    @DisplayName("loadAgentAssert4jConfig 解析失败根因披露")
    class UnparsableDisclosure {

        @Test
        @DisplayName("语法垃圾 → 默认值 + configNotes 携 unparsable 根因")
        void garbageConfig_notesCarryRootCause() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-broken", ".json");
            try {
                Files.write(tempFile, "not json at all".getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());
                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertNotNull(config);
                assertTrue(config.getConfigNotes().stream().anyMatch(n -> n.startsWith("config file ") && n.contains("is unparsable")),
                        "退化必须带 unparsable 根因而非静默: " + config.getConfigNotes());
            } finally {
                System.clearProperty(ConfigLoader.CONFIG_PATH_PROPERTY);
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("深度超限 → 根因点名 nesting")
        void deepNesting_namesNesting() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-deep", ".json");
            try {
                StringBuilder deep = new StringBuilder();
                for (int i = 0; i < 130; i++) {
                    deep.append("{\"a\":");
                }
                deep.append('1');
                for (int i = 0; i < 130; i++) {
                    deep.append('}');
                }
                Files.write(tempFile, deep.toString().getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());
                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertTrue(config.getConfigNotes().stream().anyMatch(n -> n.contains("nesting exceeds 128")),
                        "深度超限根因必须就地可辨: " + config.getConfigNotes());
            } finally {
                System.clearProperty(ConfigLoader.CONFIG_PATH_PROPERTY);
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("根对象为合法 JSON 非 object → 同路径披露")
        void nonObjectRoot_notesCarryRootCause() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-array", ".json");
            try {
                Files.write(tempFile, "[1,2,3]".getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());
                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertTrue(config.getConfigNotes().stream().anyMatch(n -> n.contains("config root is not a JSON object")),
                        "根对象非 object 必须披露: " + config.getConfigNotes());
            } finally {
                System.clearProperty(ConfigLoader.CONFIG_PATH_PROPERTY);
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("合法配置 → 不产生 config file 根因 note")
        void validConfig_noRootCauseNote() throws IOException {
            Path tempFile = Files.createTempFile("agentassert4j-good", ".json");
            try {
                Files.write(tempFile, "{\"llm\":{\"apiKey\":\"k\"}}".getBytes(StandardCharsets.UTF_8));
                System.setProperty(ConfigLoader.CONFIG_PATH_PROPERTY, tempFile.toString());
                AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
                assertFalse(config.getConfigNotes().stream().anyMatch(n -> n.startsWith("config file ")),
                        "合法配置不得产生解析失败 note: " + config.getConfigNotes());
            } finally {
                System.clearProperty(ConfigLoader.CONFIG_PATH_PROPERTY);
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
