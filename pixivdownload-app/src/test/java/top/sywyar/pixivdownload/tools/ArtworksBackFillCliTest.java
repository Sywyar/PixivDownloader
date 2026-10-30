package top.sywyar.pixivdownload.tools;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.StandardEnvironment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("独立回填 CLI")
class ArtworksBackFillCliTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("真实 main 输出阶段与最终计数，遵守上限与预演并释放数据库及插件会话")
    void reportsProgressAndPreservesDryRun(boolean dryRun, @TempDir Path directory) throws Exception {
        var path = directory.resolve("cli.db");
        var target = DatabaseProviderTestFixtures.sqlite(path);
        var metadata = new WorkMetaCurator(new ObjectMapper()).curateNovel(1,
                new ObjectMapper().readTree("{\"id\":1,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward");
        try (var connection = target.openConnection()) {
            DatabaseInitializer.prepareOffline(connection, target);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO novels(novel_id,title,folder,count,extensions,time,metadata_json) VALUES(1,'fixture','{0}',1,'txt',1,?)")) {
                statement.setString(1, metadata.document().toString());
                statement.executeUpdate();
            }
        }
        var applicationLogger = (Logger) LoggerFactory.getLogger(ArtworksBackFill.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        applicationLogger.addAppender(output);
        var plugins = mock(PluginBootstrapSession.class);
        var environment = new StandardEnvironment();
        try (var targets = mockStatic(DatabaseTarget.class);
             var bootstrap = mockStatic(DatabaseBootstrap.class);
             var console = mockStatic(top.sywyar.pixivdownload.common.Utf8ConsoleStreams.class);
             var files = mockStatic(RuntimeFiles.class)) {
            targets.when(DatabaseTarget::configurationEnvironment).thenReturn(environment);
            bootstrap.when(() -> DatabaseBootstrap.openToolSession(environment)).thenReturn(plugins);
            bootstrap.when(() -> DatabaseBootstrap.inspect(plugins, environment)).thenReturn(target);
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var arguments = new ArrayList<>(List.of("--root-folder", directory.toString(), "--no-proxy", "--limit", "1"));
            if (dryRun) arguments.add("--dry-run");
            ArtworksBackFill.main(arguments.toArray(String[]::new));
            verify(plugins).close();
            var messages = output.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.contains(MessageBundles.getForLog("artworks-backfill.log.phase-progress",
                    ArtworksBackFill.Phase.IMPORTING, 0, "?", 0, 0, 0)));
            assertTrue(messages.contains(MessageBundles.getForLog("artworks-backfill.log.phase-progress",
                    ArtworksBackFill.Phase.FINISHED, 1, 1, 1, 0, 0)));
            try (var connection = target.openConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT upload_time,metadata_json FROM novels WHERE novel_id=1")) {
                assertTrue(row.next());
                assertEquals(dryRun ? null : 1580698800000L, row.getObject(1));
                assertEquals(metadata.document().toString(), row.getString(2));
            }
        } finally {
            applicationLogger.detachAppender(output);
            output.stop();
        }
    }
}
