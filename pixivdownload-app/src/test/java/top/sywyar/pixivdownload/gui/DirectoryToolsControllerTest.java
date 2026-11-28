package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMigrationService;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixToolsController;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DirectoryToolsControllerTest {
    @TempDir Path directory;
    private final SQLiteDataSource dataSource = new SQLiteDataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final JsonMapper json = JsonMapper.shared();
    private MockMvc mvc;
    private PathPrefixCodec codec;

    @BeforeEach void setUp() throws Exception {
        var database = directory.resolve("artworks.db");
        dataSource.setUrl("jdbc:sqlite:" + database);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artwork_image_hashes(artwork_id INTEGER, page INTEGER)");
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT, folder TEXT, moved INTEGER, move_folder TEXT, deleted INTEGER, time INTEGER)");
            statement.execute("INSERT INTO artworks VALUES (1, 'one', 'missing-one', 0, NULL, 0, 1), (2, 'two', 'missing-two', 0, NULL, 0, 1)");
        }
        var download = new DownloadConfig();
        download.setRootFolder(directory.resolve("{artwork_id}").toString());
        var messages = TestI18nBeans.appMessages();
        var requests = new ToolApiRequests(messages, mock(WebI18nService.class));
        var main = DatabaseProviderTestFixtures.main(dataSource);
        var mapper = new PathPrefixMapper(main);
        codec = new PathPrefixCodec(mapper, download, messages);
        codec.init();
        var owners = new top.sywyar.pixivdownload.core.asset.DownloadDirectories(main, codec, null);
        var service = new PathPrefixMigrationService(codec, mapper, DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns(),
                download, messages, new DataSourceTransactionManager(dataSource), main, owners);
        mvc = MockMvcBuilders.standaloneSetup(new DirectoryToolsController(dataSource,
                DatabaseProviderTestFixtures.sqlite(database), download, requests, messages, owners),
                new PathPrefixToolsController(service, requests, messages)).build();
    }

    @Test void importedPageReferencesKeepTheirHashButStaleReferencesDoNotProtectManagedHashes() throws Exception {
        jdbc.execute("CREATE TABLE external_work_files(work_type TEXT,work_id INTEGER,record_time INTEGER)");
        jdbc.update("INSERT INTO external_work_files VALUES('ARTWORK',1,1),('ARTWORK',2,77)");
        jdbc.update("INSERT INTO artwork_image_hashes VALUES(1,0),(2,0)");
        for (long id : List.of(1L, 2L)) {
            mvc.perform(post("/api/tools/directories").contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("artworkId", id, "moved", false, "path", directory.toString()))))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForList("SELECT artwork_id FROM artwork_image_hashes", Integer.class)).containsExactly(1);
    }

    @Test void rollsBackThePathWhenHashInvalidationFails() throws Exception {
        jdbc.update("INSERT INTO artwork_image_hashes VALUES(1,-1)");
        jdbc.execute("CREATE TRIGGER refuse_hash_delete BEFORE DELETE ON artwork_image_hashes BEGIN SELECT RAISE(ABORT, 'test'); END");
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("artworkId", 1, "moved", false, "path", directory.toString()))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DIRECTORY_DATABASE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT folder FROM artworks WHERE artwork_id=1", String.class)).isEqualTo("missing-one");
        assertThat(jdbc.queryForObject("SELECT page FROM artwork_image_hashes WHERE artwork_id=1", Integer.class)).isEqualTo(-1);
    }

    @Test void pagesMissingDirectoriesAndRepairsCurrentDatabaseWithoutMovingFiles() throws Exception {
        mvc.perform(get("/api/tools/directories").param("offset", "1").param("limit", "1"))
                .andDo(result -> { if (result.getResolvedException() != null) throw new AssertionError(result.getResolvedException()); })
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.missing").value(2)).andExpect(jsonPath("$.inaccessible.length()").value(1))
                .andExpect(jsonPath("$.inaccessible[0].artworkId").value(1));
        mvc.perform(get("/api/tools/directories").param("limit", "1001")).andExpect(status().isBadRequest());
        String path = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(directory.toString());
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                .content("{\"artworkId\":1,\"moved\":false,\"path\":" + path + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(1)).andExpect(jsonPath("$.inaccessible[0].artworkId").value(2));
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                .content("{\"artworkId\":999,\"moved\":false,\"path\":" + path + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTWORK_NOT_FOUND"));
        try (var connection = dataSource.getConnection(); var setup = connection.prepareStatement(
                "UPDATE artworks SET moved=1, move_folder=? WHERE artwork_id=2")) {
            setup.setString(1, directory.toString());
            setup.executeUpdate();
            for (boolean moved : new boolean[]{false, true}) {
                int artworkId = moved ? 2 : 1;
                mvc.perform(post("/api/tools/directories").contentType("application/json")
                        .content("{\"artworkId\":" + artworkId + ",\"moved\":" + moved + ",\"path\":123}"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST"))
                        .andExpect(jsonPath("$.field").value("/path"));
                try (var query = connection.createStatement(); var rows = query.executeQuery(
                        "SELECT folder, move_folder FROM artworks WHERE artwork_id=" + artworkId)) {
                    org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rows.getString(moved ? "move_folder" : "folder")).isEqualTo(directory.toString());
                }
            }
        }
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk()).andExpect(jsonPath("$.missing").value(0));
    }

    @Test void repairThroughParentDoesNotBindToSiblingPrefix() throws Exception {
        Path a = Files.createDirectory(directory.resolve("A"));
        Path b = Files.createDirectory(directory.resolve("B"));
        Path work = Files.createDirectory(b.resolve("work"));
        Path viaA = a.resolve("../B/work");
        assertThat(Files.isSameFile(viaA, work)).isTrue();
        register(a, b);
        repair(1, viaA);
        repair(2, work);
        assertThat(storedFolder(1)).isEqualTo(viaA.toString());
        assertThat(storedFolder(2)).isEqualTo("{" + prefixId(b) + "}/work");
        Path newA = Files.createDirectories(directory.resolve("other/newA"));
        mvc.perform(post("/api/tools/path-prefixes").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("updates", List.of(Map.of("id", prefixId(a), "path", newA.toString()))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
        for (int id : List.of(1, 2)) {
            assertThat(Files.isSameFile(Path.of(codec.resolve(storedFolder(id))), work)).isTrue();
        }
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.missing").value(0));
    }

    @Test void newAndStoredReferencesPreserveUnicodeLineSeparators() throws Exception {
        Path b = Files.createDirectory(directory.resolve("B"));
        Path fresh = Files.createDirectory(b.resolve("work\u2028part"));
        Path existing = Files.createDirectory(b.resolve("saved\u2028part"));
        register(b);
        String token = "{" + prefixId(b) + "}/";
        jdbc.update("UPDATE artworks SET folder=? WHERE artwork_id=2", token + existing.getFileName());
        repair(1, fresh);
        assertThat(storedFolder(1)).isEqualTo(token + fresh.getFileName());
        assertThat(storedFolder(2)).isEqualTo(token + existing.getFileName());
        assertThat(codec.looksEncoded(storedFolder(2))).isTrue();
        assertThat(Files.isSameFile(Path.of(codec.resolve(storedFolder(1))), fresh)).isTrue();
        assertThat(Files.isSameFile(Path.of(codec.resolve(storedFolder(2))), existing)).isTrue();
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.missing").value(0));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void repairsColumnUsedByGalleryEvenWhenMoveFlagDisagrees(boolean useMoveFolder) throws Exception {
        jdbc.update("DELETE FROM artworks WHERE artwork_id=2");
        Path original = Files.createDirectory(directory.resolve("original"));
        Path selected = Files.createDirectory(directory.resolve("selected"));
        jdbc.update("UPDATE artworks SET folder=?, moved=?, move_folder=' ' WHERE artwork_id=1",
                original.toString(), !useMoveFolder);
        repair(1, useMoveFolder, selected);
        assertThat(Files.isSameFile(galleryFolder(), selected)).isTrue();
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk()).andExpect(jsonPath("$.missing").value(0));

        Path repaired = Files.move(selected, directory.resolve("repaired"));
        assertThat(galleryFolder()).isEqualTo(selected);
        assertThat(galleryFolder()).doesNotExist();
        var response = mvc.perform(get("/api/tools/directories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(1)).andExpect(jsonPath("$.inaccessible[0].path").value(selected.toString()))
                .andExpect(jsonPath("$.inaccessible[0].moved").value(useMoveFolder)).andReturn().getResponse();
        var item = json.readTree(response.getContentAsByteArray()).required("inaccessible").get(0);
        repair(item.required("artworkId").asInt(), item.required("moved").asBoolean(), repaired);
        assertThat(Files.isSameFile(galleryFolder(), repaired)).isTrue();
        assertThat(storedFolder(1)).isEqualTo((useMoveFolder ? original : repaired).toString());
        assertThat(jdbc.queryForObject("SELECT move_folder FROM artworks WHERE artwork_id=1", String.class))
                .isEqualTo(useMoveFolder ? repaired.toString() : " ");
        assertThat(jdbc.queryForObject("SELECT moved FROM artworks WHERE artwork_id=1", Boolean.class)).isEqualTo(!useMoveFolder);
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk()).andExpect(jsonPath("$.missing").value(0));
    }

    private Path galleryFolder() {
        var record = jdbc.queryForObject("SELECT folder, moved, move_folder FROM artworks WHERE artwork_id=1",
                (row, index) -> new ArtworkRecord(1, "one", row.getString(1), 1, "jpg", 1L,
                        row.getBoolean(2), row.getString(3), null, 0, false, null, null));
        var locator = new ArtworkFileLocator(null, null, null, null, null, null, null);
        return Path.of(locator.resolveArtworkDirectory(record));
    }

    private void register(Path... paths) throws Exception {
        jdbc.execute("CREATE TABLE path_prefixes (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE)");
        var directories = java.util.Arrays.stream(paths).map(Path::toString).toList();
        mvc.perform(post("/api/tools/path-prefixes").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("registerPaths", directories))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(paths.length));
    }

    private long prefixId(Path directory) {
        return jdbc.queryForObject("SELECT id FROM path_prefixes WHERE path=?", Long.class, directory.toString());
    }

    private void repair(int artworkId, Path path) throws Exception {
        repair(artworkId, false, path);
    }

    private void repair(int artworkId, boolean moved, Path path) throws Exception {
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                .content(json.writeValueAsBytes(new DirectoryToolsController.Update(artworkId, moved, path.toString()))))
                .andExpect(status().isNoContent());
    }

    private String storedFolder(int artworkId) {
        return jdbc.queryForObject("SELECT folder FROM artworks WHERE artwork_id=?", String.class, artworkId);
    }
}
