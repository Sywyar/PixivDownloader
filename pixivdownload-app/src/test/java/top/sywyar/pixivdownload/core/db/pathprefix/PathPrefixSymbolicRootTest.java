package top.sywyar.pixivdownload.core.db.pathprefix;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.download.db.PathPrefixStartupMigration;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("符号根 {0} 编解码与启动折叠测试")
class PathPrefixSymbolicRootTest {

    private static final PathPrefixColumns PATH_COLUMNS =
            DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns();

    private SingleConnectionDataSource dataSource;
    private PathPrefixMapper mapper;
    private PathPrefixCodec codec;
    private String rootPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 把运行期目录指到临时目录，避免测试在工作目录写 state/ / config/
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());

        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        mapper = new PathPrefixMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));

        // 默认相对 root → 符号根启用
        codec = new PathPrefixCodec(mapper, new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();
        rootPath = codec.getSymbolicRootPath();

        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializePathPrefixes(dataSource);
        createPathColumnTables();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        dataSource.destroy();
    }

    private void createPathColumnTables() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            for (var table : PATH_COLUMNS.all()) {
                st.execute("CREATE TABLE " + table.table() + " (" + table.idColumn() + (table.idColumn().equals("reference_id") || table.idColumn().equals("entry_id")
                        ? " TEXT PRIMARY KEY, " : " INTEGER PRIMARY KEY, ")
                        + String.join(", ", table.columns().stream().map(column -> column + " TEXT").toList()) + ")");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void execute(String sql) {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String queryString(String sql) {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("PathPrefixCodec 符号根编解码")
    class CodecTests {

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"/fixture/İ", "C:\\fixture\\İ"})
        @DisplayName("Unicode 大小写映射不改变路径后缀，活动编解码与离线快照一致")
        void preservesSuffixWhenCaseMappingChangesLength(String prefix) {
            long id = codec.forceCreatePrefixId(prefix);
            var snapshot = PathPrefixCodec.snapshot(new DownloadConfig().getRootFolder(), codec.snapshot());
            for (StoredPathCodec paths : java.util.List.of(codec, snapshot)) {
                assertThat(paths.encode(prefix + "/file-İ/图.png")).isEqualTo("{" + id + "}/file-İ/图.png");
                assertThat(paths.encode(prefix.toLowerCase(java.util.Locale.ROOT) + "/file.png"))
                        .isEqualTo("{" + id + "}/file.png");
                assertThat(paths.resolve("{" + id + "}/file-İ/图.png")).isEqualTo(prefix + "/file-İ/图.png");
                assertThat(paths.encode(prefix + "other/file.png")).isEqualTo(prefix + "other/file.png");
            }
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"/", "C:/", "C:\\"})
        @DisplayName("文件系统根前缀可编码子路径且回读不重复分隔符")
        void roundTripsFilesystemRoot(String prefix) {
            long id = codec.forceCreatePrefixId(prefix);
            var snapshot = PathPrefixCodec.snapshot(new DownloadConfig().getRootFolder(), codec.snapshot());
            for (StoredPathCodec paths : java.util.List.of(codec, snapshot)) {
                assertThat(paths.encode(prefix + "file.png")).isEqualTo("{" + id + "}/file.png");
                assertThat(paths.resolve("{" + id + "}/file.png").replace('\\', '/'))
                        .isEqualTo(prefix.replace('\\', '/') + "file.png");
            }
        }

        @Test
        @DisplayName("前缀注册随外层事务回滚后不能继续编码为已撤销的 ID")
        void discardsRolledBackPrefix() {
            String prefix = tempDir.resolve("rollback-prefix").toString();
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> {
                long id = codec.forceCreatePrefixId(prefix);
                assertThat(codec.encode(prefix + "/file.png")).isEqualTo("{" + id + "}/file.png");
                status.setRollbackOnly();
            });
            assertThat(mapper.findIdByPath(prefix)).isNull();
            assertThat(codec.encode(prefix + "/file.png")).isEqualTo(prefix + "/file.png");
        }

        @Test
        @DisplayName("保存点回滚撤回内层前缀并保留外层注册")
        void discardsPrefixAfterSavepointRollback() {
            var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
            var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
            var nested = new org.springframework.transaction.support.TransactionTemplate(manager);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            String outer = tempDir.resolve("outer").toString();
            String inner = tempDir.resolve("inner").toString();
            transaction.executeWithoutResult(status -> {
                long outerId = codec.forceCreatePrefixId(outer);
                nested.executeWithoutResult(savepoint -> {
                    long innerId = codec.forceCreatePrefixId(inner);
                    assertThat(codec.encode(inner + "/file")).isEqualTo("{" + innerId + "}/file");
                    savepoint.setRollbackOnly();
                });
                assertThat(codec.encode(inner + "/file")).isEqualTo(inner + "/file");
                assertThat(codec.encode(outer + "/file")).isEqualTo("{" + outerId + "}/file");
            });
            assertThat(mapper.findIdByPath(inner)).isNull();
            assertThat(codec.snapshot()).containsExactly(new PathPrefix(mapper.findIdByPath(outer), outer));
        }

        @Test
        @DisplayName("同一事务复用前缀快照，普通读取不使共享缓存失效")
        void reusesPrefixSnapshotWithinAndAfterReadTransaction() {
            var observed = org.mockito.Mockito.spy(mapper);
            var paths = new PathPrefixCodec(observed, new DownloadConfig(), TestI18nBeans.appMessages());
            paths.init();
            paths.reload();
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> {
                for (int i = 0; i < 100; i++) assertThat(paths.encode(rootPath + "/" + i)).isEqualTo("{0}/" + i);
            });
            assertThat(paths.encode(rootPath + "/next")).isEqualTo("{0}/next");
            org.mockito.Mockito.verify(observed, org.mockito.Mockito.times(1)).findAll();
        }

        @Test
        @DisplayName("新前缀只在提交后对其它线程的共享快照可见")
        void publishesPrefixOnlyAfterCommit() {
            codec.reload();
            String prefix = tempDir.resolve("commit-prefix").toString();
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> {
                long id = codec.forceCreatePrefixId(prefix);
                assertThat(codec.encode(prefix + "/file.png")).isEqualTo("{" + id + "}/file.png");
                var snapshot = new java.util.concurrent.FutureTask<>(codec::snapshot);
                Thread reader = new Thread(snapshot);
                reader.start();
                try {
                    assertThat(snapshot.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                } finally {
                    reader.interrupt();
                    try { reader.join(5000); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    assertThat(reader.isAlive()).isFalse();
                }
            });
            assertThat(codec.snapshot()).containsExactly(new PathPrefix(mapper.findIdByPath(prefix), prefix));
        }

        @Test
        @DisplayName("去除尾分隔符时应保留 POSIX 与 Windows 文件系统根")
        void shouldPreserveFilesystemRootWhenStrippingTrailingSeparators() {
            assertThat(PathPrefixCodec.stripTrailingSeparators("/")).isEqualTo("/");
            assertThat(PathPrefixCodec.stripTrailingSeparators("C:\\")).isEqualTo("C:\\");
            assertThat(PathPrefixCodec.stripTrailingSeparators("folder///")).isEqualTo("folder");
        }

        @Test
        @DisplayName("相对 root 时符号根应启用，根目录下路径编码为 {0}/...")
        void shouldEncodeRootSubPathToSymbolicToken() {
            assertThat(codec.isSymbolicRootActive()).isTrue();
            assertThat(codec.encode(rootPath + "/sub/1")).isEqualTo("{0}/sub/1");
            assertThat(codec.encode(rootPath)).isEqualTo("{0}");
        }

        @Test
        @DisplayName("{0} 引用应解析回根目录绝对路径")
        void shouldResolveSymbolicTokenToRootPath() {
            assertThat(codec.resolve("{0}")).isEqualTo(rootPath);
            assertThat(codec.resolve("{0}/sub/1")).isEqualTo(rootPath + "/sub/1");
        }

        @Test
        @DisplayName("符号根按长度参与最长匹配：根内更深的已注册前缀优先")
        void shouldPreferLongerRegisteredPrefixOverSymbolicRoot() {
            long deeperId = codec.getOrCreatePrefixId(rootPath + "/classified");

            assertThat(deeperId).isPositive();
            assertThat(codec.encode(rootPath + "/classified/9")).isEqualTo("{" + deeperId + "}/9");
            assertThat(codec.encode(rootPath + "/other/9")).isEqualTo("{0}/other/9");
        }

        @Test
        @DisplayName("注册与符号根同路径的前缀应返回 0 且不建行")
        void shouldReturnSymbolicIdWhenRegisteringRootPath() {
            assertThat(codec.getOrCreatePrefixId(rootPath)).isZero();
            assertThat(mapper.findIdByPath(rootPath)).isNull();
        }

        @Test
        @DisplayName("绝对 root 时符号根不启用，但 {0} 引用仍可解析")
        void shouldStayInactiveButResolvableWithAbsoluteRoot() {
            DownloadConfig absConfig = new DownloadConfig();
            absConfig.setRootFolder(tempDir.resolve("abs-root").toString());
            PathPrefixCodec absCodec = new PathPrefixCodec(mapper, absConfig, TestI18nBeans.appMessages());
            absCodec.init();

            assertThat(absCodec.isSymbolicRootActive()).isFalse();
            String absRoot = absCodec.getSymbolicRootPath();
            assertThat(absCodec.encode(absRoot + "/x")).isNotEqualTo("{0}/x");
            assertThat(absCodec.resolve("{0}/x")).isEqualTo(absRoot + "/x");
        }
    }

    @Nested
    @DisplayName("PathPrefixStartupMigration 启动折叠")
    class StartupFoldTests {

        private PathPrefixStartupMigration migration() {
            return new PathPrefixStartupMigration(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), codec, PATH_COLUMNS, new DownloadConfig(),
                    TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction());
        }

        @Test
        @DisplayName("与符号根同路径的 {N} 行应被折叠为 {0} 并删除前缀行")
        void shouldFoldMatchingPrefixRowIntoSymbolicRoot() {
            mapper.insertIfAbsent(rootPath);
            Long oldId = mapper.findIdByPath(rootPath);
            assertThat(oldId).isNotNull();
            codec.reload();
            execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{" + oldId + "}/100', '{" + oldId + "}')");
            execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{" + oldId + "}/novel-2')");

            migration().migrate();

            assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}/100");
            assertThat(queryString("SELECT move_folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}");
            assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2")).isEqualTo("{0}/novel-2");
            assertThat(mapper.findPathById(oldId)).isNull();
        }

        @Test
        @DisplayName("折叠应覆盖反斜杠分隔的 {N}\\... 引用，删除前缀行后不留悬空引用")
        void shouldFoldBackslashSeparatedReferences() {
            mapper.insertIfAbsent(rootPath);
            Long oldId = mapper.findIdByPath(rootPath);
            assertThat(oldId).isNotNull();
            codec.reload();
            // 反斜杠分隔的编码值：encode 不产出，但 codec 承认其合法，可能来自手改 / 旧数据
            execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{" + oldId + "}\\100', '{" + oldId + "}')");
            execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{" + oldId + "}\\novel-2')");

            migration().migrate();

            assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}\\100");
            assertThat(queryString("SELECT move_folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}");
            assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2")).isEqualTo("{0}\\novel-2");
            assertThat(mapper.findPathById(oldId)).isNull();
        }

        @Test
        @DisplayName("折叠后应写入 download_root_marker 标记文件")
        void shouldWriteRootMarkerAfterMigrate() throws Exception {
            migration().migrate();

            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            assertThat(marker).exists();
            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEqualTo(rootPath);
        }

        @Test
        @DisplayName("重复启动应幂等：无匹配前缀行时不再改写")
        void shouldBeIdempotentAcrossRestarts() {
            mapper.insertIfAbsent(rootPath);
            codec.reload();
            execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{"
                    + mapper.findIdByPath(rootPath) + "}/100', NULL)");

            migration().migrate();
            migration().migrate();

            assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}/100");
            assertThat(queryString("SELECT COUNT(*) FROM path_prefixes")).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("孤儿符号根（root 改为绝对路径但仍有 {0} 行）")
    class OrphanTests {

        private PathPrefixCodec absCodec;
        private DownloadConfig absConfig;

        @BeforeEach
        void setUpAbsoluteRoot() {
            absConfig = new DownloadConfig();
            absConfig.setRootFolder(tempDir.resolve("abs-root").toString());
            absCodec = new PathPrefixCodec(mapper, absConfig, TestI18nBeans.appMessages());
            absCodec.init();
        }

        private PathPrefixStartupMigration absMigration() {
            return new PathPrefixStartupMigration(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource), absCodec, PATH_COLUMNS, absConfig,
                    TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction());
        }

        @Test
        @DisplayName("孤儿状态下启动不得覆盖 marker，{0} 行保持原样")
        void shouldPreserveMarkerAndRowsWhenOrphaned() throws Exception {
            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, rootPath, StandardCharsets.UTF_8);
            execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");

            absMigration().migrate();

            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEqualTo(rootPath);
            assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}/100");
        }

        @Test
        @DisplayName("符号根未启用且无 {0} 行时应删除过时 marker")
        void shouldDeleteStaleMarkerWhenInactiveWithoutRows() throws Exception {
            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, rootPath, StandardCharsets.UTF_8);

            absMigration().migrate();

            assertThat(marker).doesNotExist();
        }

        @Test
        @DisplayName("孤儿修复：pin 到旧路径应改写 {0} 行并删除 marker")
        void shouldRepairOrphanByPinningAndDeleteMarker() throws Exception {
            Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, rootPath, StandardCharsets.UTF_8);
            Path oldRoot = Files.createDirectory(tempDir.resolve("old-root"));
            execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");
            PathPrefixMigrationService service = new PathPrefixMigrationService(absCodec, mapper, PATH_COLUMNS,
                    absConfig, TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(dataSource));
            assertThat(service.symbolicRootStatus().orphan()).isTrue();
            assertThat(service.symbolicRootStatus().suggestedOldPath()).isEqualTo(rootPath);

            var result = service.pinSymbolicRoot(oldRoot.toString());

            assertThat(result.success()).isTrue();
            Long newId = mapper.findIdByPath(PathPrefixCodec.stripTrailingSeparators(oldRoot.toString()));
            assertThat(newId).isNotNull();
            assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1"))
                    .isEqualTo("{" + newId + "}/100");
            assertThat(marker).doesNotExist();
            assertThat(service.symbolicRootStatus().orphan()).isFalse();
        }
    }
}
