package top.sywyar.pixivdownload.core.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PixivDatabase 集成测试")
class PixivDatabaseTest {

    private PixivDatabase pixivDatabase;
    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() {
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

        // 绝对路径 root → 符号根 {0} 不启用，编码行为与历史一致
        top.sywyar.pixivdownload.core.appconfig.DownloadConfig downloadConfig =
                new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
        downloadConfig.setRootFolder(
                java.nio.file.Path.of("pixiv-download").toAbsolutePath().normalize().toString());
        PathPrefixCodec codec = new PathPrefixCodec(pathPrefixMapper, downloadConfig, TestI18nBeans.appMessages());
        codec.init();

        pixivDatabase = new PixivDatabase(mapper, TestI18nBeans.appMessages(), codec);
        pixivDatabase.init();

        // artwork_collections 由 CollectionMapper 建表，不在 PixivMapper.init() 范围内；
        // deleteArtwork 会清理该表，故测试库需手动补建，模拟生产环境的完整 schema。
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS artwork_collections ("
                    + "collection_id INTEGER NOT NULL,"
                    + "artwork_id INTEGER NOT NULL,"
                    + "added_time INTEGER NOT NULL,"
                    + "PRIMARY KEY (collection_id, artwork_id))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
        dataSource.destroy();
    }

    // ========== insertArtwork & getArtwork ==========

    @Nested
    @DisplayName("insertArtwork & getArtwork")
    class InsertAndGetTests {

        @Test
        @DisplayName("插入作品后应能查询到")
        void shouldInsertAndRetrieveArtwork() {
            pixivDatabase.insertArtwork(12345L, "测试作品", "/path/to/12345", 3, "jpg", 1700000001L, 0);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);

            assertThat(record).isNotNull();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.title()).isEqualTo("测试作品");
            assertThat(record.folder()).isEqualTo("/path/to/12345");
            assertThat(record.count()).isEqualTo(3);
            assertThat(record.extensions()).isEqualTo("jpg");
            assertThat(record.time()).isEqualTo(1700000001L);
            assertThat(record.xRestrict()).isEqualTo(0);
            assertThat(record.isAi()).isNull();
            assertThat(record.authorId()).isNull();
            assertThat(record.moved()).isFalse();
        }

