package top.sywyar.pixivdownload.download.db;

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
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService.PathPrefixMigrationResult;
import top.sywyar.pixivdownload.download.db.PathPrefixMigrationService.PathPrefixUpdate;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathPrefixMigrationService 批量改写测试")
class PathPrefixMigrationServiceTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private PathPrefixMapper mapper;
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

        PathPrefixCodec codec = new PathPrefixCodec(mapper, TestI18nBeans.appMessages());
        codec.init();

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
        service = new PathPrefixMigrationService(codec, mapper, new DownloadConfig(),
                TestI18nBeans.appMessages(), TransactionOperations.withoutTransaction());
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

    private String stored(long id) {
        return mapper.findPathById(id);
    }

    private static String strip(Path dir) {
        return PathPrefixCodec.stripTrailingSeparators(dir.toString());
    }
}
