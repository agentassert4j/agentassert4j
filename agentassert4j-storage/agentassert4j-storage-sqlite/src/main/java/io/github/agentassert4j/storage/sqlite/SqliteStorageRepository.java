package io.github.agentassert4j.storage.sqlite;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.Checkpoint;
import io.github.agentassert4j.spi.StorageRepository;

/**
 * SQLite 默认存储实现 — 单文件存储，适合单机开发场景。
 *
 * <p>遵循 JDBC 模式：接口在 core，实现在独立模块。
 * 插件发现：ServiceLoader 自动发现。</p>
 *
 * <p><b>技术债标记</b>：本类中标注了多处 TODO，将在后续功能完善后优化。</p>
 */
public class SqliteStorageRepository implements StorageRepository {

    private static final Logger LOG = Logger.getLogger(SqliteStorageRepository.class.getName());

    private final String dbPath;
    private Connection connection;

    public SqliteStorageRepository() {
        this(".agentassert4j/data.db");
    }

    public SqliteStorageRepository(String dbPath) {
        this.dbPath = dbPath;
    }

    @Override
    public String type() {
        return "sqlite";
    }

    /** 包级可见：供同包测试校验 PRAGMA user_version 等底层状态 */
    Connection getConnection() {
        return connection;
    }

    @Override
    public void initialize() {
        try {
            if (dbPath != null && !dbPath.startsWith(":")) {
                File parent = new File(dbPath).getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            connection.setAutoCommit(true);
            SchemaMigrator.migrate(connection);
        } catch (SQLException e) {
            // 初始化失败不得泄漏已打开的连接
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
                connection = null;
            }
            LOG.log(Level.SEVERE, "SQLite 初始化失败: " + dbPath, e);
            throw new RuntimeException("Storage initialization failed", e);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }

    // ======================== 交互记录 ========================

    @Override
    public void saveInteraction(InteractionRecord r) {
        // INSERT OR IGNORE：interactions 是只追加历史，record_id 冲突（崩溃重放双写）静默跳过
        String sql = "INSERT OR IGNORE INTO interactions" +
            " (record_id, session_id, timestamp, seq," +
            "  template_id, template_hash, variables_fingerprint," +
            "  api_protocol, provider, model, served_model, endpoint," +
            "  skill_id, group_key, user_input, turn_index," +
            "  tools_definition, sampling_params, model_request_raw," +
            "  finish_reason, model_response, model_response_raw," +
            "  tool_calls, has_tool_calls," +
            "  input_tokens, output_tokens, cache_read_tokens, cache_write_tokens," +
            "  reasoning_tokens, usage_raw, latency_ms, ttft_ms, cost_usd," +
            "  multimodal_input, multimodal_content, previous_turns, fingerprint," +
            "  metadata, recorder_version)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, r.getRecordId());
            ps.setString(i++, r.getSessionId());
            ps.setLong(i++, r.getTimestamp());
            setNullableLong(ps, i++, r.getSeq());
            ps.setString(i++, r.getTemplateId());
            ps.setString(i++, r.getTemplateHash());
            ps.setString(i++, r.getVariablesFingerprint());
            ps.setString(i++, r.getApiProtocol());
            ps.setString(i++, r.getProvider());
            ps.setString(i++, r.getModel());
            ps.setString(i++, r.getServedModel());
            ps.setString(i++, r.getEndpoint());
            ps.setString(i++, r.getSkillId());
            // TODO: [group_key 占位空串] DeterministicSkillGrouper 已实现且 InteractionRecord 已有
            //       groupKey 字段，但录制层尚未接线回填（复审 §5 管道断裂项，随步骤 6a 基线管道落地）
            ps.setString(i++, r.getGroupKey() != null ? r.getGroupKey() : "");
            ps.setString(i++, r.getUserInput());
            ps.setInt(i++, r.getTurnIndex());
            ps.setString(i++, r.getToolsDefinition());
            ps.setString(i++, r.getSamplingParams());
            ps.setString(i++, r.getModelRequestRaw());
            ps.setString(i++, r.getFinishReason());
            ps.setString(i++, r.getModelResponse());
            ps.setString(i++, r.getModelResponseRaw());
            ps.setString(i++, serializeToolCalls(r.getToolCalls()));
            ps.setInt(i++, r.isHasToolCalls() ? 1 : 0);
            setNullableInt(ps, i++, r.getInputTokens());
            setNullableInt(ps, i++, r.getOutputTokens());
            setNullableInt(ps, i++, r.getCacheReadTokens());
            setNullableInt(ps, i++, r.getCacheWriteTokens());
            setNullableInt(ps, i++, r.getReasoningTokens());
            ps.setString(i++, r.getUsageRaw());
            setNullableLong(ps, i++, r.getLatencyMs());
            setNullableLong(ps, i++, r.getTtftMs());
            setNullableDouble(ps, i++, r.getCostUsd());
            ps.setInt(i++, r.isMultimodalInput() ? 1 : 0);
            ps.setString(i++, r.getMultimodalContent());
            ps.setString(i++, serializeTurnContexts(r.getPreviousTurns()));
            // TODO: fingerprint 字段暂存 null。FingerprintExtractor 已实现，
            //       但 InteractionRecord 无 fingerprint 字段，需在 recorder 层通过
            //       FingerprintExtractor.extract(record) 生成指纹，序列化后写入。
            //       届时应使用 JsonMapper.toJson(FingerprintExtractor.extract(r)) 替代此 null
            ps.setString(i++, null);
            ps.setString(i++, r.getMetadata());
            ps.setString(i++, r.getRecorderVersion());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveInteraction failed", e);
        }
    }