        @Test
        @DisplayName("插入作品时应写入 authorId")
        void shouldInsertArtworkWithAuthorId() {
            pixivDatabase.insertArtwork(12346L, "author test", "/path/to/12346",
                    1, "png", 1700000007L, 0, 777L);

            ArtworkRecord record = pixivDatabase.getArtwork(12346L);

            assertThat(record).isNotNull();
            assertThat(record.authorId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("插入作品时应写入 isAi")
        void shouldInsertArtworkWithIsAi() {
            pixivDatabase.insertArtwork(12347L, "ai test", "/path/to/12347",
                    1, "png", 1700000008L, 0, true, 888L, "desc");

            ArtworkRecord record = pixivDatabase.getArtwork(12347L);

            assertThat(record).isNotNull();
            assertThat(record.isAi()).isTrue();
            assertThat(record.authorId()).isEqualTo(888L);
            assertThat(record.description()).isEqualTo("desc");
            assertThat(record.fileName()).isEqualTo(1L);
        }

        @Test
        @DisplayName("插入作品时应写入文件名模板 id")
        void shouldInsertArtworkWithFileNameTemplateId() {
            long templateId = pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}");

            pixivDatabase.insertArtwork(12348L, "file name test", "/path/to/12348",
                    1, "png", 1700000012L, 0, false, 888L, "desc", templateId);

            ArtworkRecord record = pixivDatabase.getArtwork(12348L);

            assertThat(record).isNotNull();
            assertThat(record.fileName()).isEqualTo(templateId);
            assertThat(pixivDatabase.getFileNameTemplate(templateId)).isEqualTo("{artwork_title}_p{page}");
            assertThat(pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}")).isEqualTo(templateId);
        }

        @Test
        @DisplayName("插入 R18 作品")
        void shouldInsertR18Artwork() {
            pixivDatabase.insertArtwork(11111L, "R18作品", "/path/r18", 1, "png", 1700000002L, 1);

            ArtworkRecord record = pixivDatabase.getArtwork(11111L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isEqualTo(1);
        }

        @Test
        @DisplayName("插入 R18G 作品")
        void shouldInsertR18GArtwork() {
            pixivDatabase.insertArtwork(11112L, "R18G作品", "/path/r18g", 1, "png", 1700000009L, 2);

            ArtworkRecord record = pixivDatabase.getArtwork(11112L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isEqualTo(2);
        }

        @Test
        @DisplayName("xRestrict 为 null 的作品")
        void shouldHandleNullXRestrict() {
            pixivDatabase.insertArtwork(22222L, "普通作品", "/path/normal", 1, "jpg", 1700000003L, (Integer) null);

            ArtworkRecord record = pixivDatabase.getArtwork(22222L);

            assertThat(record).isNotNull();
            assertThat(record.xRestrict()).isNull();
        }

        @Test
        @DisplayName("INSERT OR IGNORE 重复插入不应覆盖")
        void shouldIgnoreDuplicateInsert() {
            pixivDatabase.insertArtwork(12345L, "原始标题", "/path/1", 1, "jpg", 1700000004L, 0);
            pixivDatabase.insertArtwork(12345L, "新标题", "/path/2", 2, "png", 1700000005L, 1);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.title()).isEqualTo("原始标题");
            assertThat(record.folder()).isEqualTo("/path/1");
        }

        @Test
        @DisplayName("查询不存在的作品应返回 null")
        void shouldReturnNullForNonExistentArtwork() {
            assertThat(pixivDatabase.getArtwork(99999L)).isNull();
        }

        @Test
        @DisplayName("文件夹路径末尾斜杠应被去除")
        void shouldStripTrailingSlash() {
            pixivDatabase.insertArtwork(33333L, "test", "/path/to/folder/", 1, "jpg", 1700000006L, 0);

            ArtworkRecord record = pixivDatabase.getArtwork(33333L);
            assertThat(record.folder()).isEqualTo("/path/to/folder");
        }
    }

    // ========== hasArtwork ==========

    @Test
    @DisplayName("hasArtwork 应正确判断作品是否存在")
    void shouldCheckArtworkExistence() {
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();

        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000010L, 0);

        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();
    }

    // ========== deleteArtwork ==========

    @Test
    @DisplayName("删除作品后应查询不到")
    void shouldDeleteArtwork() {
        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();

        pixivDatabase.deleteArtwork(12345L);
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();
    }

    @Test
    @DisplayName("删除作品应一并清理标签关联与收藏夹关联")
    void shouldDeleteArtworkSatelliteRows() throws Exception {
        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        pixivDatabase.saveArtworkTags(12345L, List.of(new TagDto(null, "tag-a", null)));
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("INSERT INTO artwork_collections(collection_id, artwork_id, added_time) VALUES (1, 12345, 0)");
        }

        pixivDatabase.deleteArtwork(12345L);

        assertThat(pixivDatabase.getArtworkTags(12345L)).isEmpty();
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artwork_collections WHERE artwork_id = 12345")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    // ========== markArtworkDeleted（软删除） ==========

