package io.github.agentassert4j.storage.sqlite;

/**
 * SQLite Schema 常量 — 与方案文档 6.3 节完全一致。
 * 所有 DDL 使用 IF NOT EXISTS，支持重复执行。
 */
final class Schema {

    private Schema() {}

    // ====== 表名常量 ======
    static final String TABLE_INTERACTIONS = "interactions";
    static final String TABLE_PROMPT_TEXTS = "prompt_texts";
    static final String TABLE_SKILL_PROFILES = "skill_profiles";
    static final String TABLE_ARCHIVED_BASELINES = "archived_baselines";
    static final String TABLE_GRAPH_SNAPSHOT = "graph_snapshot";
    static final String TABLE_CHECKPOINTS = "checkpoints";

    // ====== DDL ======

    static final String[] ALL_DDL = {
        // 交互记录表
        "CREATE TABLE IF NOT EXISTS interactions (" +
        "  record_id           TEXT PRIMARY KEY," +
        "  session_id          TEXT NOT NULL," +
        "  timestamp           INTEGER NOT NULL," +
        "  skill_id            TEXT NOT NULL," +
        "  group_key           TEXT NOT NULL," +
        "  system_prompt_hash  TEXT NOT NULL," +
        "  user_input          TEXT," +
        "  turn_index          INTEGER DEFAULT 0," +
        "  model_response      TEXT NOT NULL," +
        "  tool_calls          TEXT NOT NULL," +
        "  has_tool_calls      INTEGER NOT NULL," +
        "  latency_ms          INTEGER," +
        "  multimodal_input    INTEGER DEFAULT 0," +
        "  multimodal_content  TEXT," +
        "  previous_turns      TEXT," +
        "  fingerprint         TEXT" +
        ")",

        "CREATE INDEX IF NOT EXISTS idx_skill_id ON interactions(skill_id)",
        "CREATE INDEX IF NOT EXISTS idx_prompt_hash ON interactions(system_prompt_hash)",
        "CREATE INDEX IF NOT EXISTS idx_session_id ON interactions(session_id)",

        // Prompt 文本缓存表（按 hash 去重存储）
        "CREATE TABLE IF NOT EXISTS prompt_texts (" +
        "  prompt_hash  TEXT PRIMARY KEY," +
        "  prompt_text  TEXT NOT NULL," +
        "  created_at   INTEGER NOT NULL" +
        ")",

        // Skill 画像表
        "CREATE TABLE IF NOT EXISTS skill_profiles (" +
        "  skill_id             TEXT PRIMARY KEY," +
        "  group_key            TEXT UNIQUE NOT NULL," +
        "  skill_name           TEXT NOT NULL," +
        "  skill_type           TEXT NOT NULL," +
        "  fingerprint          TEXT NOT NULL," +
        "  candidate_fingerprint TEXT," +
        "  baseline_status      TEXT DEFAULT 'BASELINE'," +
        "  version_tag          TEXT," +
        "  total_records        INTEGER DEFAULT 0," +
        "  updated_at           INTEGER NOT NULL" +
        ")",

        // 基线归档表（approve 时旧基线移入）
        "CREATE TABLE IF NOT EXISTS archived_baselines (" +
        "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
        "  skill_id     TEXT NOT NULL," +
        "  fingerprint  TEXT NOT NULL," +
        "  version_tag  TEXT," +
        "  archived_at  INTEGER NOT NULL" +
        ")",

        "CREATE INDEX IF NOT EXISTS idx_archived_skill ON archived_baselines(skill_id)",

        // 图数据（整体 JSON，非逐行存边）
        "CREATE TABLE IF NOT EXISTS graph_snapshot (" +
        "  id           TEXT PRIMARY KEY DEFAULT 'current'," +
        "  graph_json   TEXT NOT NULL," +
        "  updated_at   INTEGER NOT NULL" +
        ")",

        // 检查点表
        "CREATE TABLE IF NOT EXISTS checkpoints (" +
        "  id           TEXT PRIMARY KEY," +
        "  name         TEXT NOT NULL," +
        "  timestamp    INTEGER NOT NULL," +
        "  passed       INTEGER NOT NULL," +
        "  failed       INTEGER NOT NULL," +
        "  diff         INTEGER NOT NULL," +
        "  full_report  TEXT NOT NULL" +
        ")"
    };
}
