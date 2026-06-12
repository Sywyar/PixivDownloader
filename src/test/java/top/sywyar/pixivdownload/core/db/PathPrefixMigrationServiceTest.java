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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService.PathPrefixMigrationResult;
import top.sywyar.pixivdownload.core.db.PathPrefixMigrationService.PathPrefixUpdate;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.DatabaseSchemaRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathPrefixMigrationService 批量改写测试")
class PathPrefixMigrationServiceTest {

    private static final PathPrefixColumns PATH_COLUMNS =
            DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns();

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private PathPrefixMapper mapper;
    private PathPrefixCodec codec;
    private PathPrefixMigrationService service;

    private long idA;
    private long idB;
    private long idC;
    private Path dirA;
    private Path dirB;
    private Path dirC;
    private Path dirD;
    private Path dirE;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
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

        // 默认相对 root → 符号根 {0} 启用
        codec = new PathPrefixCodec(mapper, new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();

        // 符号根改写需要触达全部路径前缀列：补建最小化的内容表
        createPathColumnTables();

        // 真实存在的目录：validatePath 要求目标必须是已存在的绝对目录
        dirA = Files.createDirectory(tempDir.resolve("a"));
        dirB = Files.createDirectory(tempDir.resolve("b"));
        dirC = Files.createDirectory(tempDir.resolve("c"));
        dirD = Files.createDirectory(tempDir.resolve("d"));
        dirE = Files.createDirectory(tempDir.resolve("e"));

        idA = codec.getOrCreatePrefixId(dirA.toString());
        idB = codec.getOrCreatePrefixId(dirB.toString());
        idC = codec.getOrCreatePrefixId(dirC.toString());

        // 不依赖真实事务管理器：withoutTransaction 直接执行回调，足以验证两阶段写入避免瞬时 UNIQUE 冲突
        service = new PathPrefixMigrationService(codec, mapper, PATH_COLUMNS, new DownloadConfig(),
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), dataSource);
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

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("链式重命名（A→D, B→C, C→E）应成功而不触发 UNIQUE 约束")
    void shouldApplyChainedRenameWithoutUniqueViolation() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirD.toString()),
                new PathPrefixUpdate(idB, dirC.toString()),
                new PathPrefixUpdate(idC, dirE.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        assertThat(stored(idA)).isEqualTo(strip(dirD));
        assertThat(stored(idB)).isEqualTo(strip(dirC));
        assertThat(stored(idC)).isEqualTo(strip(dirE));
    }

    @Test
    @DisplayName("两行路径互换（A↔B）应成功")
    void shouldSwapTwoPrefixesWithoutUniqueViolation() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirB.toString()),
                new PathPrefixUpdate(idB, dirA.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(2);
        assertThat(stored(idA)).isEqualTo(strip(dirB));
        assertThat(stored(idB)).isEqualTo(strip(dirA));
        assertThat(stored(idC)).isEqualTo(strip(dirC));
    }

    @Test
    @DisplayName("目标与另一行当前路径重复应被拒绝且一行不改")
    void shouldRejectDuplicateTargetAndApplyNothing() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(idA, dirB.toString())), List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.applied()).isZero();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("duplicate");
        // 未写入任何记录
        assertThat(stored(idA)).isEqualTo(strip(dirA));
        assertThat(stored(idB)).isEqualTo(strip(dirB));
    }

    @Test
    @DisplayName("符号根启用时 list 应置顶返回 id 0 的虚拟行")
    void shouldListSymbolicRootFirst() {
        var views = service.list();

        assertThat(views).isNotEmpty();
        assertThat(views.get(0).id()).isZero();
        assertThat(views.get(0).symbolic()).isTrue();
        assertThat(views.get(0).downloadRoot()).isTrue();
        assertThat(views.get(0).path()).isEqualTo(codec.getSymbolicRootPath());
        assertThat(views.stream().skip(1)).allMatch(v -> !v.symbolic());
    }

    @Test
    @DisplayName("改写符号根应把全部路径前缀列中的 {0} 引用替换为新前缀 {N}")
    void shouldRewriteSymbolicRootReferencesToNewPrefix() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', '{0}')");
        execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{0}/novel-2')");
        execute("INSERT INTO collections(id, download_root) VALUES(3, '{0}/col')");

        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, dirD.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isEqualTo(1);
        Long newId = mapper.findIdByPath(strip(dirD));
        assertThat(newId).isNotNull();
        String token = "{" + newId + "}";
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo(token + "/100");
        assertThat(queryString("SELECT move_folder FROM artworks WHERE artwork_id = 1")).isEqualTo(token);
        assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2")).isEqualTo(token + "/novel-2");
        assertThat(queryString("SELECT download_root FROM collections WHERE id = 3")).isEqualTo(token + "/col");
    }

    @Test
    @DisplayName("符号根目标与现有前缀行同路径时应合并复用该行而不报错")
    void shouldMergeSymbolicRootIntoExistingPrefixRow() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");

        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, dirA.toString())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1"))
                .isEqualTo("{" + idA + "}/100");
    }

    @Test
    @DisplayName("符号根新路径与当前解析结果相同应视为未改动")
    void shouldIgnoreSymbolicRootUpdateToSamePath() {
        PathPrefixMigrationResult result = service.apply(List.of(
                new PathPrefixUpdate(0L, codec.getSymbolicRootPath())), List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.applied()).isZero();
    }

    @Test
    @DisplayName("符号根未启用（绝对 root）时对 id 0 的改写应报 unknown-id")
    void shouldRejectSymbolicRootUpdateWhenInactive(@TempDir Path absRoot) {
        DownloadConfig absConfig = new DownloadConfig();
        absConfig.setRootFolder(absRoot.toString());
        PathPrefixCodec absCodec = new PathPrefixCodec(mapper, absConfig, TestI18nBeans.appMessages());
        absCodec.init();
        PathPrefixMigrationService absService = new PathPrefixMigrationService(absCodec, mapper, PATH_COLUMNS, absConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), dataSource);

        PathPrefixMigrationResult result = absService.apply(List.of(
                new PathPrefixUpdate(0L, dirD.toString())), List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("unknown-id");
    }

    @Test
    @DisplayName("pin 符号根到任意已存在目录应改写全部 {0} 引用")
    void shouldPinSymbolicRootToGivenPath() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', '{0}')");
        execute("INSERT INTO novels(novel_id, folder) VALUES(2, '{0}/novel-2')");

        PathPrefixMigrationResult result = service.pinSymbolicRoot(dirD.toString());

        assertThat(result.success()).isTrue();
        Long newId = mapper.findIdByPath(strip(dirD));
        assertThat(newId).isNotNull();
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1"))
                .isEqualTo("{" + newId + "}/100");
        assertThat(queryString("SELECT folder FROM novels WHERE novel_id = 2"))
                .isEqualTo("{" + newId + "}/novel-2");
    }

    @Test
    @DisplayName("pin 到符号根当前解析路径应强制建行（冻结旧记录场景）")
    void shouldPinSymbolicRootToItsOwnResolutionByForceCreatingRow() {
        // root 设为已存在的相对目录 target → 符号根启用且解析路径真实存在，满足 validatePath
        DownloadConfig relConfig = new DownloadConfig();
        relConfig.setRootFolder("target");
        PathPrefixCodec relCodec = new PathPrefixCodec(mapper, relConfig, TestI18nBeans.appMessages());
        relCodec.init();
        PathPrefixMigrationService relService = new PathPrefixMigrationService(relCodec, mapper, PATH_COLUMNS, relConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), dataSource);
        String rootPath = relCodec.getSymbolicRootPath();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(9, '{0}/900', NULL)");

        PathPrefixMigrationResult result = relService.pinSymbolicRoot(rootPath);

        assertThat(result.success()).isTrue();
        Long newId = mapper.findIdByPath(rootPath);
        assertThat(newId).isNotNull(); // getOrCreatePrefixId 守卫会返回 0，pin 必须强制建行
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 9"))
                .isEqualTo("{" + newId + "}/900");
    }

    @Test
    @DisplayName("pin 冻结符号根后应停用 {0} 编码，新记录改用固定后的 {N}（未重启窗口防误写）")
    void shouldStopEncodingAsSymbolicRootAfterPin() {
        // root 设为已存在的相对目录 target → 符号根启用且解析路径真实存在，满足 validatePath
        DownloadConfig relConfig = new DownloadConfig();
        relConfig.setRootFolder("target");
        PathPrefixCodec relCodec = new PathPrefixCodec(mapper, relConfig, TestI18nBeans.appMessages());
        relCodec.init();
        PathPrefixMigrationService relService = new PathPrefixMigrationService(relCodec, mapper, PATH_COLUMNS, relConfig,
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction(), dataSource);
        String rootPath = relCodec.getSymbolicRootPath();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(9, '{0}/900', NULL)");
        assertThat(relCodec.isSymbolicRootActive()).isTrue();
        assertThat(relCodec.encode(rootPath + "/new")).isEqualTo("{0}/new");

        PathPrefixMigrationResult result = relService.pinSymbolicRoot(rootPath);

        assertThat(result.success()).isTrue();
        // pin 后符号根停用，list / 状态不再把 {0} 当作可编码候选
        assertThat(relCodec.isSymbolicRootActive()).isFalse();
        Long newId = mapper.findIdByPath(rootPath);
        assertThat(newId).isNotNull();
        // 旧记录已固定为 {N}
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 9"))
                .isEqualTo("{" + newId + "}/900");
        // 关键：尚未重启时新下载（仍落在旧 root）应编码成固定后的 {N}，而不是会随新配置漂移的 {0}
        assertThat(relCodec.encode(rootPath + "/new")).isEqualTo("{" + newId + "}/new");
    }

    @Test
    @DisplayName("hasSymbolicRootRows 应识别反斜杠分隔的 {0}\\... 引用")
    void shouldDetectBackslashSeparatedSymbolicRows() {
        assertThat(service.symbolicRootStatus().referenced()).isFalse();
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(7, '{0}\\deep', NULL)");
        assertThat(service.symbolicRootStatus().referenced()).isTrue();
    }

    @Test
    @DisplayName("pin 到不存在的目录应报 not-exist 且不改写任何记录")
    void shouldRejectPinToMissingDirectory() {
        execute("INSERT INTO artworks(artwork_id, folder, move_folder) VALUES(1, '{0}/100', NULL)");

        PathPrefixMigrationResult result = service.pinSymbolicRoot(dirD.resolve("missing").toString());

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).extracting(PathPrefixMigrationService.PrefixError::reason)
                .containsExactly("not-exist");
        assertThat(queryString("SELECT folder FROM artworks WHERE artwork_id = 1")).isEqualTo("{0}/100");
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

    private String stored(long id) {
        return mapper.findPathById(id);
    }

    private static String strip(Path dir) {
        return PathPrefixCodec.stripTrailingSeparators(dir.toString());
    }
}
