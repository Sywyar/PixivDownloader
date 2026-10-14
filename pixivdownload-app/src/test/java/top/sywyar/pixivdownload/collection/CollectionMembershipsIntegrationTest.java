package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    private void verifyMemberships(DataSource pool) throws Exception {
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var jdbc = new JdbcTemplate(pool);
        var initializer = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
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
