package io.github.agentassert4j.storage.sqlite;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            try (Statement stmt = connection.createStatement()) {
                for (String ddl : Schema.ALL_DDL) {
                    stmt.execute(ddl);
                }
            }
        } catch (SQLException e) {
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
        String sql = "INSERT OR REPLACE INTO interactions" +
            " (record_id, session_id, timestamp, skill_id, group_key, system_prompt_hash," +
            "  user_input, turn_index, model_response, tool_calls, has_tool_calls," +
            "  latency_ms, multimodal_input, multimodal_content, previous_turns, fingerprint)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, r.getRecordId());
            ps.setString(2, r.getSessionId());
            ps.setLong(3, r.getTimestamp());
            ps.setString(4, r.getSkillId());
            // TODO: group_key 当前写死空串。DeterministicSkillGrouper 已实现，
            //       但 InteractionRecord 无 groupKey 字段，需在 recorder 层通过
            //       DeterministicSkillGrouper.group(record).getGroupKey() 获取后回填至 record.setSkillId()，
            //       并在此处使用 r.getSkillId() 对应的 SkillProfile.getGroupKey() 写入
            ps.setString(5, "");
            ps.setString(6, r.getSystemPromptHash());
            ps.setString(7, r.getUserInput());
            ps.setInt(8, r.getTurnIndex());
            ps.setString(9, r.getModelResponse());
            ps.setString(10, serializeToolCalls(r.getToolCalls()));
            ps.setInt(11, r.isHasToolCalls() ? 1 : 0);
            ps.setLong(12, r.getLatencyMs());
            ps.setInt(13, r.isMultimodalInput() ? 1 : 0);
            ps.setString(14, r.getMultimodalContent());
            ps.setString(15, serializeTurnContexts(r.getPreviousTurns()));
            // TODO: fingerprint 字段暂存 null。FingerprintExtractor 已实现，
            //       但 InteractionRecord 无 fingerprint 字段，需在 recorder 层通过
            //       FingerprintExtractor.extract(record) 生成指纹，序列化后写入。
            //       届时应使用 JsonMapper.toJson(FingerprintExtractor.extract(r)) 替代此 null
            ps.setString(16, null);
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
        return queryInteractions("SELECT * FROM interactions WHERE system_prompt_hash = ?", hash);
    }

    @Override
    public Set<String> findSkillIdsByPromptHash(String hash) {
        Set<String> result = new HashSet<>();
        String sql = "SELECT DISTINCT skill_id FROM interactions WHERE system_prompt_hash = ?";
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
            "  candidate_fingerprint, baseline_status, version_tag, total_records, updated_at)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, p.getSkillId());
            ps.setString(2, p.getGroupKey());
            ps.setString(3, p.getSkillName());
            ps.setString(4, p.getSkillType() != null ? p.getSkillType().name() : "TOOL_SKILL");
            ps.setString(5, JsonMapper.toJson(p.getFingerprint()));
            ps.setString(6, JsonMapper.toJson(p.getCandidateFingerprint()));
            ps.setString(7, p.getBaselineStatus() != null ? p.getBaselineStatus().name() : "BASELINE");
            ps.setString(8, p.getVersionTag());
            ps.setInt(9, p.getTotalRecords());
            ps.setLong(10, System.currentTimeMillis());
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
        String json = null;
        // 从数据库读取所有字段重建 InteractionRecord
        // 由于没有存储完整 JSON，逐字段映射
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(rs.getString("record_id"));
        r.setSessionId(rs.getString("session_id"));
        r.setTimestamp(rs.getLong("timestamp"));
        r.setSkillId(rs.getString("skill_id"));
        r.setSystemPromptHash(rs.getString("system_prompt_hash"));
        r.setUserInput(rs.getString("user_input"));
        r.setTurnIndex(rs.getInt("turn_index"));
        r.setModelResponse(rs.getString("model_response"));
        r.setHasToolCalls(rs.getInt("has_tool_calls") == 1);
        r.setLatencyMs(rs.getLong("latency_ms"));
        r.setMultimodalInput(rs.getInt("multimodal_input") == 1);
        r.setMultimodalContent(rs.getString("multimodal_content"));

        // 解析 JSON 字段
        String toolCallsJson = rs.getString("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            json = "{\"toolCalls\":" + toolCallsJson + "}";
            r.setToolCalls(parseToolCallsFromDb(json));
        }

        String turnsJson = rs.getString("previous_turns");
        if (turnsJson != null && !turnsJson.isEmpty()) {
            json = "{\"previousTurns\":" + turnsJson + "}";
            r.setPreviousTurns(parseTurnsFromDb(json));
        }

        return r;
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
