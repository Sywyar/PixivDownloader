package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopUiToolsTest {

    @TempDir
    Path tempDir;

    @Test
    @org.junit.jupiter.api.DisplayName("目录扫描在累计条目或路径字符超限时拒绝，不返回截断结果")
    void boundsClassifierScan() throws Exception {
        Files.createDirectory(tempDir.resolve("1"));
        Files.createDirectory(tempDir.resolve("2"));
        var tools = new DesktopUiTools(() -> "");
        assertThatThrownBy(() -> tools.listClassifierPaths(tempDir, true, 1, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CLASSIFIER_DIRECTORY_LIMIT");
        assertThatThrownBy(() -> tools.listClassifierPaths(tempDir, true, 100, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CLASSIFIER_DIRECTORY_LIMIT");
        assertThat(tools.listClassifierPaths(tempDir, true, 2, Long.MAX_VALUE)).hasSize(2);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("分类目录混合数字及文字名称时保留全部目录并按稳定顺序排列")
    void sortsMixedClassifierFolderNames() throws Exception {
        for (String name : List.of("2", "10", "1a", "01", "1", "2147483648", "中文")) {
            Files.createDirectory(tempDir.resolve(name));
        }
        Files.writeString(tempDir.resolve("ignored.txt"), "not a directory", StandardCharsets.UTF_8);

        assertThat(new DesktopUiTools(() -> "").listImageClassifierFolders(tempDir))
                .extracting(path -> path.getFileName().toString())
                .containsExactly("01", "1", "2", "10", "2147483648", "1a", "中文");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1", "unknown", "9223372036854775808"})
    @org.junit.jupiter.api.DisplayName("无法取得正数作品编号的目录返回空结果")
    void rejectsUnresolvedClassifierArtwork(String name) {
        assertThat(new DesktopUiTools(() -> "").resolveImageClassifierArtwork(tempDir.resolve(name), null))
                .isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("文件系统根目录没有作品编号时返回空结果")
    void rejectsClassifierRootWithoutArtworkId() {
        assertThat(new DesktopUiTools(() -> "").resolveImageClassifierArtwork(tempDir.getRoot(), null))
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1", "1.5", "18446744073709551625"})
    @org.junit.jupiter.api.DisplayName("服务有效正数编号优先，非法编号回退目录，无法解析时不查询详情")
    void resolvesOnlyPositiveServerArtworkIds(String invalidId) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var serverArtworkId = new java.util.concurrent.atomic.AtomicReference<>(invalidId);
        server.createContext("/api/downloaded/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            byte[] body = (path.endsWith("by-move-folder")
                    ? "{\"artworkId\":" + serverArtworkId.get() + "}" : "{\"title\":\"Example\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var target = new DesktopUiToolHost.ImageClassifierServer(true, "http://localhost:" + server.getAddress().getPort());
            var tools = new DesktopUiTools(() -> "");
            assertThat(tools.resolveImageClassifierArtwork(tempDir.resolve("42"), target))
                    .contains(new DesktopUiToolHost.ImageClassifierArtwork(42L, "Example", null));
            serverArtworkId.set("-1");
            assertThat(tools.resolveImageClassifierArtwork(tempDir.resolve("0"), target)).isEmpty();
            serverArtworkId.set("99");
            assertThat(tools.resolveImageClassifierArtwork(tempDir.resolve("42"), target))
                    .contains(new DesktopUiToolHost.ImageClassifierArtwork(99L, "Example", null));
            assertThat(requests).containsExactly("/api/downloaded/by-move-folder", "/api/downloaded/42",
                    "/api/downloaded/by-move-folder", "/api/downloaded/by-move-folder", "/api/downloaded/99");
        } finally {
            server.stop(0);
        }
    }

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
        DesktopUiToolHost.FolderCheckResult before = tools.checkArtworkFolders(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString());
        assertThat(before.total()).isEqualTo(3);
        assertThat(before.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(2L);

        tools.updateArtworkFolder(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString(), 2, false, existing.toString());
        assertThat(tools.checkArtworkFolders(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString()).inaccessible()).isEmpty();
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
        var result = tools.checkArtworkFolders(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString());
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.inaccessible()).extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(3L, 4L);
        assertThat(Path.of(result.inaccessible().get(0).path())).isEqualTo(tempDir.resolve("gone"));
        assertThat(result.inaccessible().get(1).path()).isEqualTo("{999}/gone");
        tools.updateArtworkFolder(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString(), 4, true, existing.toString());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT folder, move_folder FROM artworks WHERE artwork_id = 4")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("folder")).isEqualTo("{999}/gone");
            assertThat(rows.getString("move_folder")).isEqualTo("{2}");
        }
        assertThat(tools.checkArtworkFolders(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database), tempDir.toString()).inaccessible())
                .extracting(DesktopUiToolHost.FolderArtwork::artworkId).containsExactly(3L);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("桌面 SDK 在分类移动退役后保留源文件、目标和数据库登记")
    void sdkRejectsRetiredClassifierMovesBeforeTouchingFilesOrRegistration() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("42"));
        Path image = Files.writeString(source.resolve("42_p0.jpg"), "original");
        Path sidecar = Files.writeString(source.resolve("42.meta.json"), "{}");
        Path target = tempDir.resolve("target");
        Path database = tempDir.resolve("artworks.db");
        var databaseTarget = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(database);
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + database);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, folder TEXT, moved INTEGER, move_folder TEXT)");
        jdbc.update("INSERT INTO artworks VALUES (?, ?, ?, ?)", 42, source.toString(), 0, null);
        var before = jdbc.queryForMap("SELECT * FROM artworks WHERE artwork_id = 42");

        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(410, -1);
            exchange.close();
        });
        server.start();
        try {
            var host = new AppDesktopUiHost(server.getAddress().getPort(),
                    org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile.class),
                    () -> dataSource, null, databaseTarget);
            DesktopUiToolHost sdk = host;
            String url = "http://localhost:" + server.getAddress().getPort();
            for (boolean online : List.of(true, false)) {
                assertThatThrownBy(() -> sdk.classifyImageFolder(source, List.of(image), 42, target,
                        new DesktopUiToolHost.ImageClassifierServer(online, url),
                        (detail, folder) -> { throw new AssertionError("Unexpected source cleanup"); }))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessage(host.message("gui.image-classifier.move-retired"));
                assertThat(target).doesNotExist();
                assertThat(Files.readString(image)).isEqualTo("original");
                assertThat(Files.readString(sidecar)).isEqualTo("{}");
                assertThat(jdbc.queryForMap("SELECT * FROM artworks WHERE artwork_id = 42")).isEqualTo(before);
            }
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private static String sql(Path path) {
        return path.toString().replace("'", "''");
    }
}
