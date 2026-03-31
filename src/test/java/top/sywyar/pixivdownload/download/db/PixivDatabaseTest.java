package top.sywyar.pixivdownload.download.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true); // auto-commit
        PixivMapper mapper = sqlSession.getMapper(PixivMapper.class);

        pixivDatabase = new PixivDatabase(mapper);
        pixivDatabase.init();
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
            pixivDatabase.insertArtwork(12345L, "测试作品", "/path/to/12345", 3, "jpg", 1700000001L, false);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);

            assertThat(record).isNotNull();
            assertThat(record.artworkId()).isEqualTo(12345L);
            assertThat(record.title()).isEqualTo("测试作品");
            assertThat(record.folder()).isEqualTo("/path/to/12345");
            assertThat(record.count()).isEqualTo(3);
            assertThat(record.extensions()).isEqualTo("jpg");
            assertThat(record.time()).isEqualTo(1700000001L);
            assertThat(record.isR18()).isFalse();
            assertThat(record.moved()).isFalse();
        }

        @Test
        @DisplayName("插入 R18 作品")
        void shouldInsertR18Artwork() {
            pixivDatabase.insertArtwork(11111L, "R18作品", "/path/r18", 1, "png", 1700000002L, true);

            ArtworkRecord record = pixivDatabase.getArtwork(11111L);

            assertThat(record).isNotNull();
            assertThat(record.isR18()).isTrue();
        }

        @Test
        @DisplayName("isR18 为 null 的作品")
        void shouldHandleNullR18() {
            pixivDatabase.insertArtwork(22222L, "普通作品", "/path/normal", 1, "jpg", 1700000003L, null);

            ArtworkRecord record = pixivDatabase.getArtwork(22222L);

            assertThat(record).isNotNull();
            assertThat(record.isR18()).isNull();
        }

        @Test
        @DisplayName("INSERT OR IGNORE 重复插入不应覆盖")
        void shouldIgnoreDuplicateInsert() {
            pixivDatabase.insertArtwork(12345L, "原始标题", "/path/1", 1, "jpg", 1700000004L, false);
            pixivDatabase.insertArtwork(12345L, "新标题", "/path/2", 2, "png", 1700000005L, true);

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
            pixivDatabase.insertArtwork(33333L, "test", "/path/to/folder/", 1, "jpg", 1700000006L, false);

            ArtworkRecord record = pixivDatabase.getArtwork(33333L);
            assertThat(record.folder()).isEqualTo("/path/to/folder");
        }
    }

    // ========== hasArtwork ==========

    @Test
    @DisplayName("hasArtwork 应正确判断作品是否存在")
    void shouldCheckArtworkExistence() {
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();

        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000010L, false);

        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();
    }

    // ========== deleteArtwork ==========

    @Test
    @DisplayName("删除作品后应查询不到")
    void shouldDeleteArtwork() {
        pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000011L, false);
        assertThat(pixivDatabase.hasArtwork(12345L)).isTrue();

        pixivDatabase.deleteArtwork(12345L);
        assertThat(pixivDatabase.hasArtwork(12345L)).isFalse();
    }

    // ========== getAllArtworkIds ==========

    @Test
    @DisplayName("获取所有作品ID")
    void shouldReturnAllArtworkIds() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000020L, false);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000021L, false);
        pixivDatabase.insertArtwork(3L, "c", "/c", 1, "jpg", 1700000022L, false);

        List<Long> ids = pixivDatabase.getAllArtworkIds();
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ========== getArtworkIdsSortedByTimeDesc ==========

    @Test
    @DisplayName("按时间倒序排列")
    void shouldReturnIdsSortedByTimeDesc() {
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000030L, false);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000032L, false);
        pixivDatabase.insertArtwork(3L, "c", "/c", 1, "jpg", 1700000031L, false);

        List<Long> ids = pixivDatabase.getArtworkIdsSortedByTimeDesc();
        assertThat(ids).containsExactly(2L, 3L, 1L);
    }

    // ========== getArtworkIdsSortedByTimeDescPaged ==========

    @Test
    @DisplayName("分页查询按时间倒序")
    void shouldReturnPagedResults() {
        for (int i = 1; i <= 20; i++) {
            pixivDatabase.insertArtwork(i, "art" + i, "/path/" + i, 1, "jpg", 1700000040L + i, false);
        }

        List<Long> page0 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(0, 5);
        assertThat(page0).hasSize(5);
        assertThat(page0.get(0)).isEqualTo(20L);

        List<Long> page1 = pixivDatabase.getArtworkIdsSortedByTimeDescPaged(5, 5);
        assertThat(page1).hasSize(5);
        assertThat(page1.get(0)).isEqualTo(15L);
    }

    // ========== countArtworks ==========

    @Test
    @DisplayName("统计作品数量")
    void shouldCountArtworks() {
        assertThat(pixivDatabase.countArtworks()).isZero();

        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", 1700000050L, false);
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", 1700000051L, false);

        assertThat(pixivDatabase.countArtworks()).isEqualTo(2);
    }

    // ========== updateArtworkMove ==========

    @Nested
    @DisplayName("updateArtworkMove")
    class MoveTests {

        @Test
        @DisplayName("更新移动信息")
        void shouldUpdateMoveInfo() {
            pixivDatabase.insertArtwork(12345L, "test", "/original/path", 1, "jpg", 1700000060L, false);

            pixivDatabase.updateArtworkMove(12345L, "/new/path", 1700000070L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moved()).isTrue();
            assertThat(record.moveFolder()).isEqualTo("/new/path");
            assertThat(record.moveTime()).isEqualTo(1700000070L);
        }

        @Test
        @DisplayName("移动路径末尾斜杠应被去除")
        void shouldStripTrailingSlashOnMove() {
            pixivDatabase.insertArtwork(12345L, "test", "/path", 1, "jpg", 1700000061L, false);

            pixivDatabase.updateArtworkMove(12345L, "/new/path/", 1700000071L);

            ArtworkRecord record = pixivDatabase.getArtwork(12345L);
            assertThat(record.moveFolder()).isEqualTo("/new/path");
        }
    }

    // ========== getArtworkByMoveFolder ==========

    @Test
    @DisplayName("通过移动路径查找作品")
    void shouldFindArtworkByMoveFolder() {
        pixivDatabase.insertArtwork(12345L, "test", "/original", 1, "jpg", 1700000080L, false);
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
        pixivDatabase.insertArtwork(1L, "old", "/old", 1, "jpg", 1000L, false);
        pixivDatabase.insertArtwork(2L, "new", "/new", 1, "jpg", 2000L, false);

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
        pixivDatabase.insertArtwork(1L, "a", "/a", 1, "jpg", time1, false);

        long time2 = pixivDatabase.getUniqueTime();
        assertThat(time2).isGreaterThanOrEqualTo(time1);

        // 确保 time2 与 time1 不冲突
        pixivDatabase.insertArtwork(2L, "b", "/b", 1, "jpg", time2, false);
        assertThat(pixivDatabase.hasArtwork(2L)).isTrue();
    }
}
