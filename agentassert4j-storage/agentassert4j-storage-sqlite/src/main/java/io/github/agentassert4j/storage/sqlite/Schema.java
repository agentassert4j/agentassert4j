package io.github.agentassert4j.storage.sqlite;

/**
 * SQLite Schema 常量 — v1 定稿（《AgentAssert4j Schema定稿设计（2026-08-26）》为真源）。
 *
 * <p>三层存储模型：</p>
 * <ul>
 *   <li>概念层：类型化列，只存跨协议概念稳定的数据，方言差异在捕获层归一化</li>
 *   <li>原文层：{@code *_raw} 列逐字保留，是未来一切新概念列的回填来源（承重墙）</li>
 *   <li>吸收层：{@code metadata} JSON 列吸收一切 unforeseen 属性</li>
 * </ul>
 *
 * <p><b>V100 演进契约</b>：自本版本起只允许 ADD COLUMN 可空列 + 从 raw 回填，
 * 永不破坏性变更、永不重解释已存语义（语义升级走 algo_version 显式区分）。
 * 版本由 {@link SchemaMigrator} 以 PRAGMA user_version 盖戳。</p>
 *
 * <p>scenarios / scenario_runs / check_runs 三表契约已在定稿文档 §4 定形，
 * 建表随场景层与 CLI 模块落地，此处不预建空表。</p>
 */
final class Schema {

    private Schema() {}

    /** 当前 schema 契约版本（PRAGMA user_version） */
    static final int USER_VERSION = 1;

    // ====== 表名常量 ======
    static final String TABLE_INTERACTIONS = "interactions";
    static final String TABLE_PROMPT_TEXTS = "prompt_texts";
    static final String TABLE_SKILL_PROFILES = "skill_profiles";
    static final String TABLE_ARCHIVED_BASELINES = "archived_baselines";
    static final String TABLE_GRAPH_SNAPSHOT = "graph_snapshot";

    // ====== v1 DDL ======

    static final String[] ALL_DDL = {
        // 交互记录表（只追加历史；列分组 A-G 见定稿文档 §2.2）
        "CREATE TABLE IF NOT EXISTS interactions (" +
        "  record_id             TEXT PRIMARY KEY," +
        "  session_id            TEXT NOT NULL," +
        "  timestamp             INTEGER NOT NULL," +
        // A. 顺序与确定性（修复同毫秒交互排序不可复现）
        "  seq                   INTEGER," +
        // B. Prompt 身份三元组（template_hash 可空：无 system prompt 的纯对话记录没有模板锚点，
        //    分组侧由 Grouper 回退到 user_input hash——复审 M6）
        "  template_id           TEXT," +
        "  template_hash         TEXT," +
        "  variables_fingerprint TEXT," +
        // C. 模型与部署身份（指纹可比性前提）
        "  api_protocol          TEXT," +
        "  provider              TEXT," +
        "  model                 TEXT," +
        "  served_model          TEXT," +
        "  endpoint              TEXT," +
        // 既有列
        "  skill_id              TEXT NOT NULL," +
        "  group_key             TEXT NOT NULL," +
        "  user_input            TEXT," +
        "  turn_index            INTEGER DEFAULT 0," +
        // D. 请求保真
        "  tools_definition      TEXT," +
        "  sampling_params       TEXT," +
        "  model_request_raw     TEXT," +
        // E. 响应保真
        "  finish_reason         TEXT," +
        "  model_response        TEXT NOT NULL," +
        "  model_response_raw    TEXT," +
        "  tool_calls            TEXT NOT NULL," +
        "  has_tool_calls        INTEGER NOT NULL," +
        // F. 遥测（方言中立命名）
        "  input_tokens          INTEGER," +
        "  output_tokens         INTEGER," +
        "  cache_read_tokens     INTEGER," +
        "  cache_write_tokens    INTEGER," +
        "  reasoning_tokens      INTEGER," +
        "  usage_raw             TEXT," +
        "  latency_ms            INTEGER," +
        "  ttft_ms               INTEGER," +
        "  cost_usd              REAL," +
        // 既有列
        "  multimodal_input      INTEGER DEFAULT 0," +
        "  multimodal_content    TEXT," +
        "  previous_turns        TEXT," +
        "  fingerprint           TEXT," +
        // G. 通用性字段
        "  metadata              TEXT," +
        "  recorder_version      TEXT" +
        ")",

        // 确定性排序键（重放/提边的复现保证）；复合索引前缀覆盖 session_id 单列查询
        "CREATE INDEX IF NOT EXISTS idx_session_seq ON interactions(session_id, seq)",
        "CREATE INDEX IF NOT EXISTS idx_skill_id ON interactions(skill_id)",
        "CREATE INDEX IF NOT EXISTS idx_template_hash ON interactions(template_hash)",
        "CREATE INDEX IF NOT EXISTS idx_group_key ON interactions(group_key)",
        "CREATE INDEX IF NOT EXISTS idx_timestamp ON interactions(timestamp)",

        // Prompt 模板文本库（按 hash 去重存储——hash 不可逆的原文，单向门数据）
        "CREATE TABLE IF NOT EXISTS prompt_texts (" +
        "  prompt_hash  TEXT PRIMARY KEY," +
        "  prompt_text  TEXT NOT NULL," +
        "  created_at   INTEGER NOT NULL" +
        ")",

        // Skill 画像表
        "CREATE TABLE IF NOT EXISTS skill_profiles (" +
        "  skill_id              TEXT PRIMARY KEY," +
        "  group_key             TEXT UNIQUE NOT NULL," +
        "  skill_name            TEXT NOT NULL," +
        "  skill_type            TEXT NOT NULL," +
        "  fingerprint           TEXT NOT NULL," +
        "  candidate_fingerprint TEXT," +
        "  baseline_status       TEXT DEFAULT 'BASELINE'," +
        "  version_tag           TEXT," +
        "  algo_version          TEXT," +
        "  param_signature       TEXT," +
        "  sample_count          INTEGER," +
        "  approved_by           TEXT," +
        "  approved_at           INTEGER," +
        "  total_records         INTEGER DEFAULT 0," +
        "  updated_at            INTEGER NOT NULL" +
        ")",

        // 基线归档表（approve 时旧基线移入）
        "CREATE TABLE IF NOT EXISTS archived_baselines (" +
        "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
        "  skill_id      TEXT NOT NULL," +
        "  fingerprint   TEXT NOT NULL," +
        "  version_tag   TEXT," +
        "  algo_version  TEXT," +
        "  approved_by   TEXT," +
        "  approved_at   INTEGER," +
        "  archived_at   INTEGER NOT NULL" +
        ")",

        "CREATE INDEX IF NOT EXISTS idx_archived_skill ON archived_baselines(skill_id)",

        // 图数据（整体 JSON，非逐行存边——图是派生数据、双向门）
        "CREATE TABLE IF NOT EXISTS graph_snapshot (" +
        "  id           TEXT PRIMARY KEY DEFAULT 'current'," +
        "  graph_json   TEXT NOT NULL," +
        "  updated_at   INTEGER NOT NULL" +
        ")"
    };

    // ====== 旧版（v0，发布前开发期）全部表名 ======
    // checkpoints 已砍除（只写无读的死表）；列出新语义后的旧表无法增量迁移，整体重建。
    static final String[] LEGACY_V0_TABLES = {
        "interactions", "prompt_texts", "skill_profiles",
        "archived_baselines", "graph_snapshot", "checkpoints"
    };
}
