package io.github.agentassert4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentAssert4jConfig 单元测试 — 主配置模型。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class AgentAssert4jConfigTest {

    @Nested
    @DisplayName("默认值完整性")
    class Defaults {

        @Test
        @DisplayName("defaults() 返回安全默认配置")
        void defaults_allFieldsSet() {
            AgentAssert4jConfig config = AgentAssert4jConfig.defaults();

            assertNotNull(config.getStorage());
            assertNotNull(config.getRecorder());
            assertNotNull(config.getRegression());
            assertNotNull(config.getLlm());
            assertNotNull(config.getTools());
        }

        @Test
        @DisplayName("Storage 默认 SQLite 单文件路径")
        void storageDefaults() {
            AgentAssert4jConfig.StorageConfig s = AgentAssert4jConfig.defaults().getStorage();
            assertNotNull(s.getUrl());
        }

        @Test
        @DisplayName("Recorder 默认 batch=100, flush=5000")
        void recorderDefaults() {
            AgentAssert4jConfig.RecorderConfig r = AgentAssert4jConfig.defaults().getRecorder();
            assertEquals(100, r.getBatchSize());
            assertEquals(5000, r.getFlushIntervalMs());
        }

        @Test
        @DisplayName("Llm 默认 endpoint + model + timeout")
        void llmDefaults() {
            AgentAssert4jConfig.LlmConfig llm = AgentAssert4jConfig.defaults().getLlm();
            assertEquals("https://api.openai.com", llm.getEndpoint());
            assertEquals("gpt-4o", llm.getModel());
            assertEquals(30000, llm.getTimeoutMs());
            assertNull(llm.getApiKey());
            assertNull(llm.getExtraBody(), "默认无方言扩展");
        }

        @Test
        @DisplayName("Llm extraBody 方言片段可从 JSON 装载并逐字保留")
        void llmExtraBody() {
            String fragment = "\"thinking\":{\"type\":\"disabled\"}";
            String embedded = fragment.replace("\\", "\\\\").replace("\"", "\\\"");
            String json = "{\"llm\":{\"apiKey\":\"sk-test\",\"model\":\"deepseek-v4-flash\",\"extraBody\":\"" + embedded + "\"}}";

            AgentAssert4jConfig.LlmConfig llm = AgentAssert4jConfig.fromJson(json).getLlm();

            assertEquals(fragment, llm.getExtraBody(), "方言扩展片段必须逐字往返保留，客户端按原样注入请求体");
        }

        @Test
        @DisplayName("Regression 默认空可忽略字段")
        void regressionDefaults() {
            AgentAssert4jConfig.RegressionConfig r = AgentAssert4jConfig.defaults().getRegression();
            assertNotNull(r.getIgnorableFields());
            assertTrue(r.getIgnorableFields().isEmpty());
        }

        @Test
        @DisplayName("Tools 默认空排除列表")
        void toolsDefaults() {
            AgentAssert4jConfig.ToolsConfig t = AgentAssert4jConfig.defaults().getTools();
            assertNotNull(t.getExcludeFromGraph());
            assertTrue(t.getExcludeFromGraph().isEmpty());
        }
    }

    @Nested
    @DisplayName("JSON 解析")
    class FromJson {

        @Test
        @DisplayName("完整 JSON 解析")
        void fullJson() {
            String json = "{\n" + "  \"storage\": {\"url\": \"/data/agentassert4j.db\"},\n" + "  \"recorder\": {\"batchSize\": 200, \"flushIntervalMs\": 10000},\n" + "  \"regression\": {\"ignorableFields\": [\"debugInfo\", \"timestamp\"]},\n" + "  \"llm\": {\"apiKey\": \"sk-test\", \"endpoint\": \"https://api.deepseek.com\", \"model\": \"deepseek-chat\", \"timeoutMs\": 60000},\n" + "  \"tools\": {\"excludeFromGraph\": [\"read_file\", \"bash\"]}\n" + "}";

            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);

            assertEquals("/data/agentassert4j.db", config.getStorage().getUrl());
            assertEquals(200, config.getRecorder().getBatchSize());
            assertEquals(10000, config.getRecorder().getFlushIntervalMs());
            assertEquals(2, config.getRegression().getIgnorableFields().size());
            assertEquals("sk-test", config.getLlm().getApiKey());
            assertEquals("https://api.deepseek.com", config.getLlm().getEndpoint());
            assertEquals("deepseek-chat", config.getLlm().getModel());
            assertEquals(60000, config.getLlm().getTimeoutMs());
            assertEquals(2, config.getTools().getExcludeFromGraph().size());
        }

        @Test
        @DisplayName("部分 JSON — 缺失字段使用默认值")
        void partialJson_usesDefaults() {
            String json = "{\"storage\": {\"url\": \"/custom/path.db\"}}";

            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);

            assertEquals("/custom/path.db", config.getStorage().getUrl());
            // 其他字段使用默认值
            assertEquals(100, config.getRecorder().getBatchSize());
            assertEquals("gpt-4o", config.getLlm().getModel());
        }

        @Test
        @DisplayName("null 输入 → 默认配置")
        void nullInput_defaults() {
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(null);
            assertNotNull(config.getStorage().getUrl());
        }

        @Test
        @DisplayName("空字符串 → 默认配置")
        void blankInput_defaults() {
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson("   ");
            assertNotNull(config.getStorage().getUrl());
        }

        @Test
        @DisplayName("非 JSON 输入 → 默认配置（退化不中断）")
        void invalidJson_defaults() {
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson("not json at all");
            assertNotNull(config.getStorage().getUrl());
        }

        @Test
        @DisplayName("数字字符串的 int 字段解析")
        void intField_fromString() {
            String json = "{\"recorder\": {\"batchSize\": \"50\"}}";
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);
            assertEquals(50, config.getRecorder().getBatchSize());
        }

        @Test
        @DisplayName("非数字字符串的 int 字段退化为默认值")
        void intField_invalidString_defaults() {
            String json = "{\"recorder\": {\"batchSize\": \"abc\"}}";
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);
            assertEquals(100, config.getRecorder().getBatchSize());
        }
    }

    @Nested
    @DisplayName("Setter 空值安全")
    class SetterSafety {

        @Test
        @DisplayName("RegressionConfig.setIgnorableFields(null) → 空列表")
        void ignorableFields_nullSafe() {
            AgentAssert4jConfig.RegressionConfig r = new AgentAssert4jConfig.RegressionConfig();
            r.setIgnorableFields(null);
            assertNotNull(r.getIgnorableFields());
            assertTrue(r.getIgnorableFields().isEmpty());
        }

        @Test
        @DisplayName("ToolsConfig.setExcludeFromGraph(null) → 空列表")
        void excludeFromGraph_nullSafe() {
            AgentAssert4jConfig.ToolsConfig t = new AgentAssert4jConfig.ToolsConfig();
            t.setExcludeFromGraph(null);
            assertNotNull(t.getExcludeFromGraph());
            assertTrue(t.getExcludeFromGraph().isEmpty());
        }
    }
}
