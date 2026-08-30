package io.github.agentassert4j.storage.sqlite;

/**
 * SQLite schema 定义（契约版本 1）。
 *
 * <p>三层列结构：</p>
 * <ul>
 *   <li>概念层：类型化列，只存跨协议稳定的概念数据，方言差异在捕获层归一</li>
 *   <li>原文层：{@code *_raw} 列逐字保留请求/响应/usage，是后续新增概念列的回填来源</li>
 *   <li>吸收层：{@code metadata} JSON 列承接未预见的扩展属性</li>
 * </ul>
 *
 * <p>演进规则：公开发布后只允许「新增可空列 + 从 raw 回填」，禁止破坏性变更。
 * 版本由 {@link SchemaMigrator} 以 PRAGMA user_version 盖戳。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
final class Schema {

    /**
     * 当前 schema 契约版本（PRAGMA user_version）
     */
    static final int USER_VERSION = 1;
    static final String[] ALL_DDL = {

            // 交互记录表（只追加历史）
            "CREATE TABLE IF NOT EXISTS interactions (" +
                    "  record_id             TEXT PRIMARY KEY," +
                    "  session_id            TEXT NOT NULL," +
                    "  timestamp             INTEGER NOT NULL," +
                    // seq 由录制器进程内单调分配，与 timestamp 组成确定性排序键
                    "  seq                   INTEGER," +
                    // Prompt 身份三元组；template_hash 可空——无 system prompt 的调用点由解析器回退到请求锚点
                    "  template_id           TEXT," +
                    "  template_hash         TEXT," +
                    "  variables_fingerprint TEXT," +
                    // 模型与部署身份：基线跨模型/部署不可比，必须落列
                    "  api_protocol          TEXT," +
                    "  provider              TEXT," +
                    "  model                 TEXT," +
                    "  served_model          TEXT," +
                    "  endpoint              TEXT," +
                    "  invocation_id         TEXT NOT NULL," +
                    "  invocation_key        TEXT NOT NULL," +
                    "  user_input            TEXT," +
                    "  turn_index            INTEGER DEFAULT 0," +
                    // 请求保真
                    "  tools_definition      TEXT," +
                    "  sampling_params       TEXT," +
                    "  model_request_raw     TEXT," +
                    // 响应保真（model_response 可空——纯工具调用的响应没有文本内容）
                    "  finish_reason         TEXT," +
                    "  model_response        TEXT," +
                    "  model_response_raw    TEXT," +
                    "  tool_calls            TEXT NOT NULL," +
                    "  has_tool_calls        INTEGER NOT NULL," +
                    // 遥测（概念列为方言中立命名，供应商原始 usage 存 usage_raw）
                    "  input_tokens          INTEGER," +
                    "  output_tokens         INTEGER," +
                    "  cache_read_tokens     INTEGER," +
                    "  cache_write_tokens    INTEGER," +
                    "  reasoning_tokens      INTEGER," +
                    "  usage_raw             TEXT," +
                    "  latency_ms            INTEGER," +
                    "  ttft_ms               INTEGER," +
                    "  cost_usd              REAL," +
                    "  multimodal_input      INTEGER DEFAULT 0," +
                    "  multimodal_content    TEXT," +
                    "  previous_turns        TEXT," +
                    // metadata 为通用扩展列；recorder_version 标记写入方版本
                    "  metadata              TEXT," +
                    "  recorder_version      TEXT" +
                    ")",

            // (session_id, seq) 是确定性排序键，复合索引前缀同时覆盖 session_id 单列查询
            "CREATE INDEX IF NOT EXISTS idx_session_seq ON interactions(session_id, seq)",
            "CREATE INDEX IF NOT EXISTS idx_invocation_id ON interactions(invocation_id)",
            "CREATE INDEX IF NOT EXISTS idx_template_hash ON interactions(template_hash)",
            "CREATE INDEX IF NOT EXISTS idx_invocation_key ON interactions(invocation_key)",
            "CREATE INDEX IF NOT EXISTS idx_timestamp ON interactions(timestamp)",

            // Prompt 原文库（hash 不可逆，原文只能存这里，删除即永久丢失）
            "CREATE TABLE IF NOT EXISTS prompt_texts (" +
                    "  prompt_hash  TEXT PRIMARY KEY," +
                    "  prompt_text  TEXT NOT NULL," +
                    "  created_at   INTEGER NOT NULL" +
                    ")",

            // 调用点画像表（治理主体 = 调用点的模板版本史）
            "CREATE TABLE IF NOT EXISTS invocations (" +
                    "  invocation_key        TEXT PRIMARY KEY," +
                    "  label                 TEXT," +
                    "  template_hash         TEXT," +
                    "  invocation_name       TEXT NOT NULL," +
                    "  invocation_type       TEXT NOT NULL," +
                    "  fingerprint           TEXT NOT NULL," +
                    "  candidate_fingerprint TEXT," +
                    "  baseline_status       TEXT DEFAULT 'BASELINE'," +
                    "  version_tag           TEXT," +
                    "  algo_version          TEXT," +
                    "  param_signature       TEXT," +
                    "  approved_by           TEXT," +
                    "  approved_at           INTEGER," +
                    "  total_records         INTEGER DEFAULT 0," +
                    "  updated_at            INTEGER NOT NULL" +
                    ")",

            // 审批时旧基线按模板版本的归档表
            "CREATE TABLE IF NOT EXISTS invocation_template_versions (" +
                    "  id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  invocation_key TEXT NOT NULL," +
                    "  template_hash  TEXT," +
                    "  fingerprint    TEXT NOT NULL," +
                    "  version_tag    TEXT," +
                    "  algo_version   TEXT," +
                    "  approved_by    TEXT," +
                    "  approved_at    INTEGER," +
                    "  archived_at    INTEGER NOT NULL" +
                    ")",

            "CREATE INDEX IF NOT EXISTS idx_archived_invocation ON invocation_template_versions(invocation_key)",

            // 依赖图快照（整图 JSON；图是派生数据，可随时重建）
            "CREATE TABLE IF NOT EXISTS graph_snapshot (" +
                    "  id           TEXT PRIMARY KEY DEFAULT 'current'," +
                    "  graph_json   TEXT NOT NULL," +
                    "  updated_at   INTEGER NOT NULL" +
                    ")",

    };

    private Schema() {
    }
}
