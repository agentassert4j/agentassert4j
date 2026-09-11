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

    }

    @Nested
    @DisplayName("JSON 解析")
    class FromJson {

        @Test
        @DisplayName("完整 JSON 解析")
        void fullJson() {
            String json = "{\n" + "  \"storage\": {\"url\": \"/data/agentassert4j.db\"},\n" + "  \"recorder\": {\"batchSize\": 200, \"flushIntervalMs\": 10000},\n" + "  \"regression\": {\"ignorableFields\": [\"debugInfo\", \"timestamp\"]},\n" + "  \"llm\": {\"apiKey\": \"sk-test\", \"endpoint\": \"https://api.deepseek.com\", \"model\": \"deepseek-chat\", \"timeoutMs\": 60000}\n" + "}";

            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);

            assertEquals("/data/agentassert4j.db", config.getStorage().getUrl());
            assertEquals(200, config.getRecorder().getBatchSize());
            assertEquals(10000, config.getRecorder().getFlushIntervalMs());
            assertEquals(2, config.getRegression().getIgnorableFields().size());
            assertEquals("sk-test", config.getLlm().getApiKey());
            assertEquals("https://api.deepseek.com", config.getLlm().getEndpoint());
            assertEquals("deepseek-chat", config.getLlm().getModel());
            assertEquals(60000, config.getLlm().getTimeoutMs());
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

    }

    @Test
    void llmTemperature_threeStates() throws Exception {
        String apiKeyPair = "\"apiKey\":\"k\"";
        // 缺省 = 0.0（确定性重放默认）
        String defaultJson = "{\"llm\":{" + apiKeyPair + "}}";
        AgentAssert4jConfig.LlmConfig d = AgentAssert4jConfig.fromJson(defaultJson).getLlm();
        assertEquals(0.0, d.getTemperature(), 1e-9, "缺省保持 0.0 确定性默认");

        // 显式数值覆盖
        String numberJson = "{\"llm\":{" + apiKeyPair + ",\"temperature\":0.7}}";
        AgentAssert4jConfig.LlmConfig n = AgentAssert4jConfig.fromJson(numberJson).getLlm();
        assertEquals(0.7, n.getTemperature(), 1e-9);

        // 显式 null = 不发送该参数（推理模型方言）
        String nullJson = "{\"llm\":{" + apiKeyPair + ",\"temperature\":null}}";
        AgentAssert4jConfig.LlmConfig o = AgentAssert4jConfig.fromJson(nullJson).getLlm();
        assertNull(o.getTemperature(), "显式 null 必须区别于缺省——重放请求省略该成员");
    }

    @Nested
    @DisplayName("未知键检测对照面（键集 ↔ 解析路径契约钉）")
    class UnknownKeyDetection {

        @Test
        @DisplayName("全部合法根段就位 → 无未知键告警")
        void allKnownRootSections_produceNoNotes() {
            String json = "{\"storage\":{},\"recorder\":{},\"regression\":{},\"llm\":{}}";
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);
            assertTrue(config.getConfigNotes().isEmpty(), "合法根段不得触发告警: " + config.getConfigNotes());
        }

        @Test
        @DisplayName("llm 段全部合法键被解析路径真实消费（哨兵值逐字段吸收）")
        void everyLlmKey_isActuallyConsumed() {
            String json = "{\"llm\":{\"protocol\":\"openai-chat\",\"apiKey\":\"k\",\"endpoint\":\"http://e\"," + "\"model\":\"m\",\"timeoutMs\":1234,\"temperature\":0.7,\"extraBody\":\"{\\\"a\\\":1}\"}}";
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson(json);
            assertTrue(config.getConfigNotes().isEmpty(), "合法 llm 键不得触发告警: " + config.getConfigNotes());
            assertEquals("openai-chat", config.getLlm().getProtocol());
            assertEquals("k", config.getLlm().getApiKey());
            assertEquals("http://e", config.getLlm().getEndpoint());
            assertEquals("m", config.getLlm().getModel());
            assertEquals(1234, config.getLlm().getTimeoutMs());
            assertEquals(0.7, config.getLlm().getTemperature(), 1e-9);
            assertEquals("{\"a\":1}", config.getLlm().getExtraBody());
        }

        @Test
        @DisplayName("未知根键与未知 llm 键 → 告警就近可见")
        void unknownKeys_produceVisibleNotes() {
            AgentAssert4jConfig config = AgentAssert4jConfig.fromJson("{\"storag\":{},\"llm\":{\"endPoint\":\"http://e\"}}");
            assertEquals(2, config.getConfigNotes().size(), "两个未知键各出一条告警: " + config.getConfigNotes());
        }
    }
}
