package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("回填元数据来源与上传时间")
class ArtworksBackFillSourcesTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanDirectoryAfterResourcesClose() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("并发补齐或软删除插画后，普通字段和关联字段均不虚报本次写入")
    void concurrentArtworkWritesAreNotCounted(boolean deleted) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("concurrent-fields.db"));
        initialize(target);
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute("UPDATE artworks SET author_id=NULL,r18=NULL");
        }
        var result = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(
                "{\"userId\":42,\"userName\":\"network author\",\"xRestrict\":1,\"aiType\":2,\"description\":\"network\","
                        + "\"seriesNavData\":{\"seriesId\":7,\"order\":3,\"title\":\"network series\"},\"tags\":{\"tags\":[{\"tag\":\"network tag\"}]}}"), 9999L, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenAnswer(ignored -> {
            try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                if (deleted) sql.execute("UPDATE artworks SET deleted=1");
                else {
                    sql.execute("UPDATE artworks SET author_id=42,r18=0,is_ai=0,description='concurrent',series_id=7,series_order=9,upload_time=1234");
                    sql.execute("INSERT INTO authors(author_id,name,updated_time) VALUES(42,'concurrent author',1)");
                    sql.execute("INSERT INTO manga_series(series_id,title,author_id,updated_time) VALUES(7,'concurrent series',42,1)");
                    sql.execute("INSERT INTO tags(tag_id,name) VALUES(1,'concurrent tag')");
                    sql.execute("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(1,1)");
                }
            }
            return result;
        });
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), true);
            var summary = ArtworksBackFill.run(options);
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.skipped()).isEqualTo(1);
            assertThat(java.util.List.of(summary.filledAuthor(), summary.filledR18(), summary.filledAi(),
                    summary.filledDescription(), summary.filledTags(), summary.filledSeries(), summary.filledUploadTime()))
                    .containsOnly(0);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT r18,is_ai,description,upload_time,series_order FROM artworks WHERE artwork_id=1")) {
            assertThat(row.next()).isTrue();
            if (deleted) for (int index = 1; index <= 5; index++) assertThat(row.getObject(index)).isNull();
            else {
                assertThat(row.getInt(1)).isZero(); assertThat(row.getInt(2)).isZero();
                assertThat(row.getString(3)).isEqualTo("concurrent");
                assertThat(row.getLong(4)).isEqualTo(1234); assertThat(row.getLong(5)).isEqualTo(9);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("受限响应不覆盖并发年龄标记或软删除作品，也不计为补齐")
    void restrictedResponseHonorsConcurrentWrites(boolean deleted) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("restricted-race.db"));
        initialize(target);
        try (var connection = target.openConnection(); var sql = connection.createStatement()) { sql.execute("UPDATE artworks SET r18=NULL"); }
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenAnswer(ignored -> {
            try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                sql.execute(deleted ? "UPDATE artworks SET deleted=1" : "UPDATE artworks SET r18=0");
            }
            return ArtworksBackFillPixivClient.LookupResult.r18Only("restricted");
        });
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), true);
            assertThat(ArtworksBackFill.run(options).filledR18()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("作者身份并发修正后按实际身份补姓名，拒绝的旧身份不计成功")
    void refreshesConflictingAuthorBeforeNetwork(boolean networkAvailable) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("author-conflict.db"));
        initialize(target);
        String snapshot = new WorkMetaCurator(mapper).curateArtwork(1,
                mapper.readTree("{\"id\":1,\"userId\":42}"), null, "forward").document().toString();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute("UPDATE artworks SET author_id=NULL,is_ai=0,description='keep',series_id=0,upload_time=1234");
            sql.execute("INSERT INTO tags(tag_id,name) VALUES(1,'fixture')");
            sql.execute("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(1,1)");
            try (var update = connection.prepareStatement("UPDATE artworks SET metadata_json=?")) {
                update.setString(1, snapshot);
                update.executeUpdate();
            }
        }
        var client = mock(ArtworksBackFillPixivClient.class);
        var network = ArtworksBackFillPixivClient.parsePayload(
                mapper.readTree("{\"id\":1,\"userId\":99,\"userName\":\"current author\"}"), null, true);
        when(client.query(1)).thenReturn(networkAvailable ? network : ArtworksBackFillPixivClient.LookupResult.skip("fixture"));
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> target.openConnection());
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), false);
            var summary = ArtworksBackFill.run(options, dataSource, (processed, total) -> {
                if (total > 0 && changed.compareAndSet(false, true)) {
                    try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                        sql.execute("UPDATE artworks SET author_id=99");
                    } catch (java.sql.SQLException failure) { throw new RuntimeException(failure); }
                }
            });
            assertThat(summary.filledAuthor()).isEqualTo(networkAvailable ? 1 : 0);
            verify(client).query(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT a.author_id,b.name FROM artworks a LEFT JOIN authors b ON b.author_id=a.author_id")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(99);
            assertThat(row.getString(2)).isEqualTo(networkAvailable ? "current author" : null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("不可达作品的部分快照耗尽后让出候选额度，预览和执行一致")
    void exhaustedLocalMetadataDoesNotStarveLaterCandidates(boolean sidecar) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("partial-limit.db"));
        initialize(target);
        String snapshot = new WorkMetaCurator(mapper).curateArtwork(1,
                mapper.readTree("{\"id\":1,\"description\":\"old description\",\"tags\":{\"tags\":[]}}"),
                null, "forward").document().toString();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) "
                    + "VALUES(2,'fixture','{0}',1,'jpg',2)");
            if (!sidecar) try (var update = connection.prepareStatement("UPDATE artworks SET metadata_json=? WHERE artwork_id=1")) {
                update.setString(1, snapshot);
                update.executeUpdate();
            }
        }
        if (sidecar) Files.writeString(directory.resolve("1.meta.json"), snapshot);
        var unreachablePath = directory.resolve("unreachable.json");
        var unreachable = ArtworksBackFillUnreachableStore.load(unreachablePath, mapper);
        unreachable.record(1, "HTTP 404");
        unreachable.save();
        var network = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("local", "network")), 4567L, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(2)).thenReturn(network);
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(unreachablePath);
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), false);
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
            var first = ArtworksBackFill.run(options);
            assertThat(first.processed()).isEqualTo(1);
            assertThat(first.filledDescription()).isEqualTo(1);
            assertThat(first.previouslyUnreachable()).isEqualTo(1);
            verify(client, never()).query(anyLong());
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
            var second = ArtworksBackFill.run(options);
            assertThat(second.processed()).isEqualTo(1);
            assertThat(second.filledDescription()).isEqualTo(1);
            assertThat(second.filledUploadTime()).isEqualTo(1);
            assertThat(second.previouslyUnreachable()).isEqualTo(1);
            assertThat(ArtworksBackFill.countCandidates(options)).isZero();
            assertThat(ArtworksBackFill.run(options).processed()).isZero();
            verify(client, never()).query(1);
            verify(client, times(1)).query(2);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT description,upload_time,metadata_json FROM artworks WHERE artwork_id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("old description");
            assertThat(row.getObject(2)).isNull();
            assertThat(row.getString(3)).isEqualTo(snapshot);
        }
        assertThat(ArtworksBackFillUnreachableStore.load(unreachablePath, mapper).contains(1)).isTrue();
        if (sidecar) assertThat(Files.readString(directory.resolve("1.meta.json"))).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("系列身份并发变化后按当前身份联网补缺，拒绝的本地系列不计成功")
    void refreshesConflictingSeriesBeforeNetwork(boolean networkAvailable) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("series-conflict.db"));
        initialize(target);
        String snapshot = new WorkMetaCurator(mapper).curateArtwork(1,
                mapper.readTree("{\"id\":1,\"description\":\"local\",\"seriesNavData\":{\"seriesId\":7,\"order\":3,\"title\":\"old-series\"}}"),
                null, "forward").document().toString();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE artworks SET is_ai=0,upload_time=1000");
            sql.executeUpdate("INSERT INTO tags(tag_id,name) VALUES(1,'fixture')");
            sql.executeUpdate("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(1,1)");
            try (var update = connection.prepareStatement("UPDATE artworks SET metadata_json=? WHERE artwork_id=1")) {
                update.setString(1, snapshot);
                update.executeUpdate();
            }
        }
        var network = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("local", "network")
                .replace("\"seriesNavData\":null", "\"seriesNavData\":{\"seriesId\":8,\"order\":5,\"title\":\"current-series\"}")), null, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenReturn(networkAvailable ? network : ArtworksBackFillPixivClient.LookupResult.skip("fixture"));
        var dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> target.openConnection());
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), false);
            var summary = ArtworksBackFill.run(options, dataSource, (processed, total) -> {
                if (total > 0 && changed.compareAndSet(false, true)) {
                    try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                        sql.executeUpdate("UPDATE artworks SET series_id=8 WHERE artwork_id=1");
                    } catch (java.sql.SQLException failure) { throw new RuntimeException(failure); }
                }
            });
            assertThat(changed).isTrue();
            assertThat(summary.filledDescription()).isEqualTo(1);
            assertThat(summary.filledSeries()).isEqualTo(networkAvailable ? 1 : 0);
            verify(client).query(1);
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(networkAvailable ? 0 : 1);
            if (networkAvailable) assertThat(ArtworksBackFill.run(options).processed()).isZero();
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT a.series_id,a.series_order,s.title,a.description,a.upload_time FROM artworks a "
                     + "LEFT JOIN manga_series s ON a.series_id=s.series_id")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(8);
            assertThat(row.getObject(2)).isEqualTo(networkAvailable ? 5 : null);
            assertThat(row.getString(3)).isEqualTo(networkAvailable ? "current-series" : null);
            assertThat(row.getString(4)).isEqualTo("local");
            assertThat(row.getLong(5)).isEqualTo(1000);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT COUNT(*) FROM manga_series WHERE series_id=7")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("候选上限前按来源过滤不可达快照，强制联网不饥饿且本地优先仍可恢复")
    void filtersUnreachableSnapshotsBeforeLimit(boolean alwaysNetwork) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("candidate-limit.db"));
        initialize(target);
        String snapshot = new WorkMetaCurator(mapper).curateArtwork(1,
                mapper.readTree(alwaysNetwork ? "{\"id\":1}" : BODY), null, "forward").document().toString();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            for (int id : new int[]{2, 3}) sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time) "
                    + "VALUES(" + id + ",'fixture','{0}',1,'jpg'," + id + ")");
            try (var update = connection.prepareStatement("UPDATE artworks SET metadata_json=? WHERE artwork_id=1")) {
                update.setString(1, snapshot);
                update.executeUpdate();
            }
        }
        var unreachablePath = directory.resolve("unreachable.json");
        var unreachable = ArtworksBackFillUnreachableStore.load(unreachablePath, mapper);
        unreachable.record(1, "HTTP 404");
        unreachable.save();
        var network = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("local", "network")), 4567L, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(anyLong())).thenReturn(network);
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(unreachablePath);
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 1, false, target)
                    .withSources(directory.toString(), alwaysNetwork);
            for (int id : alwaysNetwork ? new int[]{2, 3} : new int[]{1, 2, 3}) {
                assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
                var summary = ArtworksBackFill.run(options);
                assertThat(summary.totalCandidates()).isEqualTo(1);
                assertThat(summary.processed()).isEqualTo(1);
                assertThat(summary.filledDescription()).isEqualTo(1);
                assertThat(summary.previouslyUnreachable()).isEqualTo(alwaysNetwork ? 1 : 0);
                try (var connection = target.openConnection(); var sql = connection.createStatement();
                     var row = sql.executeQuery("SELECT description FROM artworks WHERE artwork_id=" + id)) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo(id == 1 ? "local" : "network");
                }
            }
            assertThat(ArtworksBackFill.countCandidates(options)).isZero();
            assertThat(ArtworksBackFill.run(options).totalCandidates()).isZero();
            verify(client, never()).query(1);
            verify(client, times(1)).query(2);
            verify(client, times(1)).query(3);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("partialSeriesCases")
    @DisplayName("部分本地系列与作者资料保持未知，网络只补缺项且重复运行不再请求")
    void fillsPartialSeriesAndAuthorWithoutPlaceholders(String navigation, boolean retryNetwork) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("series.db"));
        initialize(target);
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE artworks SET author_id=NULL");
        }
        var raw = mapper.readTree(BODY.replace("\"userName\":\"作者\",", "")
                .replace("\"seriesNavData\":null", "\"seriesNavData\":" + navigation));
        Files.writeString(directory.resolve("1.meta.json"), new WorkMetaCurator(mapper)
                .curateArtwork(1, raw, null, "forward").document().toString());
        var network = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("作者", "network-author")
                .replace("\"seriesNavData\":null", "\"seriesNavData\":{\"seriesId\":7,\"order\":8,\"title\":\"network-series\"}")), null, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        when(client.query(1)).thenAnswer(ignored -> {
            try (var connection = target.openConnection(); var sql = connection.createStatement();
                 var row = sql.executeQuery("SELECT a.author_id,a.series_id,a.series_order,s.title,u.name FROM artworks a "
                         + "LEFT JOIN manga_series s ON a.series_id=s.series_id LEFT JOIN authors u ON a.author_id=u.author_id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(42);
                assertThat(row.getLong(2)).isEqualTo(7);
                assertThat(row.getObject(3)).isEqualTo(navigation.contains("order") ? 3 : null);
                assertThat(row.getString(4)).isEqualTo(navigation.contains("title") ? "local-series" : null);
                assertThat(row.getString(5)).isNull();
            }
            return retryNetwork && requests.incrementAndGet() == 1
                    ? ArtworksBackFillPixivClient.LookupResult.skip("fixture") : network;
        });
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 0, false, target)
                    .withSources(directory.toString(), false);
            var summary = ArtworksBackFill.run(options);
            assertThat(summary.filledSeries()).isEqualTo(1);
            assertThat(summary.filledAuthor()).isEqualTo(1);
            if (retryNetwork) {
                var retry = ArtworksBackFill.run(options);
                assertThat(retry.filledSeries()).isEqualTo(1);
                assertThat(retry.filledAuthor()).isEqualTo(1);
            }
            assertThat(ArtworksBackFill.run(options).totalCandidates()).isZero();
            verify(client, times(retryNetwork ? 2 : 1)).query(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT a.series_order,s.title,u.name FROM artworks a JOIN manga_series s "
                     + "ON a.series_id=s.series_id JOIN authors u ON a.author_id=u.author_id")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(navigation.contains("order") ? 3 : 8);
            assertThat(row.getString(2)).isEqualTo(navigation.contains("title") ? "local-series" : "network-series");
            assertThat(row.getString(3)).isEqualTo("network-author");
        }
        assertThat(directory.resolve("1.meta.json")).exists();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> partialSeriesCases() {
        return java.util.stream.Stream.of("{\"seriesId\":7}", "{\"seriesId\":7,\"title\":\"local-series\"}",
                        "{\"seriesId\":7,\"order\":3}")
                .flatMap(navigation -> java.util.stream.Stream.of(false, true)
                        .map(retry -> org.junit.jupiter.params.provider.Arguments.of(navigation, retry)));
    }
    private static final String BODY = """
            {"id":"1","title":"作品","userId":"42","userName":"作者","xRestrict":0,"aiType":1,
             "description":"local","tags":{"tags":[{"tag":"local-tag"}]},"seriesNavData":null,
             "uploadDate":"2020-02-03T12:00:00+09:00"}
            """;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("先导入旧文件，再按来源选择补缺，强制联网也保留已有字段")
    void selectsSourceWithoutOverwriting(boolean alwaysNetwork) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("works.db"));
        initialize(target);
        var snapshot = new WorkMetaCurator(mapper).curateArtwork(1, mapper.readTree(BODY), null, "forward").document();
        Files.writeString(directory.resolve("1.meta.json"), snapshot.toString());
        Files.writeString(directory.resolve("3.meta.json"), new WorkMetaCurator(mapper).curateNovel(3,
                mapper.readTree("{\"id\":3,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward").document().toString());
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
        }
        var network = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("local", "network")), 4567L, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenReturn(network);
        when(client.queryNovel(3)).thenReturn(network);
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 0, false, target)
                    .withSources(directory.toString(), alwaysNetwork);
            var summary = ArtworksBackFill.run(options);
            assertThat(summary.filledUploadTime()).isEqualTo(2);
            assertThat(summary.processed()).isEqualTo(2);
            verify(client, times(alwaysNetwork ? 1 : 0)).query(1);
            verify(client, times(alwaysNetwork ? 1 : 0)).queryNovel(3);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT r18,author_id,description,upload_time,metadata_json FROM artworks")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isEqualTo(1);
            assertThat(row.getLong(2)).isEqualTo(7);
            assertThat(row.getString(3)).isEqualTo(alwaysNetwork ? "network" : "local");
            assertThat(row.getLong(4)).isEqualTo(alwaysNetwork ? 4567L : 1580698800000L);
            assertThat(mapper.readTree(row.getString(5)).path("source").asText()).isEqualTo("forward");
        }
        assertThat(directory.resolve("1.meta.json")).exists();
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT upload_time FROM novels WHERE novel_id=3")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(alwaysNetwork ? 4567L : 1580698800000L);
        }
        var imported = top.sywyar.pixivdownload.migration.JsonToSqliteMigration.run(
                new top.sywyar.pixivdownload.migration.JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target), null);
        assertThat(imported.migrated()).isZero();
        assertThat(imported.failed()).isZero();
        assertThat(directory.resolve("3.meta.json")).exists();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("试运行可使用旧文件但不写入快照或字段，也不联网")
    void dryRunUsesSidecarWithoutPersisting(boolean previouslyUnreachable) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("dry.db"));
        initialize(target);
        Files.writeString(directory.resolve("1.meta.json"), new WorkMetaCurator(mapper)
                .curateArtwork(1, mapper.readTree(BODY), null, "forward").document().toString());
        if (previouslyUnreachable) {
            var unreachable = ArtworksBackFillUnreachableStore.load(directory.resolve("unreachable.json"), mapper);
            unreachable.record(1, "404");
            unreachable.save();
        }
        var client = mock(ArtworksBackFillPixivClient.class);
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var summary = ArtworksBackFill.run(new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 0, true, target)
                    .withSources(directory.toString(), false));
            assertThat(summary.filledUploadTime()).isEqualTo(1);
            verify(client, never()).query(anyLong());
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT description,upload_time,metadata_json FROM artworks")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isNull();
            assertThat(row.getObject(2)).isNull();
            assertThat(row.getString(3)).isNull();
        }
    }

    @Test
    @DisplayName("本地只补已有内容，网络补剩余字段且不覆盖本地补入的值")
    void partialMetadataFallsBackToNetwork() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("partial.db"));
        initialize(target);
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE artworks SET author_id = NULL");
            sql.executeUpdate("INSERT INTO authors(author_id,name,updated_time) VALUES(42,'newer-author',1)");
            sql.executeUpdate("INSERT INTO manga_series(series_id,title,author_id,updated_time) VALUES(7,'newer-series',99,1)");
        }
        var raw = mapper.readTree("{\"id\":\"1\",\"description\":\"local\"}");
        Files.writeString(directory.resolve("1.meta.json"), new WorkMetaCurator(mapper)
                .curateArtwork(1, raw, null, "forward").document().toString());
        var result = ArtworksBackFillPixivClient.parsePayload(mapper.readTree(BODY.replace("local", "network")
                .replace("\"seriesNavData\":null", "\"seriesNavData\":{\"seriesId\":7,\"title\":\"older-series\",\"order\":1}")), 4567L, true);
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.query(1)).thenReturn(result);
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                invocation -> invocation.getMethod().getName().equals("open") ? client : invocation.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var summary = ArtworksBackFill.run(new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, 0, false, target)
                    .withSources(directory.toString(), false));
            assertThat(summary.filledDescription()).isEqualTo(1);
            assertThat(summary.filledUploadTime()).isEqualTo(1);
            assertThat(summary.skipped()).isZero();
            verify(client).query(1);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT description, upload_time FROM artworks")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("local");
            assertThat(row.getLong(2)).isEqualTo(4567L);
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT a.name,s.title,s.author_id FROM authors a,manga_series s WHERE a.author_id=42 AND s.series_id=7")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("newer-author");
            assertThat(row.getString(2)).isEqualTo("newer-series");
            assertThat(row.getLong(3)).isEqualTo(99);
        }
    }

    private void initialize(top.sywyar.pixivdownload.core.db.DatabaseTarget target) throws Exception {
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.executeUpdate("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,author_id,r18) "
                    + "VALUES(1,'existing','{0}',1,'jpg',1,7,1)");
            sql.executeUpdate("INSERT INTO authors(author_id,name,updated_time) VALUES(7,'existing-author',1)");
        }
    }
}
