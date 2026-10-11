package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("PostgreSQL 桌面目录工具")
class PostgresDesktopToolsTest {
    @TempDir Path directory;

    @Test
    @DisplayName("借用宿主连接检查和修复编码路径，不关闭连接或复活软删除记录")
    void borrowedConnectionChecksPathsAndEncodesRepairs() throws Exception {
        try (var database = new PostgresTestDatabase(); var connection = database.pool().getConnection()) {
            DatabaseInitializer.prepareOffline(connection);
            var target = DatabaseTarget.resolve(database.properties(), "unused");
            var jdbc = new JdbcTemplate(database.pool());
            Path existing = Files.createDirectory(directory.resolve("作品 🌸"));
            jdbc.update("INSERT INTO path_prefixes(id,path) VALUES(7,?)", directory.toString().replace('\\', '/'));
            jdbc.update("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,deleted) VALUES"
                    + "(1,'ok','{7}/作品 🌸',1,'png',1700000000001,0),"
                    + "(2,'missing','{7}/missing',1,'png',1700000000002,0),"
                    + "(3,'deleted','{7}/missing',1,'png',1700000000003,1)");
            var tools = new DesktopUiTools(() -> "http://localhost");
            var before = tools.checkArtworkFolders(connection, directory.toString());
            assertThat(before.total()).isEqualTo(2);
            assertThat(before.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(2L);
            tools.updateArtworkFolder(connection, directory.toString(), 2, false, existing.toString());
            assertThat(connection.isClosed()).isFalse();
            assertThat(jdbc.queryForObject("SELECT folder FROM artworks WHERE artwork_id=2", String.class)).isEqualTo("{7}/作品 🌸");
            assertThat(tools.checkArtworkFolders(target, directory.toString()).inaccessible()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT deleted FROM artworks WHERE artwork_id=3", Long.class)).isOne();
        }
    }
}
