package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.*;

@DisplayName("SQLite 外置数据库能力")
class SqliteDatabasePluginTest {
    @Test
    @DisplayName("市场描述符与运行期身份一致，命名空间能实际解析展示文案")
    void marketIdentityMatchesRuntimeAndResolvesBundle() throws Exception {
        var plugin = new SqliteDatabasePlugin();
        Properties descriptor = new Properties();
        try (var input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        assertThat(descriptor.getProperty("pixiv.display-namespace")).isEqualTo(plugin.displayNamespace());
        assertThat(descriptor.getProperty("pixiv.display-name-key")).isEqualTo(plugin.displayName());
        assertThat(descriptor.getProperty("pixiv.description-key")).isEqualTo(plugin.description());
        assertThat(descriptor.getProperty("pixiv.icon-key")).isEqualTo(plugin.iconKey());
        assertThat(descriptor.getProperty("pixiv.color-token")).isEqualTo(plugin.colorToken());
        var contribution = plugin.i18n().stream()
                .filter(value -> value.namespace().equals(plugin.displayNamespace())).findFirst().orElseThrow();
        var bundle = ResourceBundle.getBundle(
                contribution.baseName(), Locale.ENGLISH, plugin.getClass().getClassLoader());
        assertThat(bundle.getLocale().getLanguage()).isEqualTo(Locale.ENGLISH.getLanguage());
        assertThat(bundle.getString(plugin.displayName())).isNotBlank().isNotEqualTo(plugin.displayName());
        assertThat(bundle.getString(plugin.description())).isNotBlank().isNotEqualTo(plugin.description());
    }

    @TempDir Path directory;

    @Test
    @DisplayName("草稿解析和连接检查不会创建文件")
    void draftDoesNotCreateDatabase() throws Exception {
        Path file = directory.resolve("missing/main.db");
        var target = target(file);
        assertThat(file).doesNotExist();
        assertThatThrownBy(target::checkConnection).isInstanceOf(SQLException.class);
        assertThat(file.getParent()).doesNotExist();
    }

    @Test
    @DisplayName("真实连接保留 WAL 和锁等待，检查不初始化业务表")
    void connectionUsesWalAndBoundedBusyWait() throws Exception {
        Path file = directory.resolve("main.db");
        var target = target(file);
        try (var connection = target.openConnection(READ_WRITE); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("wal");
            }
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5000);
            }
        }
        target.checkConnection();
        try (var connection = target.openConnection(READ_ONLY); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM sqlite_schema WHERE type='table'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("只读检查保留既有 journal 模式和业务数据")
    void checkPreservesExistingJournalAndData() throws Exception {
        var target = target(directory.resolve("retained.db"));
        try (var connection = target.openConnection(READ_WRITE); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("CREATE TABLE retained(id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO retained VALUES (42)");
        }
        target.checkConnection();
        try (var connection = target.openConnection(EXISTING); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("delete");
            }
            try (var rows = statement.executeQuery("SELECT id FROM retained")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(42);
            }
        }
    }

    @Test
    @DisplayName("借用另一主数据库的连接即使附加所选文件也会被拒绝")
    void borrowedConnectionMustMatchFile() throws Exception {
        Path selectedFile = directory.resolve("selected.db");
        var selected = target(selectedFile);
        try (var connection = selected.openConnection(READ_WRITE)) {
            selected.requireConnectionTarget(connection);
        }
        try (var other = target(directory.resolve("other.db")).openConnection(READ_WRITE)) {
            try (var attach = other.prepareStatement("ATTACH DATABASE ? AS selected")) {
                attach.setString(1, selectedFile.toString());
                attach.execute();
            }
            assertThatThrownBy(() -> selected.requireConnectionTarget(other))
                    .isInstanceOf(SQLException.class).hasMessage("DATABASE_TARGET_MISMATCH");
        }
    }

    @Test
    @DisplayName("文件名中的 Unicode 和 URI 特殊字符按原路径读写")
    void specialCharactersRemainPartOfFileName() throws Exception {
        Path file = directory.resolve("数据库 空格%#&mode=ro.db");
        var target = new SqliteDatabasePlugin().resolve(
                new DatabaseConnectionSettings("jdbc:sqlite:" + file, "", "", directory.resolve("unused.db")));
        assertThat(target.localFile()).contains(file.toAbsolutePath().normalize());
        assertThat(target.description()).isEqualTo("sqlite: " + file.toAbsolutePath().normalize());
        try (var connection = target.openConnection(READ_WRITE); var statement = connection.createStatement()) {
            target.requireConnectionTarget(connection);
            statement.execute("CREATE TABLE retained(id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO retained VALUES (42)");
        }
        assertThat(file).isRegularFile();
        target.checkConnection();
        try (var connection = target.openConnection(READ_ONLY); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id FROM retained")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(42);
        }
        assertThat(directory.resolve("unused.db")).doesNotExist();
        Files.move(file, directory.resolve("closed.db"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 长路径快照支持既有模式 DDL、事务、WAL 和只读检查")
    void windowsLongPathPreservesAllConnectionModes() throws Exception {
        Path parent = directory.resolve("数据库 空格%#&mode=ro");
        while (parent.resolve("working.db").toString().length() < 320) parent = parent.resolve("p".repeat(80));
        Files.createDirectories(parent);
        Path file = Files.createFile(parent.resolve("working.db"));
        var target = target(file);
        assertThat(target.localFile()).contains(file.toAbsolutePath().normalize());
        assertThat(target.description()).isEqualTo("sqlite: " + file.toAbsolutePath().normalize());
        try (var connection = target.openConnection(EXISTING); var statement = connection.createStatement()) {
            target.requireConnectionTarget(connection);
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("delete");
            }
            statement.execute("CREATE TABLE retained(id INTEGER PRIMARY KEY)");
            connection.setAutoCommit(false);
            target.prepareTransaction(connection, false);
            statement.execute("INSERT INTO retained VALUES (42)");
            connection.commit();
            statement.execute("INSERT INTO retained VALUES (43)");
            connection.rollback();
        }
        try (var connection = target.openConnection(READ_WRITE); var statement = connection.createStatement()) {
            target.requireConnectionTarget(connection);
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("wal");
            }
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5000);
            }
            statement.execute("INSERT INTO retained VALUES (44)");
        }
        byte[] beforeCheck = Files.readAllBytes(file);
        target.checkConnection();
        assertThat(Files.readAllBytes(file)).isEqualTo(beforeCheck);
        try (var connection = target.openConnection(READ_ONLY); var statement = connection.createStatement()) {
            target.requireConnectionTarget(connection);
            try (var rows = statement.executeQuery("SELECT sum(id) FROM retained")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(86);
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO retained VALUES (99)"))
                    .isInstanceOf(SQLException.class);
        }
        Files.move(file, file.resolveSibling("closed.db"));
    }

    @Test
    @DisplayName("内部 Windows URI 不允许外部连接地址注入参数")
    void externalUriParametersRemainRejected() {
        Path file = directory.resolve("uncreated.db");
        for (String suffix : new String[] {"?vfs=win32-longpath", "?mode=ro", "?password=private-secret"}) {
            var settings = new DatabaseConnectionSettings("jdbc:sqlite:" + file + suffix, "", "", file);
            assertThatThrownBy(() -> new SqliteDatabasePlugin().resolve(settings))
                    .isInstanceOf(SQLException.class).hasMessage("DATABASE_CONFIGURATION_INVALID").hasNoCause();
        }
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("SQLite 拒绝远程凭据且不输出秘密")
    void rejectsInapplicableCredentials() {
        var settings = new DatabaseConnectionSettings("", "username", "private-secret", directory.resolve("main.db"));
        assertThatThrownBy(() -> new SqliteDatabasePlugin().resolve(settings)).isInstanceOf(SQLException.class)
                .hasMessage("DATABASE_CONFIGURATION_INVALID").hasNoCause();
    }

    private DatabaseConnectionTarget target(Path file) throws SQLException {
        return new SqliteDatabasePlugin().resolve(new DatabaseConnectionSettings("jdbc:sqlite:" + file, "", "", directory));
    }
}
