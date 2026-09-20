package top.sywyar.pixivdownload.quota;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.PixivMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额 / 归档删除链路的失败一致性回归测试。
 *
 * <p>刻意不使用 Mockito（本机 JDK 25 上 Mockito 无法自附加、测试跑不起来），改用内存 SQLite +
 * 真实临时目录驱动 {@link UserQuotaService}，直接断言「文件与下载历史记录要么都在、要么都不在」：
 * 文件删除成功 → 记录被删；文件删除失败 → 原子回滚 + 记录保留；共享目录里不误删其它作品的文件与记录。
 */
@DisplayName("UserQuotaService 删除失败一致性（真实文件 + 内存 SQLite）")
class UserQuotaDeleteConsistencyTest {

    /** 与默认模板一致的命名，便于用主干精确匹配到作品文件。 */
    private static final String FILE_NAME_TEMPLATE = "{artwork_id}_p{page}";
    /** 远早于任何 cutoff 的时间戳（timed-delete 的判定列）。 */
    private static final long OLD_TIME = 1_000L;

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private PixivDatabase pixivDatabase;
    private DownloadConfig downloadConfig;
    private MultiModeConfig multiModeConfig;
    private final StagedFileDeletion stagedFileDeletion = new StagedFileDeletion(TestI18nBeans.appMessages());

