package io.github.agentassert4j.config;

import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * AgentAssert4j 主配置模型 — 从 agentassert4j.json 加载。
 *
 * <p>配置优先级：CLI 参数 > 环境变量 > agentassert4j.json > 默认值。</p>
 *
 * <p>所有字段带安全默认值（退化不中断），缺失配置时框架仍可正常运行。</p>
 *
 * <h3>配置文件格式示例（agentassert4j.json）</h3>
 * <pre>
 * {
 *   "storage": { "url": "~/.agentassert4j/agentassert4j.db" },
 *   "recorder": { "batchSize": 100, "flushIntervalMs": 5000 },
 *   "regression": { "ignorableFields": ["debugInfo", "timestamp"] },
 *   "llm": { "protocol": "openai-chat", "apiKey": "${AGENTASSERT_API_KEY}", "endpoint": "...", "model": "gpt-4o",
 *            "extraBody": "\"thinking\":{\"type\":\"disabled\"}" }
 * }
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class AgentAssert4jConfig {

    private StorageConfig storage;
    private RecorderConfig recorder;
    private RegressionConfig regression;
    private LlmConfig llm;

    /**
     * 配置文件未知键告警（加载时收集，doctor 呈现）——拼错/放错层级的键
     * 静默无效是排障黑洞，就近可见优于静默忽略。
     */
    private List<String> configNotes = new ArrayList<>();

    public AgentAssert4jConfig() {
        this.storage = new StorageConfig();
        this.recorder = new RecorderConfig();
        this.regression = new RegressionConfig();
        this.llm = new LlmConfig();
    }

    /**
     * 返回带安全默认值的配置
     */
    public static AgentAssert4jConfig defaults() {
        return new AgentAssert4jConfig();
    }

    /**
     * 从 JSON 字符串解析配置。解析失败时安全退化为默认值。
     *
     * @param json JSON 格式的配置文本
     * @return 解析后的配置（任何解析失败的字段使用默认值）
     */
    @SuppressWarnings("unchecked")
    public static AgentAssert4jConfig fromJson(String json) {
        AgentAssert4jConfig config = defaults();
        if (TextUtil.isBlank(json)) return config;

        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) return config;

        Map<String, Object> root = (Map<String, Object>) parsed;
        config.storage = StorageConfig.fromJson(getMap(root, "storage"), config.storage);
        config.recorder = RecorderConfig.fromJson(getMap(root, "recorder"), config.recorder);
        config.regression = RegressionConfig.fromJson(getMap(root, "regression"), config.regression);
        config.llm = LlmConfig.fromJson(getMap(root, "llm"), config.llm);

        // 未知键就近可见：拼错/放错层级的配置键静默无效是排障黑洞（实测中
        // 「protocol 放顶层不生效」即此坑）。只告警不拒绝——未知键不影响既有语义。
        List<String> notes = new ArrayList<>();
        for (Object key : root.keySet()) {
            if (!ROOT_KEYS.contains(String.valueOf(key))) {
                notes.add("unknown config key '" + key + "' (not under any known section)");
            }
        }
        Map<String, Object> llmMap = getMap(root, "llm");
        if (llmMap != null) {
            for (Object key : llmMap.keySet()) {
                if (!LLM_KEYS.contains(String.valueOf(key))) {
                    notes.add("unknown llm key '" + key + "' (valid: " + LLM_KEYS + ")");
                }
            }
        }
        config.setConfigNotes(notes);

        return config;
    }

    /** 已知根段与 llm 段键集——未知键检测的对照面。 */
    private static final Set<String> ROOT_KEYS = new HashSet<>(
            Arrays.asList("storage", "recorder", "regression", "llm", "tools"));
    private static final Set<String> LLM_KEYS = new HashSet<>(
            Arrays.asList("protocol", "apiKey", "endpoint", "model", "timeoutMs", "temperature", "extraBody"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object val = parent.get(key);
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key, List<String> defaultValue) {
        Object val = map.get(key);
        if (!(val instanceof List)) return defaultValue;
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) val) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage;
    }

    public RecorderConfig getRecorder() {
        return recorder;
    }

    public void setRecorder(RecorderConfig recorder) {
        this.recorder = recorder;
    }

    public RegressionConfig getRegression() {
        return regression;
    }

    public void setRegression(RegressionConfig regression) {
        this.regression = regression;
    }

    public LlmConfig getLlm() {
        return llm;
    }

    /** 配置未知键告警（可能为空）。 */
    public List<String> getConfigNotes() {
        return configNotes;
    }

    public void setConfigNotes(List<String> configNotes) {
        this.configNotes = configNotes != null ? configNotes : new ArrayList<>();
    }

    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }

    /**
     * 存储配置 — v1 唯一后端为 SQLite 单文件。
     */
    public static class StorageConfig {
        /**
         * 存储文件路径
         */
        private String url = "~/.agentassert4j/agentassert4j.db";

        static StorageConfig fromJson(Map<String, Object> map, StorageConfig defaults) {
            if (map == null) return defaults;
            StorageConfig c = new StorageConfig();
            c.url = getString(map, "url", defaults.url);
            return c;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    /**
     * 录制器配置。
     */
    public static class RecorderConfig {
        /**
         * 批量写入大小
         */
        private int batchSize = 100;
        /**
         * 刷新间隔（毫秒）
         */
        private int flushIntervalMs = 5000;

        static RecorderConfig fromJson(Map<String, Object> map, RecorderConfig defaults) {
            if (map == null) return defaults;
            RecorderConfig c = new RecorderConfig();
            c.batchSize = getInt(map, "batchSize", defaults.batchSize);
            c.flushIntervalMs = getInt(map, "flushIntervalMs", defaults.flushIntervalMs);
            return c;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(int flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }
    }

    /**
     * 回归测试配置。
     */
    public static class RegressionConfig {
        /**
         * 可忽略字段（增删不扣分）
         */
        private List<String> ignorableFields = new ArrayList<>();

        static RegressionConfig fromJson(Map<String, Object> map, RegressionConfig defaults) {
            if (map == null) return defaults;
            RegressionConfig c = new RegressionConfig();
            c.ignorableFields = getStringList(map, "ignorableFields", defaults.ignorableFields);
            return c;
        }

        public List<String> getIgnorableFields() {
            return ignorableFields;
        }

        public void setIgnorableFields(List<String> ignorableFields) {
            this.ignorableFields = ignorableFields != null ? ignorableFields : Collections.emptyList();
        }
    }

    /**
     * LLM API 配置。
     */
    public static class LlmConfig {
        /**
         * API Key（支持 ${ENV_VAR} 环境变量引用）
         */
        private String apiKey;
        /**
         * wire 协议方言（LlmWireProtocol 的线上值）。null（缺省）= 自动推导：重放
         * 按基线记录的原摄取方言发射，无记录提示时回退 openai-chat；显式配置
         * = 覆盖推导（跨协议重放是显式意图）。未知值在客户端构造点报错
         * （配置解析层原样保留，不静默回退）
         */
        private String protocol;
        /**
         * API 端点
         */
        private String endpoint = "https://api.openai.com";
        /**
         * 模型名称
         */
        private String model = "gpt-4o";
        /**
         * 超时时间（毫秒）
         */
        private int timeoutMs = 30000;
        /**
         * 采样温度（默认 0.0 确定性输出）；显式配置 null 表示请求体不携带该参数
         * ——OpenAI o 系等推理模型只接受默认温度，发送 0.0 会被 400 拒绝
         */
        private Double temperature = 0.0;
        /**
         * 厂商方言扩展字段——原样注入请求体顶层的 JSON 成员片段（如 DeepSeek V4 系
         * 关闭思考态的 "thinking":{"type":"disabled"}），null/空白表示无扩展。
         * 客户端不做任何按模型名的自动适配，方言差异由使用方经此字段显式声明
         */
        private String extraBody;

        static LlmConfig fromJson(Map<String, Object> map, LlmConfig defaults) {
            if (map == null) return defaults;
            LlmConfig c = new LlmConfig();
            c.apiKey = getString(map, "apiKey", defaults.apiKey);
            c.protocol = getString(map, "protocol", defaults.protocol);
            c.endpoint = getString(map, "endpoint", defaults.endpoint);
            c.model = getString(map, "model", defaults.model);
            c.timeoutMs = getInt(map, "timeoutMs", defaults.timeoutMs);
            // 显式 "temperature": null 与缺省不同：null=不发送该参数，缺省=默认 0.0
            if (map.containsKey("temperature")) {
                Object raw = map.get("temperature");
                c.temperature = raw instanceof Number ? ((Number) raw).doubleValue() : null;
            } else {
                c.temperature = defaults.temperature;
            }
            c.extraBody = getString(map, "extraBody", defaults.extraBody);
            return c;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(String extraBody) {
            this.extraBody = extraBody;
        }
    }

}
