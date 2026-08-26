package io.github.agentassert4j.storage.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * schema 契约版本管理（PRAGMA user_version）。
 *
 * <p>当前契约版本固定为 1：首次初始化执行建表并盖戳，已是当前版本直接返回。
 * 版本高于本代码支持值时拒绝打开——旧代码不得静默误读新语义。</p>
 */
final class SchemaMigrator {

    private SchemaMigrator() {
    }

    /**
     * 将库迁移到当前契约版本；幂等
     */
    static void migrate(Connection connection) throws SQLException {
        int current = readUserVersion(connection);

        if (current > Schema.USER_VERSION) {
            throw new SQLException("Database schema version " + current
                    + " is newer than supported version " + Schema.USER_VERSION
                    + "; please upgrade agentassert4j");
        }

        if (current == Schema.USER_VERSION) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            for (String ddl : Schema.ALL_DDL) {
                stmt.execute(ddl);
            }
            stmt.execute("PRAGMA user_version = " + Schema.USER_VERSION);
        }
    }

    private static int readUserVersion(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
