package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.sql.Connection;
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
    private enum Step { OPTIONS, COMPARE, COUNT, RUN }

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
                case COUNT -> backfill.when(() -> ArtworksBackFill.countCandidates(any(ArtworksBackFill.Options.class)))
                        .thenThrow(failure);
                case RUN -> backfill.when(() -> ArtworksBackFill.run(any(ArtworksBackFill.Options.class)))
                        .thenThrow(failure);
            }

            if (failure instanceof Error) {
                assertThatThrownBy(() -> StartupSchemaBackfill.run(ui, host, schema, target)).isSameAs(failure);
                backend.verifyNoInteractions();
            } else {
                assertThatCode(() -> StartupSchemaBackfill.run(ui, host, schema, target)).doesNotThrowAnyException();
                if (step == Step.RUN) {
                    backend.verify(() -> BackendLifecycleManager.startAsync(any(Runnable.class)));
                } else {
                    backend.verify(BackendLifecycleManager::startAsync);
                }
            }
            if (step != Step.OPTIONS) verify(connection).close();
            else verify(target, never()).openConnection();
            if (step == Step.RUN) verify(logSession).close();
            else logs.verifyNoInteractions();
        }
    }
}
