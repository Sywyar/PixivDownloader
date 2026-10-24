package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("数据库不可用时真实恢复壳启动")
class DatabaseUnavailableBootContextTest {
    @TempDir Path directory;

    @Test
    @DisplayName("无可用连接仍建立恢复壳且不创建空数据库")
    void startsRecoveryShellWithoutAnyDatabaseConnection() throws Exception {
        Path archive = directory.resolve("downloads/_archives/retained.zip");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "unavailable database must not authorize cleanup");
        Path missingOriginal = directory.resolve("downloads/deleted-before-crash.txt");
        Path backupDirectory = Files.createDirectories(directory.resolve("data/delete-staging/legacy"));
        Path backup = Files.writeString(backupDirectory.resolve("0.bak"), "retained backup");
        top.sywyar.pixivdownload.config.DeleteStagingManifestFixture.write(backupDirectory,
                java.util.List.of(new top.sywyar.pixivdownload.config.DeleteStagingManifest.Entry(missingOriginal, "0.bak")));
        Path config = directory.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(config).write("download.root-folder",
                directory.resolve("downloads").toString());
        Map<String, String> paths = Map.of(
                RuntimeFiles.CONFIG_DIR_PROPERTY, directory.resolve("config").toString(),
                RuntimeFiles.STATE_DIR_PROPERTY, directory.resolve("state").toString(),
                RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString(),
                RuntimeFiles.PLUGINS_DIR_PROPERTY, directory.resolve("plugins").toString(),
                "pixivdownload.plugin-dev.enabled", "false");
        Map<String, String> previous = new java.util.HashMap<>();
        paths.forEach((key, value) -> previous.put(key, System.getProperty(key)));
        try {
            paths.forEach(System::setProperty);
            try (var context = PixivDownloadApplication.start(new String[]{
                            "--server.port=0", "--setup.browser.auto-open=false", "--update.auto-check=false",
                            "--spring.config.import=optional:" + directory.resolve("config/config.yaml").toUri(),
                            "--download.root-folder=" + directory.resolve("downloads")})) {
                assertThat(context.isActive()).isTrue();
                assertThat(context.getBean(RecoveryModeService.class).isActive()).isTrue();
                var availability = context.getBean(top.sywyar.pixivdownload.core.database.DatabaseAvailability.class);
                assertThat(availability.available()).isFalse();
                assertThat(context.getBean(top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository.class)
                        .find(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, 1)).isEmpty();
                assertThat(context.getBean(top.sywyar.pixivdownload.core.stats.StatsQueryStore.class).topAuthors(10)).isEmpty();
                var tasks = context.getBean(top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore.class);
                assertThat(tasks.findAll()).isEmpty();
                assertThatThrownBy(() -> tasks.create(null))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThatThrownBy(() -> tasks.tryQueueNow(1, 0, "unused"))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThatThrownBy(() -> context.getBean(top.sywyar.pixivdownload.core.work.service.WorkDeletionService.class)
                        .deleteAll(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, java.util.List.of(1L)))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThatThrownBy(() -> context.getBean(top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator.class).runManually())
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThatThrownBy(() -> context.getBean(top.sywyar.pixivdownload.quota.UserQuotaService.class)
                        .triggerAdminArchive(java.util.List.of(archive.getParent())))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThat(context.getBean(top.sywyar.pixivdownload.quota.UserQuotaService.class).listAdminArchives()).isEmpty();
                assertThat(context.getBean(RecoveryModeService.class).decision().firstReason().orElseThrow().messageKey())
                        .isEqualTo("plugin.recovery.database-unavailable");
                assertThat(archive).exists();
                var privateDatabase = context.getBean(top.sywyar.pixivdownload.plugin.storage.PluginDatabaseFactory.class)
                        .create("isolated-private-fixture");
                assertThat(privateDatabase.available()).isFalse();
                assertThatThrownBy(() -> privateDatabase.transaction(session -> {
                    throw new AssertionError("unavailable callback must not execute");
                })).isInstanceOf(SQLNonTransientConnectionException.class);
                assertThat(directory.resolve("data/isolated-private-fixture")).doesNotExist();
                assertThat(missingOriginal).doesNotExist();
                assertThat(backup).hasContent("retained backup");
                assertThat(backupDirectory.resolve("manifest.properties")).exists();
                var filesService = context.getBean(top.sywyar.pixivdownload.core.asset.StagedFileDeletion.class);
                var callback = new java.util.concurrent.atomic.AtomicBoolean();
                assertThatThrownBy(() -> filesService.deleteAtomically(java.util.List.of(archive), () -> callback.set(true)))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThat(callback).isFalse();
                assertThat(archive).exists();
                var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
                String base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
                for (String page : java.util.List.of("/", "/index.html", "/plugin-manage.html", "/pixiv-gallery.html")) {
                    var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + page)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(302);
                    assertThat(java.net.URI.create(base).resolve(response.headers().firstValue("Location").orElseThrow()))
                            .isEqualTo(java.net.URI.create(base + "/plugin-market.html"));
                }
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/gui/path-prefixes")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).contains("plugin.recovery.database-unavailable");
                var market = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/plugins/status")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(market.statusCode()).isEqualTo(503);
                assertThat(market.body()).contains("auth.setup-required");
                var setup = context.getBean(top.sywyar.pixivdownload.setup.SetupService.class);
                setup.init("database-recovery-fixture", "isolated-fixture-password", "solo");
                market = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/plugins/status")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(market.statusCode()).isEqualTo(401);
                String cookie = "pixiv_session=" + setup.createSession(false);
                for (String repair : java.util.List.of("/plugin-market.html", "/api/plugins/status", "/api/plugin-market/repositories", "/api/plugins/database")) {
                    var repairResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + repair))
                                    .header("Cookie", cookie).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(repairResponse.statusCode()).as(repair).isEqualTo(200);
                }
                var unauthorizedDatabase = client.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(base + "/api/plugins/database")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(unauthorizedDatabase.statusCode()).isEqualTo(401);
                byte[] savedConfig = Files.readAllBytes(config);
                for (String origin : java.util.List.of("", "https://foreign.example", base)) {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/plugins/database"))
                            .header("Cookie", cookie).header("Content-Type", "application/json");
                    if (!origin.isEmpty()) request.header("Origin", origin);
                    var rejected = client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"provider\":\"missing-fixture\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(rejected.statusCode()).as(origin).isEqualTo(origin.equals(base) ? 400 : 403);
                }
                assertThat(Files.readAllBytes(config)).isEqualTo(savedConfig);
                assertThatThrownBy(() -> context.getBean(DataSource.class).getConnection())
                        .isInstanceOf(SQLNonTransientConnectionException.class);
                try (var files = Files.walk(directory)) {
                    assertThat(files.filter(path -> path.getFileName().toString().endsWith(".db")))
                            .isEmpty();
                }
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }

}
