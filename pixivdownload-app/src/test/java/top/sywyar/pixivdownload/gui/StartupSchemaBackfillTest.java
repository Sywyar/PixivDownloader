package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.sql.Connection;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("启动回填的失败传播与资源释放")
class StartupSchemaBackfillTest {
    private enum Step { OPTIONS, COMPARE }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanTestDirectory() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("结构正常且仅余已暂缓小说时直接启动后端，不再运行联网回填")
    void startsBackendWhenOnlyRestrictedNovelRemains() throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("restricted.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
        }
        var path = directory.resolve("unreachable.json");
        java.nio.file.Files.writeString(path, "{\"novel:3\":{\"restricted\":true,\"reason\":\"restricted\"}}");
        var host = mock(DesktopUiHost.class);
        var config = mock(DesktopUiHost.ConfigFile.class);
        when(host.applicationConfig()).thenReturn(config);
        when(config.read("download.root-folder")).thenReturn(directory.toString());
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions("fixture", "", 0, false, 0, 1, false));
        try (var files = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class);
             var backfill = mockStatic(ArtworksBackFill.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var backend = mockStatic(BackendLifecycleManager.class);
             var dialogs = mockStatic(DesktopUiDialogs.class)) {
            files.when(top.sywyar.pixivdownload.config.RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(path);
            backfill.when(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class))).thenThrow(new AssertionError("unexpected backfill"));
            StartupSchemaBackfill.run(mock(DesktopUiSession.class), host, DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema(), target);
            backfill.verify(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class)), never());
            verifyHandoff(backend, host);
            dialogs.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"complete,true", "complete,false", "indexes,true", "extra,true"})
    @DisplayName("结构完整或只有可恢复差异时按实际缺失数据触发启动回填")
    void backfillsMissingDataWithoutMissingColumns(String layout, boolean missing) throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("data.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time,upload_time) "
                    + "VALUES(3,'novel','{0}',1,'txt',1," + (missing ? "NULL" : "1000") + ")");
            if (layout.equals("indexes")) sql.execute("DROP INDEX idx_novels_upload_time");
            if (layout.equals("extra")) sql.execute("CREATE TABLE other_plugin(id INTEGER PRIMARY KEY)");
        }
        var host = mock(DesktopUiHost.class);
        when(host.applicationConfig()).thenReturn(mock(DesktopUiHost.ConfigFile.class));
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions("fixture", "", 0, false, 0, 0, false));
        var session = mock(ToolHtmlLogSession.class);
        try (var backfill = mockStatic(ArtworksBackFill.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var logs = mockStatic(ToolHtmlLogSession.class);
             var files = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class);
             var dialogs = mockStatic(DesktopUiDialogs.class);
             var backend = mockStatic(BackendLifecycleManager.class)) {
            files.when(top.sywyar.pixivdownload.config.RuntimeFiles::resolveBackfillUnreachablePath)
                    .thenReturn(directory.resolve("unreachable.json"));
            logs.when(() -> ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class)).thenReturn(session);
            backfill.when(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class))).thenReturn(null);
            StartupSchemaBackfill.run(mock(DesktopUiSession.class), host,
                    DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema(), target);
            backfill.verify(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class)), never());
            backfill.verify(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class)), never());
            verifyHandoff(backend, host);
            logs.verifyNoInteractions();
            dialogs.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "CREATE UNIQUE INDEX idx_artworks_upload_time ON artworks(upload_time)",
            "CREATE INDEX idx_artworks_upload_time ON artworks(title)",
            "CREATE INDEX idx_artworks_upload_time ON authors(name)"})
    @DisplayName("旧库缺列与新索引并存时可回填，同名索引漂移仍阻断自动回填")
    void handlesMissingIndexesInLegacyDatabase(String replacement) throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("legacy.db"));
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("DROP INDEX idx_artworks_upload_time");
            sql.execute("DROP INDEX idx_novels_upload_time");
            sql.execute("ALTER TABLE artworks DROP COLUMN r18");
            if (!replacement.isEmpty()) sql.execute(replacement);
            var comparison = DatabaseSchemaInspector.compare(connection, target, registry.mergedSchema());
            org.assertj.core.api.Assertions.assertThat(comparison.details())
                    .anyMatch(d -> d.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN);
        }
        var host = mock(DesktopUiHost.class);
        when(host.applicationConfig()).thenReturn(mock(DesktopUiHost.ConfigFile.class));
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions(
                "fixture", "", 0, false, 0, 0, false));
        try (var backfill = mockStatic(ArtworksBackFill.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var dialogs = mockStatic(DesktopUiDialogs.class);
             var backend = mockStatic(BackendLifecycleManager.class)) {
            backfill.when(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class))).thenReturn(0);
            StartupSchemaBackfill.run(mock(DesktopUiSession.class), host, registry.mergedSchema(), target);
            backfill.verify(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class)), never());
            if (replacement.isEmpty()) verifyHandoff(backend, host);
            else backend.verify(BackendLifecycleManager::startAsync);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseSchemaInspector.SchemaDifferenceKind.class,
            names = {"MISSING_COLUMN", "COLUMN_TYPE_MISMATCH"})
    @DisplayName("附加表不阻断缺列回填，受管类型漂移不能误触发回填")
    void distinguishesMissingColumnsFromDrift(DatabaseSchemaInspector.SchemaDifferenceKind kind) throws Exception {
        var host = mock(DesktopUiHost.class);
        when(host.applicationConfig()).thenReturn(mock(DesktopUiHost.ConfigFile.class));
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions(
                "fixture", "", 0, false, 0, 0, false));
        var target = mock(DatabaseTarget.class);
        var connection = mock(Connection.class);
        when(target.available()).thenReturn(true);
        when(target.localFile()).thenReturn(Optional.empty());
        when(target.openConnection()).thenReturn(connection);
        var schema = DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        var comparison = new DatabaseSchemaInspector.SchemaComparison(false, List.of(
                new DatabaseSchemaInspector.SchemaDifference(kind, "artworks", "description", "fixture"),
                new DatabaseSchemaInspector.SchemaDifference(
                        DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE, "absent_plugin", null, "fixture")));
        try (var inspector = mockStatic(DatabaseSchemaInspector.class);
             var backfill = mockStatic(ArtworksBackFill.class);
             var dialogs = mockStatic(DesktopUiDialogs.class);
             var backend = mockStatic(BackendLifecycleManager.class)) {
            inspector.when(() -> DatabaseSchemaInspector.compare(connection, target, schema)).thenReturn(comparison);
            backfill.when(() -> ArtworksBackFill.supportsDatabaseColumn("artworks", "description")).thenReturn(true);
            StartupSchemaBackfill.run(mock(DesktopUiSession.class), host, schema, target);
            backfill.verify(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class)), never());
            backfill.verify(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class)), never());
            if (kind == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN) verifyHandoff(backend, host);
            else backend.verify(BackendLifecycleManager::startAsync);
            verify(connection).close();
        }
    }

    @TempDir Path directory;

    @AfterEach
    void cleanupDirectory() throws Exception {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @Test
    @DisplayName("结构正常且仅余已暂缓小说时直接启动后端，不再运行联网回填")
    void startsBackendWhenOnlyRestrictedNovelRemains() throws Exception {
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("restricted.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
        }
        var path = directory.resolve("unreachable.json");
        java.nio.file.Files.writeString(path, "{\"novel:3\":{\"restricted\":true,\"reason\":\"restricted\"}}");
        var host = mock(DesktopUiHost.class);
        var config = mock(DesktopUiHost.ConfigFile.class);
        when(host.applicationConfig()).thenReturn(config);
        when(config.read("download.root-folder")).thenReturn(directory.toString());
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions("fixture", "", 0, false, 0, 1, false));
        try (var files = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class);
             var backfill = mockStatic(ArtworksBackFill.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             var backend = mockStatic(BackendLifecycleManager.class);
             var dialogs = mockStatic(DesktopUiDialogs.class)) {
            files.when(top.sywyar.pixivdownload.config.RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(path);
            backfill.when(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class))).thenThrow(new AssertionError("unexpected backfill"));
            StartupSchemaBackfill.run(mock(DesktopUiSession.class), host, DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema(), target);
            backfill.verify(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class)), never());
            backend.verify(BackendLifecycleManager::startAsync);
            dialogs.verifyNoInteractions();
        }
    }

    static Stream<Arguments> failures() {
        return Arrays.stream(Step.values()).flatMap(step -> Stream.of(
                Arguments.of(step, new OutOfMemoryError("fixture")),
                Arguments.of(step, new ThreadDeath()),
                Arguments.of(step, new IllegalStateException("fixture"))
        ));
    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("各回填步骤的致命错误原样传播，普通失败继续后端启动并释放本次资源")
    void failureKeepsFatalBoundary(Step step, Throwable failure) throws Exception {
        var host = mock(DesktopUiHost.class);
        when(host.applicationConfig()).thenReturn(mock(DesktopUiHost.ConfigFile.class));
        var ui = mock(DesktopUiSession.class);
        var target = mock(DatabaseTarget.class);
        var connection = mock(Connection.class);
        var logSession = mock(ToolHtmlLogSession.class);
        var schema = DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        when(target.available()).thenReturn(true);
        when(target.localFile()).thenReturn(Optional.empty());
        when(target.openConnection()).thenReturn(connection);
        when(host.defaultBackfillOptions()).thenReturn(new DesktopUiHost.BackfillOptions(
                "fixture",
                "",
                0,
                false,
                0,
                0,
                false
        ));
        var mismatch = new DatabaseSchemaInspector.SchemaComparison(
                false,
                List.of(new DatabaseSchemaInspector.SchemaDifference(
                        DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN,
                        "artworks",
                        "description",
                        "fixture"
                ))
        );
        try (var inspector = mockStatic(DatabaseSchemaInspector.class);
             var backfill = mockStatic(ArtworksBackFill.class);
             var logs = mockStatic(ToolHtmlLogSession.class);
             var backend = mockStatic(BackendLifecycleManager.class)) {
            inspector.when(() -> DatabaseSchemaInspector.compare(connection, target, schema)).thenReturn(mismatch);
            backfill.when(() -> ArtworksBackFill.supportsDatabaseColumn("artworks", "description")).thenReturn(true);
            backfill.when(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class))).thenReturn(1);
            logs.when(() -> ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class)).thenReturn(logSession);
            switch (step) {
                case OPTIONS -> when(host.defaultBackfillOptions()).thenThrow(failure);
                case COMPARE -> inspector.when(() -> DatabaseSchemaInspector.compare(connection, target, schema))
                        .thenThrow(failure);
            }

            if (failure instanceof Error) {
                assertThatThrownBy(() -> StartupSchemaBackfill.run(ui, host, schema, target)).isSameAs(failure);
                backend.verifyNoInteractions();
            } else {
                assertThatCode(() -> StartupSchemaBackfill.run(ui, host, schema, target)).doesNotThrowAnyException();
                backend.verify(BackendLifecycleManager::startAsync);
            }
            if (step != Step.OPTIONS) verify(connection).close();
            else verify(target, never()).openConnection();
            logs.verifyNoInteractions();
        }
    }

    private static void verifyHandoff(org.mockito.MockedStatic<BackendLifecycleManager> backend,
                                      DesktopUiHost host) throws Exception {
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        backend.verify(() -> BackendLifecycleManager.startAsync(callback.capture()));
        verify(host, never()).startBackfill(any(), org.mockito.ArgumentMatchers.anyBoolean());
        callback.getValue().run();
        verify(host).startBackfill(any(), org.mockito.ArgumentMatchers.eq(true));
    }
}
