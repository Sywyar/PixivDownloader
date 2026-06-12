package top.sywyar.pixivdownload.core.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import top.sywyar.pixivdownload.download.db.PathPrefixStartupMigration;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("符号根 {0} 编解码与启动折叠测试")
class PathPrefixSymbolicRootTest {

    private static final PathPrefixColumns PATH_COLUMNS =
            DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns();

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
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

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PathPrefixMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true); // auto-commit
        mapper = sqlSession.getMapper(PathPrefixMapper.class);

        // 默认相对 root → 符号根启用
        codec = new PathPrefixCodec(mapper, new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();
        rootPath = codec.getSymbolicRootPath();

        createPathColumnTables();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        sqlSession.close();
        dataSource.destroy();
    }

    private void createPathColumnTables() {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS artworks (artwork_id INTEGER PRIMARY KEY, folder TEXT, move_folder TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS novels (novel_id INTEGER PRIMARY KEY, folder TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS manga_series (series_id INTEGER PRIMARY KEY, cover_folder TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS novel_series (series_id INTEGER PRIMARY KEY, cover_folder TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS collections (id INTEGER PRIMARY KEY, download_root TEXT)");
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
            return new PathPrefixStartupMigration(dataSource, codec, PATH_COLUMNS, new DownloadConfig(),
                    TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(),
                    null, null, null, null);
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
            return new PathPrefixStartupMigration(dataSource, absCodec, PATH_COLUMNS, absConfig,
                    TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(),
                    null, null, null, null);
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
                    absConfig, TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), dataSource);
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
