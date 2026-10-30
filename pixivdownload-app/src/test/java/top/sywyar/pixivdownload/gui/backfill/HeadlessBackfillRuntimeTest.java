package top.sywyar.pixivdownload.gui.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolLogSession;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

@DisplayName("无头回填与后端运行")
class HeadlessBackfillRuntimeTest {
    @Test
    @DisplayName("就绪事件异步回填真实 SQLite，后端仍可读库，关闭上下文释放执行器和连接池")
    void backfillsThroughLivePoolWithoutBlockingReady(@TempDir Path directory) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("headless.db"));
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + directory.resolve("headless.db"));
        config.setMaximumPoolSize(2);
        try (var pool = new HikariDataSource(config)) {
            var metadata = new WorkMetaCurator(new ObjectMapper()).curateNovel(1,
                    new ObjectMapper().readTree("{\"id\":1,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward");
            try (var connection = pool.getConnection()) {
                DatabaseInitializer.prepareOffline(connection, target);
                try (var statement = connection.prepareStatement(
                        "INSERT INTO novels(novel_id,title,folder,count,extensions,time,metadata_json) VALUES(1,'fixture','{0}',1,'txt',1,?)")) {
                    statement.setString(1, metadata.document().toString());
                    statement.executeUpdate();
                }
            }
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var completed = new CountDownLatch(1);
            var result = new AtomicReference<ArtworksBackFill.Summary>();
            var runner = new DesktopBackfillRunner((options, progress) -> {
                entered.countDown();
                release.await();
                try (var files = mockStatic(RuntimeFiles.class)) {
                    files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
                    var summary = ArtworksBackFill.runObserved(options, pool, progress);
                    result.set(summary);
                    return summary;
                }
            }, () -> new ToolLogSession() {
                public Path latestPath() { return directory.resolve("fixture.html"); }
                public Path sessionPath() { return latestPath(); }
                public void openLatestInBrowser() { fail("Headless backfill must not open a browser"); }
                public void close() { completed.countDown(); }
            });
            var application = new SpringApplication();
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean("dataSource", HikariDataSource.class, () -> pool);
                context.registerBean(DatabaseTarget.class, () -> target);
                context.registerBean(DesktopBackfillRunner.class, () -> runner, definition -> definition.setDependsOn("dataSource"));
                context.registerBean(ProxyConfig.class, ProxyConfig::new);
                var download = new DownloadConfig();
                download.setRootFolder(directory.toString());
                context.registerBean(DownloadConfig.class, () -> download);
                context.addApplicationListener(new HeadlessBackfillStartup(application));
                context.refresh();
                assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                        context.publishEvent(new ApplicationReadyEvent(application, new String[0], context, Duration.ZERO)));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertNull(result.get());
                try (var connection = pool.getConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT COUNT(*) FROM novels")) {
                    assertTrue(row.next());
                    assertEquals(1, row.getInt(1));
                }
                release.countDown();
                assertTrue(completed.await(10, TimeUnit.SECONDS));
                assertNotNull(result.get());
                assertEquals(1, result.get().filledUploadTime());
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                try (var connection = pool.getConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT upload_time FROM novels WHERE novel_id=1")) {
                    assertTrue(row.next());
                    assertEquals(1580698800000L, row.getLong(1));
                }
            } finally {
                release.countDown();
                runner.stop();
            }
            assertFalse(runner.isRunning());
            assertTrue(pool.isClosed());
        }
    }
}