    @BeforeEach
    void setUp() {
        // 原子删除的暂存目录走 RuntimeFiles：测试中指向临时 data 目录，避免污染工作目录
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("runtime-data").toString());

        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PixivMapper.class);
        config.addMapper(PathPrefixMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true); // auto-commit
        PixivMapper mapper = sqlSession.getMapper(PixivMapper.class);
        PathPrefixMapper pathPrefixMapper = sqlSession.getMapper(PathPrefixMapper.class);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        DatabaseInitializer initializer = new DatabaseInitializer(
                new JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {
        });
        initializer.initialize();

        // 绝对路径 root → 符号根 {0} 不启用，记录里的 folder 即写入时的绝对路径
        downloadConfig = new DownloadConfig();
        downloadConfig.setRootFolder(tempDir.toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(pathPrefixMapper, downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(mapper, TestI18nBeans.appMessages(), codec, initializer);
        pixivDatabase.init();

        multiModeConfig = new MultiModeConfig();
        multiModeConfig.getQuota().setEnabled(true);
        multiModeConfig.getQuota().setMaxArtworks(10);
        multiModeConfig.setPostDownloadMode("timed-delete");
        multiModeConfig.setDeleteAfterHours(1);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        sqlSession.close();
        dataSource.destroy();
    }

    // ========== timed-delete：按记录删除，失败即保记录 ==========

    @Test
    @DisplayName("文件全部删除成功后才删除下载历史记录，作品独占目录随空目录回收")
    void timedCleanupDeletesFilesThenRecord() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("101"));
        Path image = Files.writeString(dir.resolve("101_p0.jpg"), "img");
        Path sidecar = Files.writeString(dir.resolve("101.meta.json"), "{}");
        insertArtwork(101L, dir, OLD_TIME, 1);

        serviceWith(locator()).cleanupTimedDeleteArtworks();

        assertThat(image).doesNotExist();
        assertThat(sidecar).doesNotExist();
        assertThat(dir).doesNotExist();
        assertThat(pixivDatabase.getArtwork(101L)).isNull();
    }

    @Test
    @DisplayName("文件删除失败（被占用）时回滚文件并保留下载历史记录")
    void timedCleanupKeepsRecordWhenFileDeletionFails() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("202"));
        Path image = Files.writeString(dir.resolve("202_p0.jpg"), "img");
        Path sidecar = Files.writeString(dir.resolve("202.meta.json"), "{}");
        insertArtwork(202L, dir, OLD_TIME, 1);

        serviceWith(failingLocator(dir.resolve("202_p0.jpg"))).cleanupTimedDeleteArtworks();

        assertThat(image).exists();
        assertThat(sidecar).exists();
        assertThat(pixivDatabase.getArtwork(202L)).isNotNull();
    }

    @Test
    @DisplayName("共享目录里只删本作品的文件：同目录其它作品的文件与记录都不受影响")
    void timedCleanupDoesNotTouchOtherWorksInSharedDirectory() throws Exception {
        Path shared = Files.createDirectories(tempDir.resolve("author"));
        Path mine = Files.writeString(shared.resolve("303_p0.jpg"), "mine");
        Path other = Files.writeString(shared.resolve("404_p0.jpg"), "other");
        insertArtwork(303L, shared, OLD_TIME, 1);
        insertArtwork(404L, shared, System.currentTimeMillis(), 1); // 未超期，不参与本次清理

        serviceWith(locator()).cleanupTimedDeleteArtworks();

        assertThat(mine).doesNotExist();
        assertThat(other).exists();
        assertThat(shared).isDirectory();
        assertThat(pixivDatabase.getArtwork(303L)).isNull();
        assertThat(pixivDatabase.getArtwork(404L)).isNotNull();
    }

    // ========== 归档 pack-and-delete ==========

    @Test
    @DisplayName("pack-and-delete 成功后同时清理文件、记录与配额中的目录")
    void archivePackAndDeleteRemovesFilesAndRecord() throws Exception {
        multiModeConfig.setPostDownloadMode("pack-and-delete");
        Path dir = Files.createDirectories(tempDir.resolve("505"));
        Path image = Files.writeString(dir.resolve("505_p0.jpg"), "img");
        insertArtwork(505L, dir, OLD_TIME, 1);
        UserQuotaService service = serviceWith(locator());
        service.checkAndReserve("user1", 1);
        service.recordFolder("user1", dir);

        String token = service.triggerArchive("user1");

        assertThat(service.getArchive(token).getStatus()).isEqualTo("ready");
        assertThat(image).doesNotExist();
        assertThat(pixivDatabase.getArtwork(505L)).isNull();
        assertThat(service.getQuotaForUser("user1").getDownloadedFolders()).doesNotContain(dir);
    }

    @Test
    @DisplayName("pack-and-delete 文件删除失败时不删记录，目录保留在配额中等下次重试")
    void archivePackAndDeleteKeepsRecordWhenFileDeletionFails() throws Exception {
        multiModeConfig.setPostDownloadMode("pack-and-delete");
        Path dir = Files.createDirectories(tempDir.resolve("606"));
        Path image = Files.writeString(dir.resolve("606_p0.jpg"), "img");
        insertArtwork(606L, dir, OLD_TIME, 1);
        UserQuotaService service = serviceWith(failingLocator(dir.resolve("606_p0.jpg")));
        service.checkAndReserve("user1", 1);
        service.recordFolder("user1", dir);

        String token = service.triggerArchive("user1");

        assertThat(service.getArchive(token).getStatus()).isEqualTo("ready");
        assertThat(image).exists();
        assertThat(pixivDatabase.getArtwork(606L)).isNotNull();
        assertThat(service.getQuotaForUser("user1").getDownloadedFolders()).contains(dir);
    }

    @Test
    @DisplayName("pack-and-delete 不得用纯数字目录名反推 ID 删掉指向别处的作品记录")
    void archivePackAndDeleteDoesNotDeleteRecordOfUnrelatedNumericDirectory() throws Exception {
        multiModeConfig.setPostDownloadMode("pack-and-delete");
        Path dir = Files.createDirectories(tempDir.resolve("707"));
        Files.writeString(dir.resolve("707_p0.jpg"), "img");
        // 记录 707 的真实落点在别处：目录名恰为 707 不足以证明该目录属于这条记录
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Path kept = Files.writeString(elsewhere.resolve("707_p0.jpg"), "kept");
        insertArtwork(707L, elsewhere, OLD_TIME, 1);
        UserQuotaService service = serviceWith(locator());
        service.checkAndReserve("user1", 1);
        service.recordFolder("user1", dir);

        service.triggerArchive("user1");

        assertThat(kept).exists();
        assertThat(pixivDatabase.getArtwork(707L)).isNotNull();
    }

    // ========== 辅助 ==========

    private UserQuotaService serviceWith(ArtworkFileLocator locator) {
        return new UserQuotaService(
                multiModeConfig,
                downloadConfig,
                pixivDatabase,
                TestI18nBeans.appMessages(),
                Runnable::run,
                locator,
                stagedFileDeletion
        );
    }

    private ArtworkFileLocator locator() {
        return new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), stagedFileDeletion);
    }

    /** 模拟「目标文件被锁定 / 无权限」：删到该文件时抛 IOException，原子删除整体回滚。 */
    private ArtworkFileLocator failingLocator(Path poison) {
        Path normalizedPoison = poison.toAbsolutePath().normalize();
        StagedFileDeletion failingDeletion = new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(normalizedPoison)) {
                    throw new IOException("simulated lock on " + original);
                }
                super.deleteFile(original);
            }
        };
        return new ArtworkFileLocator(
                pixivDatabase, downloadConfig, TestI18nBeans.appMessages(), failingDeletion);
    }

    private void insertArtwork(long artworkId, Path folder, long time, int count) {
        long fileName = pixivDatabase.getOrCreateFileNameTemplateId(FILE_NAME_TEMPLATE);
        pixivDatabase.insertArtwork(InsertArtworkArgument.builder()
                .artworkId(artworkId)
                .title("t" + artworkId)
                .folder(folder.toString())
                .count(count)
                .extensions("jpg")
                .time(time)
                .xRestrict(0)
                .fileName(fileName)
                .build());
    }
}
