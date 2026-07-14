package top.sywyar.pixivdownload.douyin.db.history;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.douyin.DouyinPlugin;
import top.sywyar.pixivdownload.douyin.client.DefaultDouyinClient;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadedFile;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinHistoryService 抖音下载历史服务")
class DouyinHistoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("插入历史时编码路径，查询时还原路径")
    void recordsCompletedWorkWithPathPrefixCodec() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            Path folder = tempDir.resolve("owner").resolve("7351");
            Path file = folder.resolve("7351.mp4");

            boolean inserted = db.service.recordCompleted(work("7351"), folder,
                    List.of(new DouyinDownloadedFile(file, 12L)));

            assertThat(inserted).isTrue();
            assertThat(db.service.hasWork("7351")).isTrue();
            assertThat(db.service.hasActiveWork("7351")).isTrue();
            assertThat(db.mapper.findAnyById("7351").folder()).startsWith("{");
            assertThat(db.service.findById("7351")).get()
                    .extracting(DouyinWorkRecord::folder)
                    .satisfies(value -> assertThat(Path.of((String) value).normalize())
                            .isEqualTo(folder.toAbsolutePath().normalize()));
            assertThat(db.service.findFilesByWorkId("7351")).singleElement()
                    .satisfies(row -> {
                        assertThat(row.fileIndex()).isZero();
                        assertThat(row.mediaType()).isEqualTo(DouyinMediaType.VIDEO.name());
                        assertThat(row.fileName()).isEqualTo("7351.mp4");
                        assertThat(row.extension()).isEqualTo("mp4");
                        assertThat(row.bytes()).isEqualTo(12L);
                    });
        }
    }

    @Test
    @DisplayName("下载响应的真实媒体类型优先写入文件历史")
    void recordsActualResponseContentType() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            Path folder = tempDir.resolve("owner").resolve("content-type");
            Path file = folder.resolve("content-type.mp4");

            assertThat(db.service.recordCompleted(work("content-type"), folder,
                    List.of(new DouyinDownloadedFile(file, 12L, "video/x-m4v")))).isTrue();

            assertThat(db.service.findFilesByWorkId("content-type")).singleElement()
                    .extracting(DouyinWorkFileRecord::contentType)
                    .isEqualTo("video/x-m4v");
        }
    }

    @Test
    @DisplayName("aweme 文本字段解析后完整写入历史记录")
    void recordsParsedAwemeTextFields() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7361","desc":"Desc text","item_title":"Item text","caption":"Caption text",
                "share_info":{"share_title":"Share text"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/7361.mp4"]}}}}
                """);
        DouyinWork parsed = client.resolvePublicWork("https://www.douyin.com/video/7361", null);

        try (TestDb db = new TestDb(tempDir)) {
            Path folder = tempDir.resolve("owner").resolve("7361");
            boolean inserted = db.service.recordCompleted(parsed, folder,
                    List.of(new DouyinDownloadedFile(folder.resolve("7361.mp4"), 21L)));

            assertThat(inserted).isTrue();
            assertThat(db.service.findById("7361")).get()
                    .satisfies(row -> {
                        assertThat(row.title()).isEqualTo("Item text");
                        assertThat(row.description()).isEqualTo("Desc text");
                        assertThat(row.itemTitle()).isEqualTo("Item text");
                        assertThat(row.caption()).isEqualTo("Caption text");
                    });
        }
    }

    @Test
    @DisplayName("软删除后默认查询排除记录，hasWork 仍保留三态事实")
    void softDeleteSemantics() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            db.service.recordCompleted(work("7352"), tempDir.resolve("owner").resolve("7352"),
                    List.of(new DouyinDownloadedFile(tempDir.resolve("7352.mp4"), 9L)));

            assertThat(db.service.markDeleted("7352")).isTrue();

            assertThat(db.service.findById("7352")).isEmpty();
            assertThat(db.service.hasWork("7352")).isTrue();
            assertThat(db.service.hasActiveWork("7352")).isFalse();
            assertThat(db.service.isDeleted("7352")).isTrue();
            assertThat(db.service.deleteIfMarkedDeleted("7352")).isTrue();
            assertThat(db.service.hasWork("7352")).isFalse();
            assertThat(db.service.findFilesByWorkId("7352")).isEmpty();
        }
    }

    @Test
    @DisplayName("重新下载已软删除作品会清理残留并写入新文件记录")
    void redownloadDeletedWorkReplacesResidualRows() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            db.service.recordCompleted(work("7353"), tempDir.resolve("old").resolve("7353"),
                    List.of(new DouyinDownloadedFile(tempDir.resolve("old.mp4"), 9L)));
            db.service.markDeleted("7353");

            boolean inserted = db.service.recordCompleted(work("7353"), tempDir.resolve("new").resolve("7353"),
                    List.of(new DouyinDownloadedFile(tempDir.resolve("new.mp4"), 18L)));

            assertThat(inserted).isTrue();
            assertThat(db.service.hasActiveWork("7353")).isTrue();
            assertThat(db.service.isDeleted("7353")).isFalse();
            assertThat(db.service.findFilesByWorkId("7353")).singleElement()
                    .satisfies(row -> {
                        assertThat(row.fileName()).isEqualTo("new.mp4");
                        assertThat(row.bytes()).isEqualTo(18L);
                    });
        }
    }

    @Test
    @DisplayName("活动历史缺失文件行时重新下载会刷新作品元数据并完整替换文件")
    void redownloadActiveWorkRepairsMissingFiles() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            db.service.recordCompleted(record("repair", "Old", 1000L, "author", "Author"),
                    files("repair", 1000L));
            db.mapper.deleteFilesByWorkId("repair");
            DouyinWorkRecord repaired = record("repair", "Repaired", 2000L, "author", "Author");
            List<DouyinWorkFileRecord> repairedFiles = List.of(
                    file("repair", 0, DouyinMediaType.VIDEO.name()),
                    file("repair", 1, DouyinMediaType.COVER.name()));

            assertThat(db.service.recordCompleted(repaired, repairedFiles)).isTrue();

            assertThat(db.service.findById("repair")).get().satisfies(row -> {
                assertThat(row.title()).isEqualTo("Repaired");
                assertThat(row.count()).isEqualTo(1);
                assertThat(row.time()).isEqualTo(2000L);
            });
            assertThat(db.service.findFilesByWorkId("repair"))
                    .extracting(DouyinWorkFileRecord::fileIndex, DouyinWorkFileRecord::mediaType)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, DouyinMediaType.VIDEO.name()),
                            org.assertj.core.groups.Tuple.tuple(1, DouyinMediaType.COVER.name()));
        }
    }

    @Test
    @DisplayName("活动历史后来启用封面时会替换文件集合并保留已有来源关系")
    void redownloadActiveWorkAddsCoverAndKeepsRelations() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            DouyinSourceRelation relation = new DouyinSourceRelation(
                    "cover-repair", DouyinSourceTypes.SEARCH, "cat", "cat", null, 0, 1000L);
            DouyinWorkRecord initial = record("cover-repair", "Initial", 1000L, "author", "Author");
            db.service.recordCompleted(initial, files("cover-repair", 1000L), relation);
            DouyinWorkRecord refreshed = record("cover-repair", "Refreshed", 2000L, "author", "Author");

            assertThat(db.service.recordCompleted(refreshed, List.of(
                    file("cover-repair", 0, DouyinMediaType.VIDEO.name()),
                    file("cover-repair", 1, DouyinMediaType.COVER.name())), List.of())).isTrue();

            assertThat(db.service.findFilesByWorkId("cover-repair"))
                    .extracting(DouyinWorkFileRecord::mediaType)
                    .containsExactly(DouyinMediaType.VIDEO.name(), DouyinMediaType.COVER.name());
            assertThat(db.service.findRelationsByWorkId("cover-repair")).singleElement()
                    .satisfies(row -> assertThat(row.sourceId()).isEqualTo("cat"));
        }
    }

    @Test
    @DisplayName("分页搜索和作者 facet 只读取未软删除历史")
    void searchAndAuthorFacetsReadOnlyActiveHistory() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            db.service.recordCompleted(record("7354", "Alpha", 1000L, "author-1", "Same"),
                    files("7354", 1000L));
            db.service.recordCompleted(record("7355", "Beta", 2000L, "author-2", "Other"),
                    files("7355", 2000L));
            db.service.recordCompleted(record("7356", "Gamma", 3000L, "author-1", "Same"),
                    files("7356", 3000L));
            db.service.markDeleted("7356");

            DouyinHistoryPage page = db.service.search(new DouyinHistoryQuery(
                    0, 10, "title", "asc", null, List.of()));

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.works()).extracting(DouyinWorkRecord::workId)
                    .containsExactly("7354", "7355");
            assertThat(db.service.search(new DouyinHistoryQuery(
                    0, 10, "time", "desc", null, List.of("author-1"))).works())
                    .extracting(DouyinWorkRecord::workId)
                    .containsExactly("7354");
            assertThat(db.service.authorFacets(null, 10))
                    .extracting(DouyinAuthorSummary::authorId,
                            DouyinAuthorSummary::name,
                            DouyinAuthorSummary::workCount)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("author-1", "Same", 1L),
                            org.assertj.core.groups.Tuple.tuple("author-2", "Other", 1L));
        }
    }

    @Test
    @DisplayName("媒体 EXISTS 谓词在分页计数和作者 facet 中保持一致")
    void mediaExistsPredicateIsSharedByPageCountAndFacets() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            db.service.recordCompleted(record("image", "Image", 1000L, "author-image", "Image Author"),
                    List.of(file("image", 0, "IMAGE"), file("image", 1, "COVER")));
            db.service.recordCompleted(record("video", "Video", 2000L, "author-video", "Video Author"),
                    List.of(file("video", 0, "VIDEO"), file("video", 1, "COVER")));
            db.service.recordCompleted(record("mixed", "Mixed", 3000L, "author-mixed", "Mixed Author"),
                    List.of(file("mixed", 0, "IMAGE"), file("mixed", 1, "LIVE_PHOTO_VIDEO")));
            db.service.recordCompleted(record("cover", "Cover", 4000L, "author-cover", "Cover Author"),
                    List.of(file("cover", 0, "COVER")));

            DouyinHistoryQuery imageQuery = new DouyinHistoryQuery(
                    0, 20, "time", "asc", null, List.of(), List.of("IMAGE"));
            DouyinHistoryQuery videoQuery = new DouyinHistoryQuery(
                    0, 20, "time", "asc", null, List.of(), List.of("VIDEO", "LIVE_PHOTO_VIDEO"));

            assertThat(db.service.search(imageQuery).works()).extracting(DouyinWorkRecord::workId)
                    .containsExactly("image", "mixed");
            assertThat(db.service.search(imageQuery).total()).isEqualTo(2);
            assertThat(db.service.authorFacets(imageQuery)).extracting(DouyinAuthorSummary::authorId)
                    .containsExactlyInAnyOrder("author-image", "author-mixed");
            assertThat(db.service.search(videoQuery).works()).extracting(DouyinWorkRecord::workId)
                    .containsExactly("video", "mixed");
            assertThat(db.service.search(videoQuery).total()).isEqualTo(2);
            assertThat(db.service.authorFacets(videoQuery)).extracting(DouyinAuthorSummary::authorId)
                    .containsExactlyInAnyOrder("author-video", "author-mixed");
        }
    }

    @Test
    @DisplayName("同一作品重复发现时不重复主记录并幂等保留全部来源关系")
    void repeatedDiscoveryAddsRelationsWithoutDuplicatingWork() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            Path folder = tempDir.resolve("owner").resolve("relation-work");
            List<DouyinDownloadedFile> files = List.of(
                    new DouyinDownloadedFile(folder.resolve("relation-work.mp4"), 12L));
            DouyinSourceRelation search = new DouyinSourceRelation(
                    "relation-work", DouyinSourceTypes.SEARCH, "猫", "猫", null, 0, 1000L);
            DouyinSourceRelation user = new DouyinSourceRelation(
                    "relation-work", DouyinSourceTypes.USER, "sec-user", "作者", null, 1, 2000L);

            assertThat(db.service.recordCompleted(work("relation-work"), folder, files,
                    null, null, null, null, search)).isTrue();
            assertThat(db.service.recordCompleted(work("relation-work"), folder, files,
                    null, null, null, null, user)).isTrue();
            assertThat(db.service.recordRelation(search)).isTrue();

            assertThat(db.mapper.countById("relation-work")).isEqualTo(1);
            assertThat(db.service.findRelationsByWorkId("relation-work"))
                    .extracting(DouyinSourceRelation::sourceType, DouyinSourceRelation::sourceId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(DouyinSourceTypes.SEARCH, "猫"),
                            org.assertj.core.groups.Tuple.tuple(DouyinSourceTypes.USER, "sec-user"));
        }
    }

    @Test
    @DisplayName("旧历史可按已知合集字段回填来源关系且不编造未知容器")
    void backfillsRelationsFromPublishedHistoryFields() throws Exception {
        try (TestDb db = new TestDb(tempDir)) {
            DouyinWorkRecord legacy = record("legacy", "Legacy", 5000L, "author", "Author");
            legacy = new DouyinWorkRecord(legacy.workId(), legacy.title(), legacy.folder(), legacy.count(),
                    legacy.extensions(), legacy.time(), legacy.deleted(), legacy.kind(), legacy.sourceUrl(),
                    legacy.canonicalUrl(), legacy.thumbnailUrl(), legacy.authorId(), legacy.authorName(),
                    legacy.description(), legacy.itemTitle(), legacy.caption(), legacy.publishTime(),
                    "mix-known", "Known mix", 3);
            db.mapper.insertWork(legacy);
            assertThat(db.service.findRelationsByWorkId("legacy")).isEmpty();

            assertThat(db.service.backfillRelations()).isEqualTo(1);

            assertThat(db.service.findRelationsByWorkId("legacy")).singleElement()
                    .satisfies(relation -> {
                        assertThat(relation.sourceType()).isEqualTo(DouyinSourceTypes.COLLECTION);
                        assertThat(relation.sourceId()).isEqualTo("mix-known");
                        assertThat(relation.sourceOrder()).isEqualTo(3);
                    });
        }
    }

    private static DouyinWork work(String id) {
        return new DouyinWork(id,
                "Title " + id,
                "author-1",
                "Author",
                "https://www.douyin.com/video/" + id,
                "https://p3.douyinpic.com/" + id + ".jpg",
                URI.create("https://v3.douyinvod.com/" + id + ".mp4"),
                List.of(new DouyinMedia(id, DouyinMediaType.VIDEO,
                        URI.create("https://v3.douyinvod.com/" + id + ".mp4"),
                        id, "mp4", 12L, "video/mp4")),
                null,
                1_710_000_000L,
                null,
                null);
    }

    private DouyinWorkRecord record(String id, String title, long time, String authorId, String authorName) {
        return new DouyinWorkRecord(
                id,
                title,
                tempDir.resolve("owner").resolve(id).toString(),
                1,
                "mp4",
                time,
                false,
                DouyinWorkKind.VIDEO.name(),
                "https://v.douyin.com/" + id + "/",
                "https://www.douyin.com/video/" + id,
                null,
                authorId,
                authorName,
                "Desc " + id,
                "Item " + id,
                "Caption " + id,
                time + 10,
                null,
                null,
                null);
    }

    private static List<DouyinWorkFileRecord> files(String id, long createdTime) {
        return List.of(new DouyinWorkFileRecord(
                id, 0, id, DouyinMediaType.VIDEO.name(), id + ".mp4", "mp4",
                12L, "video/mp4", createdTime));
    }

    private static DouyinWorkFileRecord file(String id, int index, String mediaType) {
        String extension = "IMAGE".equals(mediaType) || "COVER".equals(mediaType) ? "jpg" : "mp4";
        return new DouyinWorkFileRecord(id, index, id + "-" + index, mediaType,
                id + "-" + index + "." + extension, extension, 12L, null, 1000L + index);
    }

    private static DefaultDouyinClient client(String body) {
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, new FakeRestTemplate(body),
                (input, cookie) -> parser.parse(input).orElseThrow());
    }

    private static final class FakeRestTemplate extends RestTemplate {
        private final byte[] body;

        private FakeRestTemplate(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            return new ResponseEntity<>((T) body, new HttpHeaders(), HttpStatusCode.valueOf(200));
        }
    }

    private static final class TestDb implements AutoCloseable {
        private final SingleConnectionDataSource dataSource;
        private final SqlSession session;
        private final DouyinHistoryMapper mapper;
        private final DouyinHistoryService service;

        private TestDb(Path root) {
            dataSource = newDataSource();
            DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(
                    new PluginRegistry(List.of(new CorePlugin(), new DouyinPlugin())));
            new DatabaseInitializer(new JdbcTemplate(dataSource),
                    registry.contributions(), registry.mergedSchema(), messages(), event -> {}).initialize();

            Configuration config = new Configuration();
            config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
            config.addMapper(PathPrefixMapper.class);
            config.addMapper(DouyinHistoryMapper.class);
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
            session = factory.openSession(true);
            mapper = session.getMapper(DouyinHistoryMapper.class);

            DownloadConfig downloadConfig = new DownloadConfig();
            downloadConfig.setRootFolder(root.toAbsolutePath().normalize().toString());
            PathPrefixCodec codec = new PathPrefixCodec(
                    session.getMapper(PathPrefixMapper.class), downloadConfig, messages());
            codec.init();
            codec.getOrCreatePrefixId(root.toAbsolutePath().normalize().toString());

            DouyinHistoryRepository repository = new DouyinHistoryRepository(mapper, codec);
            service = new DouyinHistoryService(repository);
        }

        @Override
        public void close() {
            session.close();
            dataSource.destroy();
        }

        private static SingleConnectionDataSource newDataSource() {
            SingleConnectionDataSource ds = new SingleConnectionDataSource();
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setUrl("jdbc:sqlite::memory:");
            ds.setSuppressClose(true);
            return ds;
        }
    }

    private static AppMessages messages() {
        return new AppMessages(new StaticMessageSource());
    }
}
