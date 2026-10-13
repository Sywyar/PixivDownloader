package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("SQLite 提供者拥有主库位置及历史文件组")
class SqliteStorageLocationTest {
    @TempDir Path directory;
    private final SqliteDatabasePlugin provider = new SqliteDatabasePlugin();
    private DatabaseConnectionSettings settings(Path... legacy) {
        return new DatabaseConnectionSettings("", "", "", directory.resolve("data"), List.of(legacy));
    }

    @Test
    @DisplayName("默认文件由提供者映射，检查不建目录，显式文件不触发旧库搬迁")
    void mapsDefaultAndExplicitFilesWithoutInspectionWrites() throws Exception {
        var settings = settings(directory.resolve("old"));
        assertThat(provider.resolve(settings).localFile()).contains(directory.resolve("data/pixiv_download.db"));
        assertThat(directory.resolve("data")).doesNotExist();
        var explicit = provider.localFileSettings(settings, directory.resolve("chosen.db"));
        provider.prepareStorage(explicit);
        assertThat(provider.resolve(explicit).localFile()).contains(directory.resolve("chosen.db"));
        assertThat(directory.resolve("chosen.db")).doesNotExist();
        assertThat(directory.resolve("data")).doesNotExist();
    }

    @Test
    @DisplayName("主文件与已提交 WAL 搬迁后仍能读取完整数据")
    void retainsCommittedWalData() throws Exception {
        Path legacy = Files.createDirectories(directory.resolve("旧目录 🌸"));
        Path origin = directory.resolve("original.db");
        var original = provider.resolve(provider.localFileSettings(settings(), origin));
        try (var connection = original.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO facts VALUES(1,'已提交 WAL')");
            assertThat(Path.of(origin + "-wal")).exists();
            for (String suffix : List.of("", "-wal", "-shm")) {
                Path source = Path.of(origin + suffix);
                if (Files.exists(source)) Files.copy(source, legacy.resolve("pixiv_download.db" + suffix));
            }
        }
        var settings = settings(legacy);
        assertThat(provider.resolve(settings).localFile()).contains(legacy.resolve("pixiv_download.db"));
        provider.prepareStorage(settings);
        var target = provider.resolve(settings);
        assertThat(target.localFile()).contains(directory.resolve("data/pixiv_download.db"));
        for (String suffix : List.of("", "-wal", "-shm"))
            assertThat(legacy.resolve("pixiv_download.db" + suffix)).doesNotExist();
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT value FROM facts")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("已提交 WAL");
        }
    }

    @Test
    @DisplayName("多个旧位置或目标 WAL 冲突时在移动任何文件前拒绝")
    void rejectsConflictsBeforeMovingAnySource() throws Exception {
        Path first = Files.createDirectories(directory.resolve("first"));
        Path second = Files.createDirectories(directory.resolve("second"));
        Files.writeString(first.resolve("pixiv_download.db"), "first");
        Files.writeString(second.resolve("pixiv_download.db"), "second");
        assertThatThrownBy(() -> provider.prepareStorage(settings(first, second))).isInstanceOf(java.sql.SQLException.class);
        assertThat(first.resolve("pixiv_download.db")).hasContent("first");
        assertThat(second.resolve("pixiv_download.db")).hasContent("second");
        assertThat(directory.resolve("data")).doesNotExist();
        Files.createDirectories(directory.resolve("data"));
        Files.writeString(directory.resolve("data/pixiv_download.db-wal"), "orphan");
        assertThatThrownBy(() -> provider.prepareStorage(settings(first))).isInstanceOf(java.sql.SQLException.class);
        assertThat(first.resolve("pixiv_download.db")).hasContent("first");
        assertThat(directory.resolve("data/pixiv_download.db-wal")).hasContent("orphan");
        assertThat(directory.resolve("data/pixiv_download.db")).doesNotExist();
    }
}
