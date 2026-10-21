package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.asset.FileOperationJournal;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("收藏归属 Service 的真实数据库回归")
class CollectionMembershipsIntegrationTest {
    @TempDir Path directory;

    @Test
    @DisplayName("SQLite 收藏归属保留作品和收藏夹身份")
    void sqliteMemberships() throws Exception {
        try (var pool = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openPool(directory.resolve("collections.db"),
                DatabasePoolProperties.MINIMUM_POOL_SIZE)) {
            verifyMemberships(pool);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("PostgreSQL 收藏归属保留作品和收藏夹身份")
    void postgresMemberships() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            verifyMemberships(database.pool());
        }
    }

    @Test
    @DisplayName("SQLite 收藏夹与图标变更失败保留原文件和关联，成功提交完整结果")
    void sqliteMutations() throws Exception {
        try (var pool = DatabaseProviderTestFixtures.openPool(directory.resolve("mutations.db"),
                DatabasePoolProperties.MINIMUM_POOL_SIZE)) {
            verifyMutationFailures(pool, false);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("PostgreSQL 收藏夹与图标变更失败保留原文件和关联，成功提交完整结果")
    void postgresMutations() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            verifyMutationFailures(database.pool(), true);
        }
    }

    private void verifyMutationFailures(DataSource pool, boolean postgres) throws Exception {
        String previous = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        try {
            var jdbc = new JdbcTemplate(pool);
            var initializer = initialize(pool);
            var main = DatabaseProviderTestFixtures.main(pool);
            var mapper = new CollectionMapper(main);
            var config = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig();
            config.setRootFolder(directory.resolve("downloads").toString());
            var messages = TestI18nBeans.appMessages();
            var paths = new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec(
                    new top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper(main), config, messages);
            paths.init();
            var manager = new top.sywyar.pixivdownload.core.db.DatabaseConfig(null, null)
                    .transactionManager(pool, DatabaseProviderTestFixtures.target(pool));
            var journal = new FileOperationJournal(main, manager, paths, initializer);
            var icons = new CollectionIconService(messages, new StagedFileDeletion(messages, journal));
            icons.init();
            var service = new CollectionService(mapper, icons, messages, config, null, paths, initializer);
            jdbc.update("INSERT INTO collections(id,name,icon_ext,created_time) VALUES(1,'first','jpg',1),(2,'other',NULL,2)");
            mapper.addArtwork(1, 11, 1);
            mapper.addArtwork(2, 11, 2);
            jdbc.update("INSERT INTO novel_collections(collection_id,novel_id,added_time) VALUES(1,12,1),(2,12,2)");
            Path old = Files.write(icons.resolveIconPath(1, "jpg"), imageBytes("jpg", 4));
            byte[] original = Files.readAllBytes(old);
            Path replacement = icons.resolveIconPath(1, "png");
            byte[] upload = imageBytes("png", 5);
            rejectMutation(jdbc, postgres, "DELETE");

            assertThatThrownBy(() -> service.delete(1)).isInstanceOf(RuntimeException.class);
            assertThat(mapper.countById(1)).isEqualTo(1);
            assertThat(mapper.findCollectionIdsByArtwork(11)).containsExactlyInAnyOrder(1L, 2L);
            assertThat(jdbc.queryForList("SELECT collection_id FROM novel_collections WHERE novel_id=12", Long.class))
                    .containsExactlyInAnyOrder(1L, 2L);
            assertThat(Files.readAllBytes(old)).isEqualTo(original);
            clearRejection(jdbc, postgres);
            rejectMutation(jdbc, postgres, "UPDATE");

            assertThatThrownBy(() -> service.setIcon(1, upload)).isInstanceOf(RuntimeException.class);
            assertThat(mapper.findById(1).iconExt()).isEqualTo("jpg");
            assertThat(Files.readAllBytes(old)).isEqualTo(original);
            assertThat(replacement).doesNotExist();
            assertThatThrownBy(() -> service.clearIcon(1)).isInstanceOf(RuntimeException.class);
            assertThat(mapper.findById(1).iconExt()).isEqualTo("jpg");
            assertThat(Files.readAllBytes(old)).isEqualTo(original);
            clearRejection(jdbc, postgres);

            Files.createDirectory(replacement);
            Path blocker = Files.writeString(replacement.resolve("keep"), "keep", java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(() -> service.setIcon(1, upload)).isInstanceOf(Exception.class);
            assertThat(Files.readAllBytes(old)).isEqualTo(original);
            assertThat(mapper.findById(1).iconExt()).isEqualTo("jpg");
            assertThat(blocker).exists();
            Files.delete(blocker);
            Files.delete(replacement);

            assertThat(service.setIcon(1, upload).iconExt()).isEqualTo("png");
            assertThat(old).doesNotExist();
            byte[] committed = Files.readAllBytes(replacement);
            rejectMutation(jdbc, postgres, "UPDATE");
            assertThatThrownBy(() -> service.setIcon(1, imageBytes("png", 7))).isInstanceOf(RuntimeException.class);
            assertThat(Files.readAllBytes(replacement)).isEqualTo(committed);
            assertThat(mapper.findById(1).iconExt()).isEqualTo("png");
            clearRejection(jdbc, postgres);
            assertThat(service.clearIcon(1).iconExt()).isNull();
            assertThat(replacement).doesNotExist();
            service.setIcon(1, upload);
            service.delete(1);
            assertThat(mapper.countById(1)).isZero();
            assertThat(mapper.countById(2)).isEqualTo(1);
            assertThat(mapper.findCollectionIdsByArtwork(11)).containsExactly(2L);
            assertThat(jdbc.queryForList("SELECT collection_id FROM novel_collections WHERE novel_id=12", Long.class))
                    .containsExactly(2L);
            assertThat(replacement).doesNotExist();
            assertThat(journal.operations()).isEmpty();
            try (var files = Files.list(RuntimeFiles.collectionIconsDirectory())) {
                assertThat(files.toList()).isEmpty();
            }
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previous);
        }
    }

