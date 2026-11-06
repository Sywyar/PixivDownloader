package top.sywyar.pixivdownload.tools;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@DisplayName("本地登记作品的标题与元数据快照回填")
class ArtworksBackFillMetadataTest {
    @TempDir Path directory;
    final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @ParameterizedTest @EnumSource(WorkType.class)
    @DisplayName("插画和小说从真实响应补齐公共资料与裁剪快照，重跑不覆盖")
    void fillsMetadataAndKeepsExistingValues(WorkType type) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("works.db"));
        String table = type == WorkType.NOVEL ? "novels" : "artworks";
        String id = type == WorkType.NOVEL ? "novel_id" : "artwork_id";
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            DatabaseInitializer.prepareOffline(connection, target);
            sql.execute("INSERT INTO " + table + "(" + id + ",title,folder,count,extensions,time)"
                    + " VALUES(42,'','{0}',1,'png',1)");
        }
        var result = ArtworksBackFillPixivClient.parseResponse(200, """
                {"body":{"id":"42","title":"Actual title","userId":"7","userName":"Actual author",
                "xRestrict":2,"aiType":2,"description":"Description","seriesNavData":null,
                "uploadDate":"2020-02-03T12:00:00+09:00","isOriginal":true,
                "tags":{"tags":[{"tag":"fixture"}]},"content":"private novel body"}}
                """, mapper, type, 42);
        try (var database = ArtworksBackFillDatabase.open(target)) {
            var candidate = database.currentCandidate(type, 42);
            assertThat(candidate.titleMissing()).isTrue();
            assertThat(candidate.metadataMissing()).isTrue();
            var applied = database.applyUpdates(candidate, result, true, true, true, true, true, true, true);
            assertThat(applied.details()).isTrue();
            assertThat(database.currentCandidate(type, 42).hasMissing()).isFalse();
            try (var connection = target.openConnection(); var sql = connection.createStatement();
                 var row = sql.executeQuery("SELECT title,author_id,r18,is_ai,metadata_json FROM " + table)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("title")).isEqualTo("Actual title");
                assertThat(row.getLong("author_id")).isEqualTo(7);
                assertThat(row.getInt("r18")).isEqualTo(2);
                assertThat(row.getInt("is_ai")).isEqualTo(1);
                var meta = new WorkMetaCurator(mapper).restore(type, 42, mapper.readTree(row.getString("metadata_json")));
                assertThat(meta.document().path("raw").path("title").asText()).isEqualTo("Actual title");
                if (type == WorkType.NOVEL) assertThat(meta.document().toString()).doesNotContain("private novel body");
            }
            try (var connection = target.openConnection(); var sql = connection.createStatement()) {
                sql.execute("UPDATE " + table + " SET title='Keep title',author_id=8");
            }
            assertThat(database.applyUpdates(candidate, result, true, true, true, true, true, true, true).details()).isFalse();
            try (var connection = target.openConnection(); var sql = connection.createStatement();
                 var row = sql.executeQuery("SELECT title,author_id FROM " + table)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("Keep title");
                assertThat(row.getLong(2)).isEqualTo(8);
            }
        }
    }

    @Test @DisplayName("响应身份不符不能产生可写快照")
    void rejectsWrongIdentity() {
        assertThatThrownBy(() -> ArtworksBackFillPixivClient.parseResponse(200,
                "{\"body\":{\"id\":43,\"userId\":7}}", mapper, WorkType.ARTWORK, 42))
                .isInstanceOf(java.io.IOException.class).hasMessage("PIXIV_METADATA_IDENTITY_MISMATCH");
    }

    @Test @DisplayName("本地快照缺失与标题缺失分别计算，不重复使用已耗尽的快照")
    void distinguishesTitleAndSnapshot() throws Exception {
        var local = ArtworksBackFillPixivClient.parsePayload(mapper.readTree("{\"id\":42}"), null, false)
                .withMetadata(new WorkMetaCurator(mapper).curateArtwork(42, mapper.readTree("{\"id\":42}"), null, "forward"));
        var candidate = new ArtworksBackFillDatabase.Candidate(42, false, false, false, false, false, false)
                .withDetails(true, false);
        assertThat(candidate.availableFields(local).hasMissing()).isFalse();
        assertThat(candidate.withDetails(true, true).availableFields(local).titleMissing()).isFalse();
        assertThat(candidate.withDetails(true, true).availableFields(local).metadataMissing()).isTrue();
    }
}
