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
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
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
