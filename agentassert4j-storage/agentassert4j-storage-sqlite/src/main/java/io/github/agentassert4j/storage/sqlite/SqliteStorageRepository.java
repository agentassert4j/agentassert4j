package io.github.agentassert4j.storage.sqlite;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageException;
import io.github.agentassert4j.spi.StorageRepository;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 默认存储实现 — 单文件存储，适合单机开发场景。
 *
 * <p>遵循 JDBC 模式：接口在 core，实现在独立模块。
 * 插件发现：ServiceLoader 自动发现。</p>
 *
 * <p>错误契约：SQL 失败一律抛 {@link StorageException}，绝不静默吞掉——
 * 吞掉会把磁盘满/库锁死伪装成"成功"或"无数据"。录制管道在上层捕获并计入失败计数。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
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

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
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

    @Override
    public String type() {
        return "sqlite";
    }

    /**
     * 包级可见：供同包测试校验 PRAGMA user_version 等底层状态
     */
    Connection getConnection() {
        return connection;
    }

    // initialize/close 与写路径共用实例监视器：flush 进行中不得关闭或置换连接，
    // 否则并发线程会在 prepareStatement 处踩到 null 连接
    @Override
    public synchronized void initialize() {
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
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
                connection = null;
            }
            LOG.log(Level.SEVERE, "SQLite 初始化失败: " + dbPath, e);
            throw new StorageException("initialize: " + dbPath, e);
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }

    @Override
    public synchronized void saveInteraction(InteractionRecord r) {
        // INSERT OR IGNORE：interactions 是只追加历史，record_id 冲突（崩溃重放双写）静默跳过
        String sql = "INSERT OR IGNORE INTO interactions" + " (record_id, session_id, timestamp, seq," + "  template_id, template_hash, variables_fingerprint," + "  api_protocol, provider, model, served_model, endpoint," + "  skill_id, group_key, user_input, turn_index," + "  tools_definition, sampling_params, model_request_raw," + "  finish_reason, model_response, model_response_raw," + "  tool_calls, has_tool_calls," + "  input_tokens, output_tokens, cache_read_tokens, cache_write_tokens," + "  reasoning_tokens, usage_raw, latency_ms, ttft_ms, cost_usd," + "  multimodal_input, multimodal_content, previous_turns, fingerprint," + "  metadata, recorder_version)" + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
            ps.setString(i++, r.getSkillId() != null ? r.getSkillId() : "");
            // groupKey 的 null→"" 是单向变形契约：落库后无 groupKey 与空 groupKey 不可区分，
            // 换取 NOT NULL 列约束（与 skillId 同一套约定）
            ps.setString(i++, r.getGroupKey() != null ? r.getGroupKey() : "");
            ps.setString(i++, r.getUserInput());
            ps.setInt(i++, r.getTurnIndex());
            ps.setString(i++, r.getToolsDefinition());
            ps.setString(i++, r.getSamplingParams());
            ps.setString(i++, r.getModelRequestRaw());
            ps.setString(i++, r.getFinishReason());
            ps.setString(i++, r.getModelResponse());
            ps.setString(i++, r.getModelResponseRaw());
            ps.setString(i++, JsonMapper.toolCallsToJson(r.getToolCalls()));
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
            ps.setString(i++, JsonMapper.turnsToJson(r.getPreviousTurns()));
            // 无指纹快照保持 SQL NULL（列可空）；有则序列化存储
            ps.setString(i++, r.getFingerprint() != null ? JsonMapper.fingerprintToJson(r.getFingerprint()) : null);
            ps.setString(i++, r.getMetadata());
            ps.setString(i++, r.getRecorderVersion());
            ps.executeUpdate();
            persistTemplateTextQuietly(r);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveInteraction failed", e);
            throw new StorageException("saveInteraction", e);
        }
    }

    /**
     * 模板文本随行归档：捕获侧在记录上携带的模板原文（瞬态字段，不对应
     * interactions 列）以 templateHash 为键写入 prompt_texts，供 status 巡检
     * 展示基线面对的模板原文。同 hash 首写为准；文本写失败只降级不拖累
     * 交互记录本身——旁路数据永不阻塞主数据。
     */
    private void persistTemplateTextQuietly(InteractionRecord r) {
        if (r.getTemplateText() == null || r.getTemplateText().isEmpty() || r.getTemplateHash() == null || r.getTemplateHash().isEmpty()) {
            return;
        }
        try {
            saveTemplateText(r.getTemplateHash(), r.getTemplateText());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "template text persist skipped for " + r.getRecordId(), e);
        }
    }

    @Override
    public synchronized void saveInteractions(List<InteractionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            for (InteractionRecord r : records) {
                saveInteraction(r);
            }
            connection.commit();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveInteractions batch failed", e);
            rollbackQuietly();
            throw new StorageException("saveInteractions", e);
        } catch (StorageException e) {
            // 子操作已带失败语义；整批回滚后原样上抛，避免双重包装丢失定位信息
            rollbackQuietly();
            throw e;
        } catch (RuntimeException e) {
            // 运行时异常同样必须先显式回滚：finally 恢复 autoCommit 在 sqlite-jdbc
            // 下对未决事务是隐式提交，不回滚就会把半批数据落盘、破坏整批原子性
            rollbackQuietly();
            throw e;
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void setAutoCommitQuietly(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    @Override
    public synchronized List<InteractionRecord> findBySkillId(String skillId) {
        return queryInteractions("SELECT * FROM interactions WHERE skill_id = ?" + " ORDER BY timestamp ASC, seq ASC, record_id ASC", skillId);
    }

    @Override
    public synchronized List<InteractionRecord> findByTemplateHash(String hash) {
        return queryInteractions("SELECT * FROM interactions WHERE template_hash = ?" + " ORDER BY timestamp ASC, seq ASC, record_id ASC", hash);
    }

    @Override
    public synchronized Set<String> findSkillIdsByTemplateHash(String hash) {
        Set<String> result = new HashSet<>();
        String sql = "SELECT DISTINCT skill_id FROM interactions WHERE template_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("skill_id"));
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findSkillIdsByTemplateHash failed", e);
            throw new StorageException("findSkillIdsByTemplateHash", e);
        }
        return result;
    }

    @Override
    public synchronized List<InteractionRecord> findBySessionId(String sessionId) {
        return queryInteractions("SELECT * FROM interactions WHERE session_id = ?" + " ORDER BY timestamp ASC, seq ASC, record_id ASC", sessionId);
    }

    @Override
    public synchronized List<String> findAllSessionIds() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT DISTINCT session_id FROM interactions";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(rs.getString("session_id"));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findAllSessionIds failed", e);
            throw new StorageException("findAllSessionIds", e);
        }
        return result;
    }

    @Override
    public synchronized void saveSkillProfile(SkillProfile p) {
        String sql = "INSERT OR REPLACE INTO skill_profiles" + " (skill_id, group_key, skill_name, skill_type, fingerprint," + "  candidate_fingerprint, baseline_status, version_tag," + "  algo_version, param_signature, sample_count, approved_by, approved_at," + "  total_records, updated_at)" + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, p.getSkillId());
            ps.setString(i++, p.getGroupKey());
            ps.setString(i++, p.getSkillName());
            ps.setString(i++, p.getSkillType() != null ? p.getSkillType().name() : "TOOL_SKILL");
            ps.setString(i++, JsonMapper.fingerprintToJson(p.getFingerprint()));
            ps.setString(i++, JsonMapper.fingerprintToJson(p.getCandidateFingerprint()));
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
            throw new StorageException("saveSkillProfile", e);
        }
    }

    @Override
    public synchronized SkillProfile findSkillByGroupKey(String key) {
        String sql = "SELECT * FROM skill_profiles WHERE group_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return JsonMapper.toSkillProfile(rs);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findSkillByGroupKey failed", e);
            throw new StorageException("findSkillByGroupKey", e);
        }
        return null;
    }

    @Override
    public synchronized List<SkillProfile> findAllSkills() {
        List<SkillProfile> result = new ArrayList<>();
        String sql = "SELECT * FROM skill_profiles";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(JsonMapper.toSkillProfile(rs));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findAllSkills failed", e);
            throw new StorageException("findAllSkills", e);
        }
        return result;
    }

    @Override
    public synchronized void saveTemplateText(String hash, String templateText) {
        // INSERT OR IGNORE：同 hash 首写为准（模板文本由内容哈希定键，覆盖写只会
        // 带来逐批写放大与 created_at 漂移），交互主数据的落库不受影响
        String sql = "INSERT OR IGNORE INTO prompt_texts (prompt_hash, prompt_text, created_at) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, templateText);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveTemplateText failed", e);
            throw new StorageException("saveTemplateText", e);
        }
    }

    @Override
    public synchronized String findTemplateText(String hash) {
        String sql = "SELECT prompt_text FROM prompt_texts WHERE prompt_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("prompt_text");
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findTemplateText failed", e);
            throw new StorageException("findTemplateText", e);
        }
        return null;
    }

    @Override
    public synchronized void saveGraph(String graphJson) {
        String sql = "INSERT OR REPLACE INTO graph_snapshot (id, graph_json, updated_at) VALUES ('current',?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, graphJson);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "saveGraph failed", e);
            throw new StorageException("saveGraph", e);
        }
    }

    @Override
    public synchronized String loadGraph() {
        String sql = "SELECT graph_json FROM graph_snapshot WHERE id = 'current'";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getString("graph_json");
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "loadGraph failed", e);
            throw new StorageException("loadGraph", e);
        }
        return null;
    }

    @Override
    public synchronized void archiveBaseline(ArchivedBaseline archived) {
        String sql = "INSERT INTO archived_baselines (skill_id, fingerprint, version_tag, algo_version, approved_by, approved_at, archived_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, archived.getSkillId());
            ps.setString(2, JsonMapper.fingerprintToJson(archived.getFingerprint()));
            ps.setString(3, archived.getVersionTag());
            ps.setString(4, archived.getAlgoVersion());
            ps.setString(5, archived.getApprovedBy());
            setNullableLong(ps, 6, archived.getApprovedAt());
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "archiveBaseline failed", e);
            throw new StorageException("archiveBaseline", e);
        }
    }

    @Override
    public synchronized ArchivedBaseline findArchivedBaseline(String skillId, String versionTag) {
        String sql = "SELECT * FROM archived_baselines WHERE skill_id = ? AND version_tag = ? ORDER BY archived_at DESC, rowid DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, versionTag);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return JsonMapper.toArchivedBaseline(rs);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "findArchivedBaseline failed", e);
            throw new StorageException("findArchivedBaseline", e);
        }
        return null;
    }

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
            throw new StorageException("queryInteractions", e);
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

        // 解析 JSON 列（列缺省 null 保持字段 null，与写侧 "[]" 的显式空集语义区分）
        String toolCallsJson = rs.getString("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            r.setToolCalls(JsonMapper.toolCallsFromDb(toolCallsJson));
        }

        String turnsJson = rs.getString("previous_turns");
        if (turnsJson != null && !turnsJson.isEmpty()) {
            r.setPreviousTurns(JsonMapper.turnsFromDb(turnsJson));
        }

        String fingerprintJson = rs.getString("fingerprint");
        if (fingerprintJson != null && !fingerprintJson.isEmpty()) {
            r.setFingerprint(JsonMapper.fingerprintFromDb(fingerprintJson));
        }

        return r;
    }
}
