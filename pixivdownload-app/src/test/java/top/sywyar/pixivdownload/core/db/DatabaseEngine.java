package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** 测试夹具选择本仓真实驱动，不参与生产准入。 */
public enum DatabaseEngine {
    SQLITE, POSTGRESQL;

    public String databaseId() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DatabaseEngine parse(String value) {
        if (value != null) {
            for (DatabaseEngine engine : values()) {
                if (engine.databaseId().equals(value.trim().toLowerCase(Locale.ROOT))) {
                    return engine;
                }
            }
        }
        throw new IllegalArgumentException(MessageBundles.get("core.db.invalid-config", "database.engine"));
    }

    public static DatabaseEngine detect(Connection connection) throws SQLException {
        return switch (connection.getMetaData().getDatabaseProductName()) {
            case "SQLite" -> SQLITE;
            case "PostgreSQL" -> POSTGRESQL;
            default -> throw new SQLException(MessageBundles.get("core.db.unsupported-product"));
        };
    }
}
