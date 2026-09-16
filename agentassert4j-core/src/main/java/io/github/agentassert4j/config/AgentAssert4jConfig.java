package io.github.agentassert4j.config;

import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;

import java.util.*;

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
 *   "regression": { "ignorableFields": ["debugInfo", "timestamp"] },
 *   "llm": { "protocol": "openai-chat", "apiKey": "${AGENTASSERT_API_KEY}", "endpoint": "...", "model": "gpt-4o",
 *            "extraBody": "\"thinking\":{\"type\":\"disabled\"}" }
 * }
 * </pre>
 * 录制侧旋钮不走本文件：Boot 应用经 application.yml（starter 绑定），非 Boot 应用
 * 经 {@code RecorderConfig.builder()} 程序化装配——本文件是 CLI/MCP 操作面的配置。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class AgentAssert4jConfig {

    private StorageConfig storage;
    private RegressionConfig regression;
    private LlmConfig llm;

    /**
     * 配置文件未知键告警（加载时收集，doctor 呈现）——拼错/放错层级的键
     * 静默无效是排障黑洞，就近可见优于静默忽略。
     */
    private List<String> configNotes = new ArrayList<>();

    public AgentAssert4jConfig() {
        this.storage = new StorageConfig();
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

        // 未知键就近可见：拼错/放错层级的配置键静默无效是排障黑洞（实测中
        // 「protocol 放顶层不生效」即此坑）。只告警不拒绝——未知键不影响既有语义。
        List<String> notes = new ArrayList<>();
        config.storage = StorageConfig.fromJson(getMap(root, "storage"), config.storage);
        config.regression = RegressionConfig.fromJson(getMap(root, "regression"), config.regression, notes);
        config.llm = LlmConfig.fromJson(getMap(root, "llm"), config.llm);

        for (Object key : root.keySet()) {
            if (!ROOT_KEYS.contains(String.valueOf(key))) {
                notes.add("unknown config key '" + key + "' (not under any known section)");
            }
        }
        Map<String, Object> regressionMap = getMap(root, "regression");
        if (regressionMap != null) {
            for (Object key : regressionMap.keySet()) {
                if (!REGRESSION_KEYS.contains(String.valueOf(key))) {
                    notes.add("unknown regression key '" + key + "' (valid: " + REGRESSION_KEYS + ")");
                }
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

    /**
     * 已知根段与 llm 段键集——未知键检测的对照面，键必须与解析路径一一对应。
     */
    private static final Set<String> ROOT_KEYS = new HashSet<>(Arrays.asList("storage", "regression", "llm"));
    private static final Set<String> LLM_KEYS = new HashSet<>(Arrays.asList("protocol", "apiKey", "endpoint", "model", "timeoutMs", "maxRetries", "maxTokens", "temperature", "extraBody"));
    private static final Set<String> REGRESSION_KEYS = new HashSet<>(Arrays.asList("ignorableFields", "memberSampleWindow"));

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

    private static Integer getNullableInt(Map<String, Object> map, String key, Integer defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
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

    public RegressionConfig getRegression() {
        return regression;
    }

    public void setRegression(RegressionConfig regression) {
        this.regression = regression;
    }

    public LlmConfig getLlm() {
        return llm;
    }

    /**
     * 配置未知键告警（可能为空）。
     */
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
     * 回归测试配置。
     */
    public static class RegressionConfig {
        /**
         * 可忽略字段（增删不扣分）
         */
        private List<String> ignorableFields = new ArrayList<>();
        /**
         * 成员判定（member-check）的样本窗默认——有限整数，null = 用内置默认 5。
         * 配置面只收 N：all 仅限单次调用显式传入（常驻无界默认会把稳定性量尺
         * 静默变成考古 oracle）。
         */
        private Integer memberSampleWindow;

        static RegressionConfig fromJson(Map<String, Object> map, RegressionConfig defaults, List<String> notes) {
            if (map == null) return defaults;
            RegressionConfig c = new RegressionConfig();
            c.ignorableFields = getStringList(map, "ignorableFields", defaults.ignorableFields);
            Object window = map.get("memberSampleWindow");
            if (window instanceof Number) {
                int value = ((Number) window).intValue();
                if (value >= 1) {
                    c.memberSampleWindow = Integer.valueOf(value);
                } else {
                    notes.add("regression.memberSampleWindow must be an integer >= 1; using the built-in default instead.");
                }
            } else if (window != null) {
                notes.add("regression.memberSampleWindow must be an integer >= 1 (use --member-window all for a one-off full scan); using the built-in default instead.");
            }
            return c;
        }

        public List<String> getIgnorableFields() {
            return ignorableFields;
        }

        public void setIgnorableFields(List<String> ignorableFields) {
            this.ignorableFields = ignorableFields != null ? ignorableFields : Collections.emptyList();
        }

        public Integer getMemberSampleWindow() {
            return memberSampleWindow;
        }

        public void setMemberSampleWindow(Integer memberSampleWindow) {
            this.memberSampleWindow = memberSampleWindow;
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
         * 传输层失败（HTTP 429/5xx/连接被拒）的最大重试次数；负数按 0 处理。
         * 默认 2——重试直接决定重驱成本与时长，网络环境差异大，暴露给用户
         */
        private int maxRetries = 2;
        /**
         * 发射请求的 max_tokens 兜底上限。Anthropic Messages 的 max_tokens 必填、
         * 基线记录未携带时按此值填充；null = 客户端内置默认（4096）
         */
        private Integer maxTokens;
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
            c.maxRetries = getInt(map, "maxRetries", defaults.maxRetries);
            c.maxTokens = getNullableInt(map, "maxTokens", defaults.maxTokens);
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

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public String getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(String extraBody) {
            this.extraBody = extraBody;
        }
    }

}
