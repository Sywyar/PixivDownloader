package top.sywyar.pixivdownload.plugin.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件私有 SQLite 数据源")
class AppPluginDataSourceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRuntimeOverride() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    @Test
    @DisplayName("数据库固定落在 owner 数据目录且可独立建表")
    void opensPrivateDatabaseInsideOwnerDataDirectory() throws Exception {
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.toString());

        try (AppPluginDataSource dataSource = new AppPluginDataSource("sample-plugin");
             var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE sample_value (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO sample_value(id, value) VALUES (1, 'owned')");
            try (var result = statement.executeQuery("SELECT value FROM sample_value WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("owned");
            }
        }

        assertThat(Files.isRegularFile(tempDir.resolve("sample-plugin/plugin.db"))).isTrue();
    }

    @Test
    @DisplayName("非法 owner 不得逃逸数据目录")
    void rejectsOwnerPathTraversal() {
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.toString());

        assertThatThrownBy(() -> new AppPluginDataSource("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.getParent().resolve("outside/plugin.db")).doesNotExist();
    }
}