    private static byte[] imageBytes(String format, int size) throws Exception {
        var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        assertThat(javax.imageio.ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private static void rejectMutation(JdbcTemplate jdbc, boolean postgres, String event) {
        if (postgres) {
            jdbc.execute("CREATE FUNCTION reject_collection_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN RAISE EXCEPTION 'injected collection rejection'; END $$");
            jdbc.execute("CREATE TRIGGER reject_collection_mutation BEFORE " + event
                    + " ON collections FOR EACH ROW EXECUTE FUNCTION reject_collection_mutation()");
        } else {
            jdbc.execute("CREATE TRIGGER reject_collection_mutation BEFORE " + event
                    + " ON collections BEGIN SELECT RAISE(ABORT,'injected collection rejection'); END");
        }
    }

    private static void clearRejection(JdbcTemplate jdbc, boolean postgres) {
        jdbc.execute("DROP TRIGGER reject_collection_mutation" + (postgres ? " ON collections" : ""));
        if (postgres) jdbc.execute("DROP FUNCTION reject_collection_mutation()");
    }

    private top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer initialize(DataSource pool) throws Exception {
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initializer = DatabaseProviderTestFixtures.initializer(new JdbcTemplate(pool), registry.contributions(),
                registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        return initializer;
    }

    private void verifyMemberships(DataSource pool) throws Exception {
        var jdbc = new JdbcTemplate(pool);
        var initializer = initialize(pool);
        var mapper = new CollectionMapper(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(pool));
        var service = new CollectionService(mapper, null, TestI18nBeans.appMessages(), null, null, null, initializer);
        jdbc.update("INSERT INTO collections(id,name,created_time) VALUES(?,?,?)",
                5_000_000_001L, "收藏一 🌸", 1_700_000_000_123L);
        jdbc.update("INSERT INTO collections(id,name,created_time) VALUES(?,?,?)",
                5_000_000_002L, "收藏二", 1_700_000_000_124L);
        mapper.addArtwork(5_000_000_001L, 6_000_000_001L, 1_700_000_000_125L);
        mapper.addArtwork(5_000_000_002L, 6_000_000_001L, 1_700_000_000_126L);
        mapper.addArtwork(5_000_000_002L, 6_000_000_002L, 1_700_000_000_127L);

        var memberships = service.membershipsOf(List.of(6_000_000_001L, 6_000_000_002L, 6_000_000_003L));
        assertThat(memberships).containsOnlyKeys(6_000_000_001L, 6_000_000_002L);
        assertThat(memberships.get(6_000_000_001L)).containsExactlyInAnyOrder(5_000_000_001L, 5_000_000_002L);
        assertThat(memberships.get(6_000_000_002L)).containsExactly(5_000_000_002L);
        mapper.removeArtwork(5_000_000_002L, 6_000_000_002L);
        assertThat(service.membershipsOf(List.of(6_000_000_002L))).isEmpty();
        assertThat(service.membershipsOf(List.of())).isEmpty();
        assertThat(service.membershipsOf(null)).isEmpty();
    }
}