    @Override
    public void saveInteractions(List<InteractionRecord> records) {
        if (records == null || records.isEmpty()) return;
        try {
            connection.setAutoCommit(false);
            for (InteractionRecord r : records) {
                saveInteraction(r);
            }
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
            LOG.log(Level.SEVERE, "saveInteractions batch failed", e);
        }
    }

    @Override
    public List<InteractionRecord> findBySkillId(String skillId) {
        return queryInteractions("SELECT * FROM interactions WHERE skill_id = ?", skillId);
    }

    @Override
    public List<InteractionRecord> findByPromptHash(String hash) {
        return queryInteractions("SELECT * FROM interactions WHERE template_hash = ?", hash);
    }

    @Override
    public Set<String> findSkillIdsByPromptHash(String hash) {
        Set<String> result = new HashSet<>();
        String sql = "SELECT DISTINCT skill_id FROM interactions WHERE template_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("skill_id"));
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findSkillIdsByPromptHash failed", e);
        }
        return result;
    }

    @Override
    public List<InteractionRecord> findBySessionId(String sessionId) {
        return queryInteractions("SELECT * FROM interactions WHERE session_id = ?", sessionId);
    }

    @Override
    public List<String> findAllSessionIds() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT DISTINCT session_id FROM interactions";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(rs.getString("session_id"));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findAllSessionIds failed", e);
        }
        return result;
    }

    // ======================== Skill 画像 ========================

    @Override
    public void saveSkillProfile(SkillProfile p) {
        String sql = "INSERT OR REPLACE INTO skill_profiles" +
            " (skill_id, group_key, skill_name, skill_type, fingerprint," +
            "  candidate_fingerprint, baseline_status, version_tag," +
            "  algo_version, param_signature, sample_count, approved_by, approved_at," +
            "  total_records, updated_at)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, p.getSkillId());
            ps.setString(i++, p.getGroupKey());
            ps.setString(i++, p.getSkillName());
            ps.setString(i++, p.getSkillType() != null ? p.getSkillType().name() : "TOOL_SKILL");
            ps.setString(i++, JsonMapper.toJson(p.getFingerprint()));
            ps.setString(i++, JsonMapper.toJson(p.getCandidateFingerprint()));
            ps.setString(i++, p.getBaselineStatus() != null ? p.getBaselineStatus().name() : "BASELINE");
            ps.setString(i++, p.getVersionTag());
            ps.setString(i++, p.getAlgoVersion());
            ps.setString(i++, p.getParamSignature());
            setNullableInt(ps, i++, p.getSampleCount());
            ps.setString(i++, p.getApprovedBy());
            setNullableLong(ps, i++, p.getApprovedAt());
            ps.setInt(i++, p.getTotalRecords());
            ps.setLong(i++, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveSkillProfile failed", e);
        }
    }

    @Override
    public SkillProfile findSkillByGroupKey(String key) {
        String sql = "SELECT * FROM skill_profiles WHERE group_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return JsonMapper.toSkillProfile(rs);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findSkillByGroupKey failed", e);
        }
        return null;
    }

    @Override
    public List<SkillProfile> findAllSkills() {
        List<SkillProfile> result = new ArrayList<>();
        String sql = "SELECT * FROM skill_profiles";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(JsonMapper.toSkillProfile(rs));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findAllSkills failed", e);
        }
        return result;
    }

    // ======================== Prompt 文本缓存 ========================

    @Override
    public void savePromptText(String hash, String promptText) {
        String sql = "INSERT OR REPLACE INTO prompt_texts (prompt_hash, prompt_text, created_at) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, promptText);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "savePromptText failed", e);
        }
    }

    @Override
    public String findPromptText(String hash) {
        String sql = "SELECT prompt_text FROM prompt_texts WHERE prompt_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("prompt_text");
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findPromptText failed", e);
        }
        return null;
    }

    // ======================== 依赖图谱 ========================

    @Override
    public void saveGraph(String graphJson) {
        String sql = "INSERT OR REPLACE INTO graph_snapshot (id, graph_json, updated_at) VALUES ('current',?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, graphJson);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveGraph failed", e);
        }
    }

    @Override
    public String loadGraph() {
        String sql = "SELECT graph_json FROM graph_snapshot WHERE id = 'current'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getString("graph_json");
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "loadGraph failed", e);
        }
        return null;
    }

    // ======================== 基线归档 ========================

    @Override
    public void archiveBaseline(String skillId, DeterministicFingerprint fingerprint, String versionTag) {
        String sql = "INSERT INTO archived_baselines (skill_id, fingerprint, version_tag, archived_at) VALUES (?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, JsonMapper.toJson(fingerprint));
            ps.setString(3, versionTag);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "archiveBaseline failed", e);
        }
    }

    @Override
    public ArchivedBaseline findArchivedBaseline(String skillId, String versionTag) {
        String sql = "SELECT * FROM archived_baselines WHERE skill_id = ? AND version_tag = ? ORDER BY archived_at DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, versionTag);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return JsonMapper.toArchivedBaseline(rs);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findArchivedBaseline failed", e);
        }
        return null;
    }

    // ======================== 检查点 ========================

    @Override
    public void saveCheckpoint(Checkpoint c) {
        String sql = "INSERT OR REPLACE INTO checkpoints (id, name, timestamp, passed, failed, diff, full_report) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, c.getId());
            ps.setString(2, c.getName());
            ps.setLong(3, c.getTimestamp());
            ps.setInt(4, c.getPassed());
            ps.setInt(5, c.getFailed());
            ps.setInt(6, c.getDiff());
            ps.setString(7, c.getFullReport());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveCheckpoint failed", e);
        }
    }

    // ======================== 序列化辅助 ========================

    // TODO: [技术债] 以下序列化方法（serializeToolCalls/serializeTurnContexts/escape 等）
    //       与 JsonMapper 中的手写 JSON 逻辑存在重复。
    //       待 RecursiveJsonParser 在 core util 包稳定后，应重构为统一使用 RecursiveJsonParser.parse()/serialize()，
    //       消除 SqliteStorageRepository 和 JsonMapper 中的手写 JSON 解析代码。
    //       参见 JsonMapper 类头注释。

    private static String serializeToolCalls(List<io.github.agentassert4j.model.ToolCall> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(toolCallToJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String serializeTurnContexts(List<io.github.agentassert4j.model.TurnContext> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(turnContextToJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // ======================== 内部辅助 ========================

    private List<InteractionRecord> queryInteractions(String sql, String param) {
        List<InteractionRecord> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "queryInteractions failed", e);
        }
        return result;
    }

    private InteractionRecord mapRecord(ResultSet rs) throws SQLException {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(rs.getString("record_id"));
        r.setSessionId(rs.getString("session_id"));
        r.setTimestamp(rs.getLong("timestamp"));
        r.setSeq(rs.getLong("seq"));
        r.setTemplateId(rs.getString("template_id"));
        r.setTemplateHash(rs.getString("template_hash"));
        r.setVariablesFingerprint(rs.getString("variables_fingerprint"));
        r.setApiProtocol(rs.getString("api_protocol"));
        r.setProvider(rs.getString("provider"));
        r.setModel(rs.getString("model"));
        r.setServedModel(rs.getString("served_model"));
        r.setEndpoint(rs.getString("endpoint"));
        r.setSkillId(rs.getString("skill_id"));
        r.setGroupKey(rs.getString("group_key"));
        r.setUserInput(rs.getString("user_input"));
        r.setTurnIndex(rs.getInt("turn_index"));
        r.setToolsDefinition(rs.getString("tools_definition"));
        r.setSamplingParams(rs.getString("sampling_params"));
        r.setModelRequestRaw(rs.getString("model_request_raw"));
        r.setFinishReason(rs.getString("finish_reason"));
        r.setModelResponse(rs.getString("model_response"));
        r.setModelResponseRaw(rs.getString("model_response_raw"));
        r.setHasToolCalls(rs.getInt("has_tool_calls") == 1);
        r.setInputTokens(rs.getInt("input_tokens"));
        r.setOutputTokens(rs.getInt("output_tokens"));
        r.setCacheReadTokens(getNullableInt(rs, "cache_read_tokens"));
        r.setCacheWriteTokens(getNullableInt(rs, "cache_write_tokens"));
        r.setReasoningTokens(getNullableInt(rs, "reasoning_tokens"));
        r.setUsageRaw(rs.getString("usage_raw"));
        r.setLatencyMs(rs.getLong("latency_ms"));
        r.setTtftMs(getNullableLong(rs, "ttft_ms"));
        r.setCostUsd(getNullableDouble(rs, "cost_usd"));
        r.setMultimodalInput(rs.getInt("multimodal_input") == 1);
        r.setMultimodalContent(rs.getString("multimodal_content"));
        r.setMetadata(rs.getString("metadata"));
        r.setRecorderVersion(rs.getString("recorder_version"));

        // 解析 JSON 字段
        String toolCallsJson = rs.getString("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            String json = "{\"toolCalls\":" + toolCallsJson + "}";
            r.setToolCalls(parseToolCallsFromDb(json));
        }

        String turnsJson = rs.getString("previous_turns");
        if (turnsJson != null && !turnsJson.isEmpty()) {
            String json = "{\"previousTurns\":" + turnsJson + "}";
            r.setPreviousTurns(parseTurnsFromDb(json));
        }

        return r;
    }

    // ====== 可空数值列的 JDBC 绑定/读取辅助 ======

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setLong(index, value);
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) ps.setNull(index, Types.REAL);
        else ps.setDouble(index, value);
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    // ToolCall 和 TurnContext 的独立序列化（供 saveInteraction 使用）

    private static String toolCallToJson(io.github.agentassert4j.model.ToolCall tc) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"toolName\":\"").append(escape(tc.getToolName())).append("\",");
        sb.append("\"toolCallId\":\"").append(escape(tc.getToolCallId())).append("\",");
        sb.append("\"success\":").append(tc.isSuccess()).append(",");
        sb.append("\"result\":\"").append(escape(tc.getResult())).append("\",");
        sb.append("\"arguments\":").append(mapToJson(tc.getArguments())).append(",");
        sb.append("\"argTypes\":").append(argTypesToJson(tc.getArgTypes()));
        sb.append("}");
        return sb.toString();
    }

    private static String turnContextToJson(io.github.agentassert4j.model.TurnContext tc) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"role\":\"").append(escape(tc.getRole())).append("\",");
        sb.append("\"content\":\"").append(escape(tc.getContent())).append("\"");
        if (tc.getToolCallId() != null) {
            sb.append(",\"toolCallId\":\"").append(escape(tc.getToolCallId())).append("\"");
        }
        if (tc.getToolName() != null) {
            sb.append(",\"toolName\":\"").append(escape(tc.getToolName())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static List<io.github.agentassert4j.model.ToolCall> parseToolCallsFromDb(String json) {
        String arr = extractArrayFromDb(json, "toolCalls");
        if (arr == null || arr.trim().isEmpty()) return new ArrayList<>();
        List<String> items = splitArrayItemsFromDb(arr);
        List<io.github.agentassert4j.model.ToolCall> result = new ArrayList<>();
        for (String item : items) {
            io.github.agentassert4j.model.ToolCall tc = new io.github.agentassert4j.model.ToolCall();
            tc.setToolName(getStringFromDb(item, "toolName"));
            tc.setToolCallId(getStringFromDb(item, "toolCallId"));
            tc.setSuccess(getBoolFromDb(item, "success"));
            tc.setResult(getStringFromDb(item, "result"));
            // arguments 和 argTypes 从 JSON 子对象解析
            tc.setArguments(extractStringMapFromDb(item, "arguments"));
            java.util.Map<String, Object> rawArgTypes = extractStringMapFromDb(item, "argTypes");
            if (rawArgTypes != null) {
                java.util.Map<String, String> argTypesMap = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, Object> e : rawArgTypes.entrySet()) {
                    argTypesMap.put(e.getKey(), e.getValue() != null ? String.valueOf(e.getValue()) : null);
                }
                tc.setArgTypes(argTypesMap);
            }
            result.add(tc);
        }
        return result;
    }

    private static List<io.github.agentassert4j.model.TurnContext> parseTurnsFromDb(String json) {
        String arr = extractArrayFromDb(json, "previousTurns");
        if (arr == null || arr.trim().isEmpty()) return new ArrayList<>();
        List<String> items = splitArrayItemsFromDb(arr);
        List<io.github.agentassert4j.model.TurnContext> result = new ArrayList<>();
        for (String item : items) {
            io.github.agentassert4j.model.TurnContext tc =
                    new io.github.agentassert4j.model.TurnContext(
                        getStringFromDb(item, "role"), getStringFromDb(item, "content"));
            tc.setToolCallId(getStringFromDb(item, "toolCallId"));
            tc.setToolName(getStringFromDb(item, "toolName"));
            result.add(tc);
        }
        return result;
    }

    // TODO: [技术债] 以下简化版 JSON 解析（escape/getStringFromDb/getBoolFromDb/extractArrayFromDb/splitArrayItemsFromDb）
    //       与 JsonMapper 中的解析逻辑高度重复。待统一使用 RecursiveJsonParser 后删除本段。
    // 简化版 JSON 解析（用于数据库读取）
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String getStringFromDb(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '\\' && end + 1 < json.length()) { end += 2; continue; }
            if (json.charAt(end) == '"') break;
            end++;
        }
        return json.substring(start, end);
    }

    private static boolean getBoolFromDb(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return false;
        start += pattern.length();
        return json.startsWith("true", start);
    }

    private static String extractArrayFromDb(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        if (start >= json.length() || json.charAt(start) != '[') return null;
        int depth = 1;
        int end = start + 1;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '"') { end++; while (end < json.length() && json.charAt(end) != '"') { if (json.charAt(end) == '\\') end++; end++; } }
            end++;
        }
        return json.substring(start + 1, end - 1);
    }

    private static List<String> splitArrayItemsFromDb(String arrayContent) {
        List<String> items = new ArrayList<>();
        if (arrayContent == null || arrayContent.trim().isEmpty()) return items;
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String item = arrayContent.substring(start, i).trim();
                if (!item.isEmpty()) items.add(item);
                start = i + 1;
            }
        }
        String last = arrayContent.substring(start).trim();
        if (!last.isEmpty()) items.add(last);
        return items;
    }

    // ========== Map 序列化/反序列化（供 toolCallToJson/parseToolCallsFromDb 使用） ==========

    private static String mapToJson(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(map.size() * 32);
        sb.append("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Boolean) {
                sb.append(val);
            } else if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(escape(String.valueOf(val))).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String argTypesToJson(java.util.Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(map.size() * 32);
        sb.append("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static java.util.Map<String, Object> extractStringMapFromDb(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        if (start >= json.length() || json.charAt(start) != '{') return null;
        int depth = 1;
        int end = start + 1;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') { end++; while (end < json.length() && json.charAt(end) != '"') { if (json.charAt(end) == '\\') end++; end++; } }
            end++;
        }
        String obj = json.substring(start + 1, end - 1);
        if (obj.trim().isEmpty()) return new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<String> pairs = splitMapPairs(obj);
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String k = pair.substring(0, colon).trim();
            if (k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length() - 1);
            String v = pair.substring(colon + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"")) {
                result.put(k, v.substring(1, v.length() - 1));
            } else {
                result.put(k, v);
            }
        }
        return result;
    }

    private static List<String> splitMapPairs(String content) {
        List<String> pairs = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inStr = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && inStr) { i++; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String p = content.substring(start, i).trim();
                if (!p.isEmpty()) pairs.add(p);
                start = i + 1;
            }
        }
        String last = content.substring(start).trim();
        if (!last.isEmpty()) pairs.add(last);
        return pairs;
    }
}