    @Test
    @DisplayName("软删除标记后主行保留：hasArtwork 仍命中、hasActiveArtwork 不命中、记录带 deleted 标志")
    void shouldMarkArtworkDeletedKeepingRow() {
        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isTrue();
        assertThat(pixivDatabase.isArtworkDeleted(12345L)).isFalse();

        pixivDatabase.markArtworkDeleted(12345L);

        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isFalse();
        assertThat(pixivDatabase.isArtworkDeleted(12345L)).isTrue();
        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.deleted()).isTrue();
    }

    @Test
    @DisplayName("软删除标记应照旧清理标签关联与收藏夹关联")
    void shouldMarkArtworkDeletedAndCleanSatelliteRows() throws Exception {
        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, 0);
        pixivDatabase.saveArtworkTags(12345L, List.of(new TagDto(null, "tag-a", null)));
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("INSERT INTO artwork_collections(collection_id, artwork_id, added_time) VALUES (1, 12345, 0)");
        }

        pixivDatabase.markArtworkDeleted(12345L);

        assertThat(pixivDatabase.getArtworkTags(12345L)).isEmpty();
        try (var conn = dataSource.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artwork_collections WHERE artwork_id = 12345")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("软删除的作品被重新下载落库后删除标记复位，记录被全新行替换")
    void shouldReviveDeletedArtworkOnReinsert() {
        pixivDatabase.insertArtwork(12345L, "old", "/old", 1, "jpg", 1700000011L, 0);
        pixivDatabase.markArtworkDeleted(12345L);

        pixivDatabase.insertArtwork(12345L, "new", "/new", 2, "png", 1700000012L, 1);

        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.deleted()).isFalse();
        assertThat(record.title()).isEqualTo("new");
        assertThat(record.folder()).isEqualTo("/new");
        assertThat(pixivDatabase.hasActiveArtwork(12345L)).isTrue();
    }

    @Test
    @DisplayName("未被软删除的已有记录重新插入时保持原行不被覆盖（INSERT OR IGNORE 语义不变）")
    void shouldKeepActiveRowOnReinsert() {
        pixivDatabase.insertArtwork(12345L, "old", "/old", 1, "jpg", 1700000011L, 0);

        pixivDatabase.insertArtwork(12345L, "new", "/new", 2, "png", 1700000012L, 1);

        ArtworkRecord record = pixivDatabase.getArtwork(12345L);
        assertThat(record).isNotNull();
        assertThat(record.title()).isEqualTo("old");
        assertThat(record.folder()).isEqualTo("/old");
    }

    @Test
    @DisplayName("软删除的作品不再出现在历史 ID 列表中")
    void shouldExcludeDeletedFromIdLists() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000020L, 0);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000021L, 0);
        pixivDatabase.markArtworkDeleted(2L);

        assertThat(pixivDatabase.getAllArtworkIds()).containsExactly(1L);
        assertThat(pixivDatabase.countArtworks()).isEqualTo(1);
    }

    // ========== getAllArtworkIds ==========

    @Test
    @DisplayName("获取所有作品ID")
    void shouldReturnAllArtworkIds() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000020L, 0);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000021L, 0);
        pixivDatabase.insertArtwork(3L, "c", "/c", 1, "jpg", 1700000022L, 0);

        List<Long> ids = pixivDatabase.getAllArtworkIds();
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ========== getArtworkIdsSortedByTimeDesc ==========

    @Test
    @DisplayName("按时间倒序排列")
    void shouldReturnIdsSortedByTimeDesc() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000030L, 0);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000032L, 0);
        pixivDatabase.insertArtwork(3L, "c", "/c", 1, "jpg", 1700000031L, 0);

        List<Long> ids = pixivDatabase.getArtworkIdsSortedByTimeDesc();
        assertThat(ids).containsExactly(2L, 3L, 1L);
    }

    // ========== getArtworkIdsSortedByTimeDescPaged ==========

    @Test
    @DisplayName("分页查询按时间倒序")
    void shouldReturnPagedResults() {
        for (int i = 1; i <= 20; i++) {
            pixivDatabase.insertArtwork(i, "art" + i, "/path/" + i, 1, "jpg", 1700000040L + i, 0);
        }

        List<Long> page0 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(0, 5);
        assertThat(page0).hasSize(5);
        assertThat(page0.get(0)).isEqualTo(20L);

        List<Long> page1 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(5, 5);
        assertThat(page1).hasSize(5);
        assertThat(page1.get(0)).isEqualTo(15L);
    }

    @Test
    @DisplayName("按作者排序分页时应将 null authorId 排在最后")
    void shouldReturnPagedResultsSortedByAuthorId() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 100L, 0, 20L);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 200L, 0, null);
        pixivDatabase.insertArtwork(3L, "c", "/c", 1, "jpg", 150L, 0, 10L);

        List<Long> ids = pixivDatabase.getArtworkIdsSortedByAuthorIdAscPaged(0, 10);

        assertThat(ids).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("应更新作品 authorId 并查询缺失 authorId 的记录")
    void shouldUpdateAndQueryMissingAuthorIds() {
        pixivDatabase.insertArtwork(10L, "a", "/a", 1, "jpg", 1000L, 0, null);
        pixivDatabase.insertArtwork(11L, "b", "/b", 1, "jpg", 1001L, 0, 88L);

        assertThat(pixivDatabase.getArtworkIdsMissingAuthor()).containsExactly(10L);

        pixivDatabase.updateAuthorId(10L, 99L);

        assertThat(pixivDatabase.getArtwork(10L).authorId()).isEqualTo(99L);
        assertThat(pixivDatabase.getArtworkIdsMissingAuthor()).isEmpty();
    }

    // ========== countArtworks ==========

    @Test
    @DisplayName("统计作品数量")
    void shouldCountArtworks() {
        assertThat(pixivDatabase.countArtworks()).isZero();

        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000050L, 0);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000051L, 0);

        assertThat(pixivDatabase.countArtworks()).isEqualTo(2);
    }

    // ========== updateArtworkMove ==========

    @Nested
    @DisplayName("updateArtworkMove")
    class MoveTests {

        @Test
        @DisplayName("更新移动信息")
        void shouldUpdateMoveInfo() {
            pixivDatabase.insertArtwork(12345L, "test", "/original/path", 1, "jpg", 1700000060L, 0);

            pixivDatabase.updateArtworkMove(12345L, "/new/path", 1700000070L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moved()).isTrue();
            assertThat(record.moveFolder()).isEqualTo("/new/path");
            assertThat(record.moveTime()).isEqualTo(1700000070L);
        }

        @Test
        @DisplayName("移动路径末尾斜杠应被去除")
        void shouldStripTrailingSlashOnMove() {
            pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000061L, 0);

            pixivDatabase.updateArtworkMove(12345L, "/new/path/", 1700000071L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moveFolder()).isEqualTo("/new/path");
        }

        @Test
        @DisplayName("传入 classifierTargetFolder 应把它注册为前缀，子目录共用 {N}/<seq>")
        void shouldRegisterClassifierTargetFolderAsPrefix() {
            pixivDatabase.insertArtwork(701001L, "a", "/orig/a", 1, "jpg", 1700000400L, 0);
            pixivDatabase.insertArtwork(701002L, "b", "/orig/b", 1, "jpg", 1700000401L, 0);

            String preset = "/cls/preset-root";
            // 多图先落到编号子目录，应被编码到同一个 preset 前缀下
            pixivDatabase.updateArtworkMove(701001L, preset + "/0", 1700000500L, preset);
            // 后续单图直接落到 preset 根，应复用同一行
            pixivDatabase.updateArtworkMove(701002L, preset, 1700000501L, preset);

            assertThat(pixivDatabase.getArtwork(701001L).moveFolder()).isEqualTo(preset + "/0");
            assertThat(pixivDatabase.getArtwork(701002L).moveFolder()).isEqualTo(preset);
            assertThat(pixivDatabase.getArtworkByMoveFolder(preset).artworkId()).isEqualTo(701002L);
            assertThat(pixivDatabase.getArtworkByMoveFolder(preset + "/0").artworkId()).isEqualTo(701001L);
        }

        @Test
        @DisplayName("移动到未注册路径时应自动注册前缀，下次同前缀子目录直接编码")
        void shouldAutoRegisterUnknownMoveTargetAsPrefix() {
            pixivDatabase.insertArtwork(700001L, "a", "/orig/a", 1, "jpg", 1700000200L, 0);
            pixivDatabase.insertArtwork(700002L, "b", "/orig/b", 1, "jpg", 1700000201L, 0);

            String firstTarget = "/dyn/added-after-startup";
            pixivDatabase.updateArtworkMove(700001L, firstTarget, 1700000300L);
            // 第二个作品移动到该前缀下的子目录，应被编码为 {N}/0 而不是绝对路径
            String secondTarget = firstTarget + "/0";
            pixivDatabase.updateArtworkMove(700002L, secondTarget, 1700000301L);

            ArtworkRecord first = pixivDatabase.getArtwork(700001L);
            ArtworkRecord second = pixivDatabase.getArtwork(700002L);
            assertThat(first.moveFolder()).isEqualTo(firstTarget);
            assertThat(second.moveFolder()).isEqualTo(secondTarget);
            assertThat(pixivDatabase.getArtworkByMoveFolder(firstTarget).artworkId()).isEqualTo(700001L);
            assertThat(pixivDatabase.getArtworkByMoveFolder(secondTarget).artworkId()).isEqualTo(700002L);
        }
    }

    // ========== getArtworkByMoveFolder ==========

    @Test
    @DisplayName("通过移动路径查找作品")
    void shouldFindArtworkByMoveFolder() {
        pixivDatabase.insertArtwork(12345L, "test", "/original", 1, "jpg", 1700000080L, 0);
        pixivDatabase.updateArtworkMove(12345L, "/moved/12345", 1700000090L);

        ArtworkRecord record = pixivDatabase.getArtworkByMoveFolder("/moved/12345");
        assertThat(record).isNotNull();
        assertThat(record.artworkId()).isEqualTo(12345L);

        // 带尾部斜杠也应能找到
        ArtworkRecord record2 = pixivDatabase.getArtworkByMoveFolder("/moved/12345/");
        assertThat(record2).isNotNull();
        assertThat(record2.artworkId()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("查找不存在的移动路径应返回 null")
    void shouldReturnNullForNonExistentMoveFolder() {
        assertThat(pixivDatabase.getArtworkByMoveFolder("/nonexistent")).isNull();
    }

    // ========== getArtworksOlderThan ==========

    @Test
    @DisplayName("查找早于指定时间的作品")
    void shouldReturnArtworksOlderThan() {
        pixivDatabase.insertArtwork(1L, "old", "/old", 1, "jpg", 1000L, 0);
        pixivDatabase.insertArtwork(2L, "new", "/new", 1, "jpg", 2000L, 0);

        List<ArtworkRecord> old = pixivDatabase.getArtworksOlderThan(1500L);
        assertThat(old).hasSize(1);
        assertThat(old.get(0).artworkId()).isEqualTo(1L);
    }

    // ========== statistics ==========

    @Nested
    @DisplayName("Statistics 统计操作")
    class StatisticsTests {

        @Test
        @DisplayName("初始统计应全为 0")
        void shouldHaveZeroInitialStats() {
            int[] stats = pixivDatabase.getStats();
            assertThat(stats).containsExactly(0, 0, 0);
        }

        @Test
        @DisplayName("incrementStats 应递增统计")
        void shouldIncrementStats() {
            pixivDatabase.incrementStats(5);
            pixivDatabase.incrementStats(3);

            int[] stats = pixivDatabase.getStats();
            assertThat(stats[0]).isEqualTo(2);  // total_artworks
            assertThat(stats[1]).isEqualTo(8);  // total_images
        }

        @Test
        @DisplayName("incrementMoved 应递增移动数")
        void shouldIncrementMoved() {
            pixivDatabase.incrementMoved();
            pixivDatabase.incrementMoved();

            int[] stats = pixivDatabase.getStats();
            assertThat(stats[2]).isEqualTo(2);  // total_moved
        }

        @Test
        @DisplayName("setStats 应直接设置统计值")
        void shouldSetStats() {
            pixivDatabase.setStats(100, 500, 30);

            int[] stats = pixivDatabase.getStats();
            assertThat(stats).containsExactly(100, 500, 30);
        }
    }

    // ========== getUniqueTime ==========

    @Test
    @DisplayName("getUniqueTime 应返回不与已有记录冲突的时间戳")
    void shouldReturnUniqueTime() {
        long time1 = pixivDatabase.getUniqueTime();
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", time1, 0);

        long time2 = pixivDatabase.getUniqueTime();
        assertThat(time2).isGreaterThanOrEqualTo(time1);

        // 确保 time2 与 time1 不冲突
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", time2, 0);
        assertThat(pixivDatabase.hasArtwork(2L)).isTrue();
    }
    @Test
    @DisplayName("重复初始化时不应因 authorId 列迁移失败")
    void shouldAllowRepeatedInit() {
        assertThatCode(() -> pixivDatabase.init()).doesNotThrowAnyException();
    }
}
