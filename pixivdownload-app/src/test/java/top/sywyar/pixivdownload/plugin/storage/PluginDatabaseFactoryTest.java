package top.sywyar.pixivdownload.plugin.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.config.InstallIdentity;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.core.db.DatabaseEngine;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("私库凭据持久身份")
class PluginDatabaseFactoryTest {
    @TempDir Path directory;

    @Test @DisplayName("只读私库预检不创建身份、数据目录或凭据，也不调用会升级信封的读取")
    void previewDoesNotCreatePrivateFiles() throws Exception {
        String oldData = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        String oldConfig = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        try {
            Path data = directory.resolve("data"), config = directory.resolve("config");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, data.toString());
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, config.toString());
            var credentials = mock(PluginCredentialStore.class);
            when(credentials.readForTransfer("fixture-owner")).thenReturn(Map.of());
            assertThatThrownBy(() -> PluginDatabaseFactory.privateSettings("fixture-owner", credentials, false))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            assertThat(data).doesNotExist();
            java.nio.file.Files.createDirectories(data);
            String identity = java.util.UUID.randomUUID().toString();
            java.nio.file.Files.writeString(data.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE), identity);
            var settings = PluginDatabaseFactory.privateSettings("fixture-owner", credentials, false);
            assertThat(settings.installationId()).isEqualTo(identity);
            assertThat(settings.password()).isEmpty();
            assertThat(settings.directory()).isEqualTo(data.resolve("fixture-owner"));
            assertThat(config).doesNotExist();
            assertThat(data.resolve("fixture-owner")).doesNotExist();
            verify(credentials).readForTransfer("fixture-owner");
            verifyNoMoreInteractions(credentials);
        } finally {
            if (oldData == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY); else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, oldData);
            if (oldConfig == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY); else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, oldConfig);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @DisplayName("私库初始化失败关闭已创建资源，清理致命错误仍传播")
    void closesFailedInitialization(boolean fatal, boolean cleanupFatal) throws Exception {
        Throwable failure = fatal ? new OutOfMemoryError("fixture") : new SQLException("fixture");
        Throwable cleanup = cleanupFatal ? new OutOfMemoryError("cleanup fixture")
                : new IllegalStateException("cleanup fixture");
        try (var databases = mockConstruction(AppPluginDatabase.class, (database, context) -> {
            doThrow(failure).when(database).initialize(anyList(), anyList());
            doThrow(cleanup).when(database).close();
        })) {
            var factory = new PluginDatabaseFactory(null, null, null);
            Throwable actual = catchThrowable(() -> factory.create("fixture-owner", List.of()));
            if (cleanupFatal) assertThat(actual).isSameAs(cleanup).hasSuppressedException(failure);
            else if (fatal) assertThat(actual).isSameAs(failure);
            else assertThat(actual).isInstanceOf(IllegalStateException.class)
                    .hasMessage("PRIVATE_STORAGE_INITIALIZATION_FAILED").hasCause(failure);
            verify(databases.constructed().get(0)).close();
            if (!cleanupFatal) assertThat(failure.getSuppressed()).containsExactly(cleanup);
        }
    }

    @Test
    @DisplayName("地址别名和 provider 改变不轮换同一安装 owner 密码，不同 owner 不共享密码")
    void credentialsFollowInstallationAndOwner() throws Exception {
        var credentials = mock(PluginCredentialStore.class);
        Map<String, Map<String, String>> saved = new HashMap<>();
        doAnswer(call -> {
            ((PluginCredentialStore.IoOperation) call.getArgument(1)).run();
            return null;
        }).when(credentials).withOwnerLocks(anyCollection(), any());
        when(credentials.readAll(anyString())).thenAnswer(call -> saved.getOrDefault(call.getArgument(0), Map.of()));
        doAnswer(call -> {
            saved.computeIfAbsent(call.getArgument(0), ignored -> new HashMap<>()).putAll(call.getArgument(1));
            return null;
        }).when(credentials).update(anyString(), anyMap());
        List<DatabasePrivateStorageSettings> captured = new ArrayList<>();
        try (var identity = mockStatic(InstallIdentity.class); var paths = mockStatic(RuntimeFiles.class)) {
            identity.when(InstallIdentity::get).thenReturn("isolated-installation");
            paths.when(() -> RuntimeFiles.resolvePluginDataDirectory(anyString()))
                    .thenAnswer(call -> directory.resolve((String) call.getArgument(0)));
            for (String address : List.of("localhost", "127.0.0.1")) {
                var target = mock(DatabaseTarget.class);
                when(target.available()).thenReturn(true);
                when(target.databaseId()).thenReturn("postgresql");
                when(target.description()).thenReturn("postgresql: " + address + "/fixture [main]");
                when(target.providerOwner()).thenReturn("provider-" + address);
                when(target.privateStorage(any())).thenAnswer(call -> {
                    captured.add(call.getArgument(0));
                    throw new SQLException("fixture stops before opening a pool");
                });
                var factory = new PluginDatabaseFactory(target, mock(DataSource.class), credentials);
                for (String owner : List.of("first-owner", "second-owner")) {
                    try (var database = (AppPluginDatabase) factory.create(owner)) {
                        assertThatThrownBy(database::getConnection).isInstanceOf(SQLException.class);
                    }
                }
            }
        }
        assertThat(captured).hasSize(4);
        assertThat(captured.get(0).password()).isEqualTo(captured.get(2).password())
                .isNotEqualTo(captured.get(1).password());
        assertThat(captured.get(1).password()).isEqualTo(captured.get(3).password());
        assertThat(saved).containsOnlyKeys("first-owner", "second-owner");
        assertThat(saved.values()).allSatisfy(values -> assertThat(values).hasSize(1));
        assertThat(directory.resolve("first-owner")).doesNotExist();
    }
}
