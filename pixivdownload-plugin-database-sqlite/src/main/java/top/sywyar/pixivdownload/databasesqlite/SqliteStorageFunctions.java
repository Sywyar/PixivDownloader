package top.sywyar.pixivdownload.databasesqlite;

import org.sqlite.Function;
import org.sqlite.SQLiteConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;

/** SQLite 没有 IANA 时区目录；提供者使用进程的同一 JDK 时区规则计算月份。 */
final class SqliteStorageFunctions {
    private SqliteStorageFunctions() {}
    static void install(Connection connection) throws SQLException {
        Function.create(connection.unwrap(SQLiteConnection.class), "storage_epoch_month", new Function() {
            @Override protected void xFunc() throws SQLException {
                if (value_type(0) == 5) { result(); return; }
                try {
                    var date = Instant.ofEpochMilli(value_long(0)).atZone(ZoneId.of(value_text(1)));
                    result(YearMonth.from(date).toString());
                } catch (DateTimeException invalid) { error("STORAGE_TIME_INVALID"); }
            }
        }, 2, Function.FLAG_DETERMINISTIC);
    }
}
