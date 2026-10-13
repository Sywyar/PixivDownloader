package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.*;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

/** PostgreSQL 关联触发器与权威正文派生检索。 */
final class PostgresStorageFeatures {
    private PostgresStorageFeatures() {}
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+|\"|[()]", "").toLowerCase(Locale.ROOT);
    }
    static void cleanup(Connection c, Cleanup rule) throws SQLException {
        String body = "BEGIN ";
        for (var d : rule.dependents()) body += "DELETE FROM " + quote(d.table()) + " WHERE " + quote(d.key())
                + " = NEW." + quote(rule.key()) + "; ";
        body += "RETURN NEW; END";
        String schema;
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT current_schema()")) { r.next(); schema = r.getString(1); }
        try (var s = c.prepareStatement("SELECT p.prosrc, p.prorettype='trigger'::regtype, p.pronargs, l.lanname"
                + " FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang"
                + " WHERE n.nspname=? AND p.proname=?")) {
            s.setString(1, schema); s.setString(2, rule.name());
            try (var r = s.executeQuery()) {
                while (r.next()) if (!r.getBoolean(2) || r.getInt(3) != 0 || !"plpgsql".equals(r.getString(4))
                        || !normalized(r.getString(1)).equals(normalized(body)))
                    throw new SQLException("STORAGE_FEATURE_MISMATCH", "0A000");
            }
        }
        String trigger = "CREATE TRIGGER " + quote(rule.name()) + " AFTER UPDATE OF " + quote(rule.changedColumn())
                + " ON " + quote(rule.table()) + " FOR EACH ROW WHEN (NEW." + quote(rule.changedColumn()) + "="
                + rule.value() + ") EXECUTE FUNCTION " + quote(rule.name()) + "()";
        try (var s = c.prepareStatement("SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid"
                + " JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=? AND t.tgname=?")) {
            s.setString(1, schema); s.setString(2, rule.name());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    String actual = normalized(r.getString(1)).replace(schema.toLowerCase(Locale.ROOT) + ".", "");
                    if (!actual.equals(normalized(trigger))) throw new SQLException("STORAGE_FEATURE_MISMATCH", "0A000");
                }
            }
        }
        try (var s = c.createStatement()) {
            s.execute("CREATE OR REPLACE FUNCTION " + quote(rule.name()) + "() RETURNS trigger LANGUAGE plpgsql"
                    + " SET search_path FROM CURRENT AS $storage$ " + body + " $storage$");
            s.execute(trigger.replace("CREATE TRIGGER ", "CREATE OR REPLACE TRIGGER "));
        }
    }
    static List<Long> search(Connection c, TextIndex index, String term) throws SQLException {
        if (term.isEmpty()) return List.of();
        boolean unicode = term.codePointCount(0, term.length()) >= 3;
        String text = quote(index.content());
        String comparison = unicode ? "strpos(lower(" + text + " COLLATE \"pg_c_utf8\"),lower(? COLLATE \"pg_c_utf8\"))>0"
                : "translate(" + text + ",'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')"
                + " LIKE translate(?,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz') ESCAPE '!'";
        var ids = new ArrayList<Long>();
        try (var s = c.prepareStatement("SELECT " + quote(index.key()) + " FROM " + quote(index.table())
                + " WHERE " + quote(index.activeColumn()) + "=? AND " + comparison)) {
            s.setLong(1, index.activeValue());
            s.setString(2, unicode ? term : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            try (var r = s.executeQuery()) { while (r.next()) ids.add(r.getLong(1)); }
        }
        return ids;
    }
}
