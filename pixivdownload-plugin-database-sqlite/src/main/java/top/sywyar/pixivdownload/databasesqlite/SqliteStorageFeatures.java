package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.*;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

/** SQLite 的可再生正文索引与声明式关联清理。 */
final class SqliteStorageFeatures {
    private SqliteStorageFeatures() {}
    private static String normalized(String sql) {
        return sql.replaceAll("(?i)if\\s+not\\s+exists", "").replaceAll("\\s+|\"", "").toLowerCase(Locale.ROOT);
    }
    private static void requireObject(Connection c, String name, String expected, boolean allowMissing) throws SQLException {
        try (var s = c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
            s.setString(1, name);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    if (allowMissing) return;
                    throw new SQLException("STORAGE_FEATURE_MISSING", "0A000");
                }
                String actual = r.getString(1);
                if (actual == null || !normalized(actual).equals(normalized(expected)))
                    throw new SQLException("STORAGE_FEATURE_MISMATCH", "0A000");
            }
        }
    }
    static void cleanup(Connection c, Cleanup rule) throws SQLException {
        var sql = new StringBuilder("CREATE TRIGGER IF NOT EXISTS ").append(quote(rule.name()))
                .append(" AFTER UPDATE OF ").append(quote(rule.changedColumn())).append(" ON ").append(quote(rule.table()))
                .append(" WHEN NEW.").append(quote(rule.changedColumn())).append(" = ").append(rule.value()).append(" BEGIN ");
        for (var d : rule.dependents()) sql.append("DELETE FROM ").append(quote(d.table())).append(" WHERE ")
                .append(quote(d.key())).append(" = NEW.").append(quote(rule.key())).append("; ");
        sql.append("END");
        requireObject(c, rule.name(), sql.toString(), true);
        try (var s = c.createStatement()) { s.execute(sql.toString()); }
    }
    private static String create(TextIndex index) {
        return "CREATE VIRTUAL TABLE IF NOT EXISTS " + quote(index.name()) + " USING fts5(content, tokenize='trigram')";
    }
    static TextIndexMode initialize(Connection c, TextIndex index) throws SQLException {
        requireObject(c, index.name(), create(index), true);
        try (var s = c.createStatement()) { s.execute(create(index)); }
        return TextIndexMode.MATERIALIZED;
    }
    static TextIndexMode refresh(Connection c, TextIndex index, Long key) throws SQLException {
        requireObject(c, index.name(), create(index), false);
        String source = quote(index.table()), derived = quote(index.name()), id = quote(index.key());
        if (key != null) {
            try (var s = c.prepareStatement("DELETE FROM " + derived + " WHERE rowid=?")) {
                s.setLong(1, key); s.executeUpdate();
            }
        } else {
            try (var s = c.prepareStatement("DELETE FROM " + derived + " WHERE rowid NOT IN (SELECT " + id
                    + " FROM " + source + " WHERE " + quote(index.activeColumn()) + "=?)")) {
                s.setLong(1, index.activeValue()); s.executeUpdate();
            }
        }
        try (var s = c.prepareStatement("INSERT INTO " + derived + "(rowid,content) SELECT " + id
                + ",COALESCE(" + quote(index.content()) + ",'') FROM " + source + " WHERE "
                + quote(index.activeColumn()) + "=?" + (key == null
                ? " AND " + id + " NOT IN (SELECT rowid FROM " + derived + ")" : " AND " + id + "=?"))) {
            s.setLong(1, index.activeValue());
            if (key != null) s.setLong(2, key);
            s.executeUpdate();
        }
        return TextIndexMode.MATERIALIZED;
    }
    static List<Long> search(Connection c, TextIndex index, String term) throws SQLException {
        if (term.isEmpty()) return List.of();
        boolean indexed = term.codePointCount(0, term.length()) >= 3;
        if (indexed) requireObject(c, index.name(), create(index), false);
        String sql = "SELECT n." + quote(index.key()) + " FROM " + quote(index.table()) + " n"
                + (indexed ? " JOIN " + quote(index.name()) + " f ON f.rowid=n." + quote(index.key()) : "")
                + " WHERE n." + quote(index.activeColumn()) + "=? AND "
                + (indexed ? quote(index.name()) + " MATCH ?" : "lower(n." + quote(index.content()) + ") LIKE lower(?) ESCAPE '!'");
        var ids = new ArrayList<Long>();
        try (var s = c.prepareStatement(sql)) {
            s.setLong(1, index.activeValue());
            s.setString(2, indexed ? "\"" + term.replace("\"", "\"\"") + "\"" :
                    "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            try (var r = s.executeQuery()) { while (r.next()) ids.add(r.getLong(1)); }
        }
        return ids;
    }
}
