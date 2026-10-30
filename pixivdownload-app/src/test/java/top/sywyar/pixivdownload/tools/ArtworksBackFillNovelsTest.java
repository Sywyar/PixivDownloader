package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("小说上传时间回填")
class ArtworksBackFillNovelsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final long LOCAL_TIME = 1580698800000L;
    private static final long NETWORK_TIME = 1612321200000L;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("多条小说本地快照按候选上限补齐，预演与执行的进度和计数一致")
    void localProjectionRespectsLimit(boolean sidecar) throws Exception {
        var target = initialize();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(4,'four','{0}',1,'txt',4),(5,'five','{0}',1,'txt',5)");
        }
        for (long id = 3; id <= 5; id++) {
            String snapshot = new WorkMetaCurator(mapper).curateNovel(id,
                    mapper.readTree("{\"id\":" + id + ",\"uploadTimestamp\":1580698800}"), "forward").document().toString();
            if (sidecar) Files.writeString(directory.resolve(id + ".meta.json"), snapshot);
            else try (var connection = target.openConnection(); var sql = connection.prepareStatement("UPDATE novels SET metadata_json=? WHERE novel_id=?")) {
                sql.setString(1, snapshot); sql.setLong(2, id); sql.executeUpdate();
            }
        }
        var client = mock(ArtworksBackFillPixivClient.class);
        var source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenAnswer(ignored -> target.openConnection());
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var dryProgress = new java.util.ArrayList<String>();
            var actualProgress = new java.util.ArrayList<String>();
            assertThat(ArtworksBackFill.countCandidates(options(target, false, true, 1))).isEqualTo(1);
            var preview = ArtworksBackFill.run(options(target, false, true, 1), source,
                    (done, total) -> dryProgress.add(done + "/" + total));
            assertThat(preview.processed()).isEqualTo(1);
            assertThat(preview.filledUploadTime()).isEqualTo(1);
            for (long id = 3; id <= 5; id++) assertThat(uploadTime(target, id)).isNull();
            var actual = ArtworksBackFill.run(options(target, false, false, 1), source,
                    (done, total) -> actualProgress.add(done + "/" + total));
            assertThat(actual.processed()).isEqualTo(1);
            assertThat(actual.totalCandidates()).isEqualTo(1);
            assertThat(actual.filledUploadTime()).isEqualTo(1);
            assertThat(actualProgress).isEqualTo(dryProgress).contains("0/1", "1/1");
            assertThat(uploadTime(target, 3)).isEqualTo(LOCAL_TIME);
            assertThat(uploadTime(target, 4)).isNull();
            assertThat(uploadTime(target, 5)).isNull();
            assertThat(ArtworksBackFill.countCandidates(options(target, false, false, 0))).isEqualTo(2);
            verify(client, never()).queryNovel(anyLong());
        }
    }

    @ParameterizedTest
    @CsvSource({"network,false", "network,true", "snapshot,false", "sidecar,false"})
    @DisplayName("受限小说只暂缓自动请求，手动重试或新增本地资料可恢复")
    void restrictedNovelDoesNotRepeatAtStartup(String recovery, boolean alwaysNetwork) throws Exception {
        var target = initialize();
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenReturn(ArtworksBackFillPixivClient.LookupResult.r18Only("restricted"));
        var path = directory.resolve("unreachable.json");
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(path);
            var manual = options(target, alwaysNetwork, false, 1);
            var automatic = manual.forStartup();
            assertThat(ArtworksBackFill.run(automatic).skipped()).isEqualTo(1);
            assertThat(ArtworksBackFill.countCandidates(automatic)).isZero();
            assertThat(ArtworksBackFill.run(automatic).processed()).isZero();
            verify(client, times(1)).queryNovel(3);
            var stored = ArtworksBackFillUnreachableStore.load(path, mapper);
            assertThat(stored.contains(WorkType.NOVEL, 3)).isFalse();
            assertThat(stored.blocksNetwork(WorkType.NOVEL, 3, true)).isTrue();
            assertThat(ArtworksBackFill.countCandidates(manual)).isEqualTo(1);
            if (recovery.equals("network")) {
                var recovered = networkResult();
                when(client.queryNovel(3)).thenReturn(recovered);
                assertThat(ArtworksBackFill.run(manual).filledUploadTime()).isEqualTo(1);
                assertThat(uploadTime(target, 3)).isEqualTo(NETWORK_TIME);
                verify(client, times(2)).queryNovel(3);
            } else {
                String snapshot = new WorkMetaCurator(mapper).curateNovel(3,
                        mapper.readTree("{\"id\":3,\"uploadTimestamp\":1580698800}"), "forward").document().toString();
                if (recovery.equals("sidecar")) Files.writeString(directory.resolve("3.meta.json"), snapshot);
                else try (var connection = target.openConnection(); var sql = connection.prepareStatement("UPDATE novels SET metadata_json=?")) {
                    sql.setString(1, snapshot); sql.executeUpdate();
                }
                assertThat(ArtworksBackFill.countCandidates(automatic)).isEqualTo(1);
                assertThat(ArtworksBackFill.run(automatic).filledUploadTime()).isEqualTo(1);
                assertThat(uploadTime(target, 3)).isEqualTo(LOCAL_TIME);
                verify(client, times(1)).queryNovel(3);
            }
            assertThat(ArtworksBackFillUnreachableStore.load(path, mapper).blocksNetwork(WorkType.NOVEL, 3, true)).isFalse();
            try (var connection = target.openConnection(); var sql = connection.createStatement();
                 var row = sql.executeQuery("SELECT deleted FROM novels WHERE novel_id=3")) {
                assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("预演遇到受限小说不写暂缓记录")
    void restrictedDryRunDoesNotPersist() throws Exception {
        var target = initialize();
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenReturn(ArtworksBackFillPixivClient.LookupResult.r18Only("restricted"));
        var path = directory.resolve("unreachable.json");
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(path);
            var options = options(target, false, true, 1).forStartup();
            assertThat(ArtworksBackFill.run(options).skipped()).isEqualTo(1);
            assertThat(Files.exists(path)).isFalse();
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("网络返回前小说已补齐或软删除时，本次上传时间计数为零")
    void concurrentNovelWriteIsNotCounted(boolean deleted) throws Exception {
        var target = initialize();
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenAnswer(ignored -> {
            try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                sql.execute(deleted ? "UPDATE novels SET deleted=1 WHERE novel_id=3" : "UPDATE novels SET upload_time=1234 WHERE novel_id=3");
            }
            return networkResult();
        });
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var summary = ArtworksBackFill.run(options(target, true, false, 1));
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.skipped()).isEqualTo(1);
            assertThat(summary.filledUploadTime()).isZero();
            assertThat(uploadTime(target, 3)).isEqualTo(deleted ? null : 1234L);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
            "false,true,true", "true,true,true", "false,false,true", "true,false,true"})
    @DisplayName("小说本地优先、强制联网和预演共用来源语义且保留快照与原文件")
    void sourcesAndDryRun(boolean sidecar, boolean alwaysNetwork, boolean dryRun) throws Exception {
        var target = initialize();
        String snapshot = new WorkMetaCurator(mapper).curateNovel(3,
                mapper.readTree("{\"id\":3,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}"), "forward")
                .document().toString();
        Path file = directory.resolve("3.meta.json");
        if (sidecar) Files.writeString(file, snapshot);
        else try (var connection = target.openConnection(); var update = connection.prepareStatement("UPDATE novels SET metadata_json=?")) {
            update.setString(1, snapshot);
            update.executeUpdate();
        }
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenReturn(networkResult());
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(directory.resolve("unreachable.json"));
            var options = options(target, alwaysNetwork, dryRun, 0);
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
            var summary = ArtworksBackFill.run(options);
            if (alwaysNetwork) {
                verify(client).queryNovel(3);
                assertThat(summary.filledUploadTime()).isEqualTo(1);
            } else verify(client, never()).queryNovel(anyLong());
            verify(client, never()).query(anyLong());
            assertThat(uploadTime(target, 3)).isEqualTo(dryRun ? null : alwaysNetwork ? NETWORK_TIME : LOCAL_TIME);
            if (!dryRun) {
                assertThat(ArtworksBackFill.countCandidates(options)).isZero();
                assertThat(ArtworksBackFill.run(options).processed()).isZero();
                verify(client, times(alwaysNetwork ? 1 : 0)).queryNovel(anyLong());
            }
        }
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT metadata_json FROM novels WHERE novel_id=3")) {
            assertThat(row.next()).isTrue();
            if (sidecar && dryRun) assertThat(row.getString(1)).isNull();
            else assertThat(mapper.readTree(row.getString(1))).isEqualTo(mapper.readTree(snapshot));
        }
        if (sidecar) assertThat(Files.readString(file)).isEqualTo(snapshot);
        assertThat(Files.exists(directory.resolve("unreachable.json"))).isFalse();
    }

    @Test
    @DisplayName("无本地资料的小说可联网补齐，同数字插画不可达缓存不误伤小说")
    void networkFallbackAndTypedUnreachable() throws Exception {
        var target = initialize();
        var path = directory.resolve("unreachable.json");
        var unreachable = ArtworksBackFillUnreachableStore.load(path, mapper);
        unreachable.record(3, "artwork gone");
        unreachable.record(WorkType.NOVEL, 2, "novel gone");
        unreachable.save();
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(2,'gone','{0}',1,'txt',2)");
        }
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenReturn(networkResult());
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(path);
            var options = options(target, false, false, 1);
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
            var summary = ArtworksBackFill.run(options);
            assertThat(summary.processed()).isEqualTo(1);
            assertThat(summary.filledUploadTime()).isEqualTo(1);
            assertThat(summary.previouslyUnreachable()).isEqualTo(1);
            assertThat(uploadTime(target, 3)).isEqualTo(NETWORK_TIME);
            assertThat(uploadTime(target, 2)).isNull();
            assertThat(ArtworksBackFill.run(options).processed()).isZero();
            verify(client, times(1)).queryNovel(3);
            verify(client, never()).queryNovel(2);
        }
    }

    @Test
    @DisplayName("网络确认小说不可达后持久化类型身份，后续仅用本地资料恢复")
    void remembersUnavailableNovelAndAllowsLocalRecovery() throws Exception {
        var target = initialize();
        Path unreachablePath = directory.resolve("unreachable.json");
        var client = mock(ArtworksBackFillPixivClient.class);
        when(client.queryNovel(3)).thenReturn(ArtworksBackFillPixivClient.LookupResult.deleted("HTTP 404"));
        try (var files = mockStatic(RuntimeFiles.class); var clients = mockStatic(ArtworksBackFillPixivClient.class,
                call -> call.getMethod().getName().equals("open") ? client : call.callRealMethod())) {
            files.when(RuntimeFiles::resolveBackfillUnreachablePath).thenReturn(unreachablePath);
            var options = options(target, false, false, 1);
            assertThat(ArtworksBackFill.run(options).newlyUnreachable()).isEqualTo(1);
            var stored = ArtworksBackFillUnreachableStore.load(unreachablePath, mapper);
            assertThat(stored.contains(WorkType.NOVEL, 3)).isTrue();
            assertThat(stored.contains(3)).isFalse();
            assertThat(ArtworksBackFill.countCandidates(options)).isZero();
            assertThat(ArtworksBackFill.run(options).processed()).isZero();
            String snapshot = new WorkMetaCurator(mapper).curateNovel(3,
                    mapper.readTree("{\"id\":3,\"uploadTimestamp\":1580698800}"), "forward").document().toString();
            Files.writeString(directory.resolve("3.meta.json"), snapshot);
            assertThat(ArtworksBackFill.countCandidates(options)).isEqualTo(1);
            ArtworksBackFill.run(options);
            assertThat(uploadTime(target, 3)).isEqualTo(LOCAL_TIME);
            verify(client, times(1)).queryNovel(3);
            assertThat(Files.readString(directory.resolve("3.meta.json"))).isEqualTo(snapshot);
        }
    }

    @Test
    @DisplayName("小说上传时间保留并发写入值")
    void keepsConcurrentUploadTime() throws Exception {
        var target = initialize();
        try (var database = ArtworksBackFillDatabase.open(target)) {
            var candidate = database.currentCandidate(WorkType.NOVEL, 3);
            try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                sql.execute("UPDATE novels SET upload_time=1234 WHERE novel_id=3");
            }
            database.applyUpdates(candidate, networkResult(), false, false, false, false, false, false, true);
        }
        assertThat(uploadTime(target, 3)).isEqualTo(1234L);
    }

    private DatabaseTarget initialize() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("novels.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO novels(novel_id,title,folder,count,extensions,time) VALUES(3,'novel','{0}',1,'txt',1)");
        }
        return target;
    }

    private ArtworksBackFill.Options options(DatabaseTarget target, boolean alwaysNetwork, boolean dryRun, int limit) {
        return new ArtworksBackFill.Options(target.displayName(), "", 0, false, 0, limit, dryRun, target)
                .withSources(directory.toString(), alwaysNetwork);
    }

    private ArtworksBackFillPixivClient.LookupResult networkResult() throws Exception {
        return ArtworksBackFillPixivClient.parseResponse(200,
                "{\"body\":{\"id\":3,\"userId\":42,\"uploadDate\":\"2021-02-03T12:00:00+09:00\"}}", mapper, WorkType.NOVEL);
    }

    private Long uploadTime(DatabaseTarget target, long id) throws Exception {
        try (var connection = target.openConnection(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT upload_time FROM novels WHERE novel_id=" + id)) {
            assertThat(row.next()).isTrue();
            long time = row.getLong(1);
            return row.wasNull() ? null : time;
        }
    }
}
