package io.github.agentassert4j.storage.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schema 迁移器 — PRAGMA user_version 盖戳契约版本（复审 H9 修复）。
 *
 * <p><b>演进规则（V100 契约）</b>：自 v1 起，schema 演进只允许两种形态——
 * ADD COLUMN 可空列 + 从 raw 列回填；或纯捕获层归一化调整。
 * 每个新版本在此追加一个迁移步骤，按版本号顺序执行，永不破坏性变更。</p>
 *
 * <p>v0 → v1 是唯一的例外：发布前开发期的旧库无 user_version（=0），
 * 且 v1 重命名了 {@code system_prompt_hash}、砍除了 checkpoints，
 * 无法增量迁移，采取整体重建（旧库只有维护者测试数据，重建零损失）。
 * 该例外不构成未来迁移的先例。</p>
 */
final class SchemaMigrator {

    private static final Logger LOG = Logger.getLogger(SchemaMigrator.class.getName());

    private SchemaMigrator() {}

    /**
     * 将数据库迁移到当前契约版本。
     * 幂等：已是目标版本时直接返回。
     */
    static void migrate(Connection connection) throws SQLException {
        int current = readUserVersion(connection);

        if (current > Schema.USER_VERSION) {
            // 未来版本库被旧代码打开——按契约旧代码不重解释新语义，直接拒绝
            throw new SQLException("Database schema version " + current
                    + " is newer than supported version " + Schema.USER_VERSION
                    + "; please upgrade agentassert4j");
        }

        if (current < 1) {
            migrateV0ToV1(connection);
        }
        // 后续迁移按版本顺序追加：if (current < 2) { migrateV1ToV2(connection); } ...

        if (current != Schema.USER_VERSION) {
            writeUserVersion(connection, Schema.USER_VERSION);
        }
    }

    /** 读取 PRAGMA user_version（旧库无戳，读出 0） */
    static int readUserVersion(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void writeUserVersion(Connection connection, int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA user_version = " + version);
        }
    }

    /**
     * v0（发布前开发期）→ v1：整体重建。
     * 旧库的 interactions 列语义已变化（改名/砍表），无可增量迁移的公共形状。
     */
    private static void migrateV0ToV1(Connection connection) throws SQLException {
        boolean hasLegacyData = false;
        for (String table : Schema.LEGACY_V0_TABLES) {
            if (tableExists(connection, table)) {
                hasLegacyData = true;
                break;
            }
        }

        if (hasLegacyData) {
            LOG.log(Level.WARNING,
                    "Legacy pre-v1 database detected; rebuilding schema at v1"
                    + " (pre-release dev data is not migrated)");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = OFF");
                for (String table : Schema.LEGACY_V0_TABLES) {
                    if (tableExists(connection, table)) {
                        stmt.execute("DROP TABLE IF EXISTS " + table);
                    }
                }
            }
        }

        try (Statement stmt = connection.createStatement()) {
            for (String ddl : Schema.ALL_DDL) {
                stmt.execute(ddl);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
