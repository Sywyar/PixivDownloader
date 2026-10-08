package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopUiToolsTest {

    @TempDir
    Path tempDir;

    @Test
    @org.junit.jupiter.api.DisplayName("分类器自动地址跟随后端，显式指定的其它实例地址仍优先")
    void classifierUsesCurrentInstanceUnlessConfigured() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/download/status", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var address = new java.util.concurrent.atomic.AtomicReference<>(base);
            var tools = new DesktopUiTools(address::get);
            assertThat(tools.checkImageClassifierServer("").available()).isTrue();
            assertThat(tools.checkImageClassifierServer("").url()).isEqualTo(base);
            address.set("http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(tools.checkImageClassifierServer("").url()).isEqualTo(address.get());
            assertThat(tools.checkImageClassifierServer(base).url()).isEqualTo(base);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("检查真实目录并修复缺失路径，兼容未建立前缀表的数据库")
    void checksAndUpdatesArtworkFoldersThroughTheHostEngine() throws Exception {
        Path database = tempDir.resolve("artworks.db");
        Path existing = Files.createDirectory(tempDir.resolve("existing"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT, folder TEXT,"
                    + " moved INTEGER, move_folder TEXT, deleted INTEGER, time INTEGER)");
            statement.execute("INSERT INTO artworks VALUES (1, 'ok', '" + sql(existing) + "', 0, NULL, 0, 2)");
            statement.execute("INSERT INTO artworks VALUES (2, 'missing', '" + sql(tempDir.resolve("missing"))
                    + "', 0, NULL, 0, 1)");
            statement.execute("INSERT INTO artworks VALUES (3, 'moved without destination', '" + sql(existing)
                    + "', 1, NULL, 0, 0)");
        }

        DesktopUiTools tools = new DesktopUiTools(() -> "http://localhost:8123");
        DesktopUiToolHost.FolderCheckResult before = tools.checkArtworkFolders(database, tempDir.toString());
        assertThat(before.total()).isEqualTo(3);
        assertThat(before.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(2L);

        tools.updateArtworkFolder(database, tempDir.toString(), 2, false, existing.toString());
        assertThat(tools.checkArtworkFolders(database, tempDir.toString()).inaccessible()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("目录检查解析符号根和编号前缀，修正移动目录时按最长前缀编码")
    void checksEncodedPathsAndEncodesRepairs() throws Exception {
        Path database = tempDir.resolve("encoded.db");
        Path existing = Files.createDirectory(tempDir.resolve("existing"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE path_prefixes (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE)");
            statement.execute("INSERT INTO path_prefixes VALUES (1, '" + sql(tempDir) + "'), (2, '" + sql(existing) + "')");
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT, folder TEXT,"
                    + " moved INTEGER, move_folder TEXT, deleted INTEGER, time INTEGER)");
            statement.execute("INSERT INTO artworks VALUES (1, 'symbolic', '{0}/existing', 0, NULL, 0, 5),"
                    + "(2, 'moved', '{0}/gone', 1, '{1}/existing', 0, 4),"
                    + "(3, 'missing', '{0}/gone', 0, NULL, 0, 3),"
                    + "(4, 'unknown', '{999}/gone', 1, '{999}/gone', 0, 2),"
                    + "(5, 'deleted', '{0}/gone', 0, NULL, 1, 1)");
        }
        DesktopUiTools tools = new DesktopUiTools(() -> "http://localhost:8123");
        var result = tools.checkArtworkFolders(database, tempDir.toString());
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(3L, 4L);
        assertThat(Path.of(result.inaccessible().get(0).path())).isEqualTo(tempDir.resolve("gone"));
        assertThat(result.inaccessible().get(1).path()).isEqualTo("{999}/gone");
        tools.updateArtworkFolder(database, tempDir.toString(), 4, true, existing.toString());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT folder, move_folder FROM artworks WHERE artwork_id = 4")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("folder")).isEqualTo("{999}/gone");
            assertThat(rows.getString("move_folder")).isEqualTo("{2}");
        }
        assertThat(tools.checkArtworkFolders(database, tempDir.toString()).inaccessible())
                .extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(3L);
    }

    @Test
    void classifiesImagesAndMovesTheWorkSidecar() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("123"));
        Path image = Files.writeString(source.resolve("image.jpg"), "image");
        Path sidecar = Files.writeString(source.resolve("123.meta.json"), "{}");
        Path target = tempDir.resolve("target");
        DesktopUiTools tools = new DesktopUiTools(() -> "http://localhost:8123");

        Path destination = tools.classifyImageFolder(
                source, List.of(image), 123L, target,
                new DesktopUiToolHost.ImageClassifierServer(false, "http://localhost:6999"),
                (detail, folder) -> {
                    throw new AssertionError("Deletion should not fail: " + detail);
                });

        assertThat(destination).isEqualTo(target);
        assertThat(target.resolve(image.getFileName())).exists();
        assertThat(target.resolve(sidecar.getFileName())).exists();
        assertThat(source).doesNotExist();
    }

    private static String sql(Path path) {
        return path.toString().replace("'", "''");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("分类移动完整格式组和缩略图，不依赖目录中的媒体清单")
    void classificationMovesAllFormatsWithoutManifest() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("42"));
        var suffixes = List.of(".jpg", ".png", ".webp", ".gif", ".apng", ".mp4", ".zip", ".frames.properties", "_thumb.jpg");
        for (String suffix : suffixes) Files.writeString(source.resolve("42_p0" + suffix), suffix);
        Path target = tempDir.resolve("target");
        new DesktopUiTools(() -> "http://localhost:8123").classifyImageFolder(source, List.of(source.resolve("42_p0.jpg")), 42L, target,
                new DesktopUiToolHost.ImageClassifierServer(false, "http://localhost:6999"),
                (detail, folder) -> { throw new AssertionError(detail); });
        for (String suffix : suffixes) assertThat(Files.readString(target.resolve("42_p0" + suffix))).isEqualTo(suffix);
        assertThat(source).doesNotExist();
        try (var files = Files.list(target)) { assertThat(files.count()).isEqualTo(suffixes.size()); }
    }
}
