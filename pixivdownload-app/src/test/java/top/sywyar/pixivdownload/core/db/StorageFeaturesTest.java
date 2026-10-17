package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("提供者派生索引与原子关联清理")
class StorageFeaturesTest {
    @TempDir Path directory;
    private static final StorageFeatures.TextIndex INDEX =
            new StorageFeatures.TextIndex("stories_search", "stories", "id", "body", "deleted", 0);
    private static final StorageFeatures.Cleanup CLEANUP = new StorageFeatures.Cleanup(
            "stories_cleanup", "stories", "id", "deleted", 1, List.of(new StorageFeatures.Dependent("story_notes", "story_id")));

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("同一声明执行字面检索、软删除同步清理、重启回填及事务回滚")
    void providerBehavior(String engine) throws Exception {
        PostgresTestDatabase postgres = null;
        com.zaxxer.hikari.HikariDataSource pool = null;
        try {
            DatabaseTarget target;
            if (engine.equals("postgresql")) {
                Assumptions.assumeTrue(System.getenv("PIXIV_TEST_POSTGRES_URL") != null);
                postgres = new PostgresTestDatabase(); pool = postgres.pool();
                target = DatabaseProviderTestFixtures.resolve(postgres.properties(), "unused");
            } else {
                target = DatabaseProviderTestFixtures.sqlite(directory.resolve("search.db"));
                pool = target.openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE);
            }
            var registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
            registry.register("stories-owner", new SchemaContribution(List.of(
                    new TableSpec("stories", List.of(new ColumnSpec("id", "INTEGER", true, null, 1),
                            new ColumnSpec("body", "TEXT", true, null, 0),
                            new ColumnSpec("deleted", "INTEGER", true, "0", 0)), List.of()),
                    new TableSpec("story_notes", List.of(new ColumnSpec("story_id", "INTEGER", true, null, 1)), List.of())),
                    List.of(), List.of()));
            try (var c = pool.getConnection()) {
                c.setAutoCommit(false); target.prepareTransaction(c, false);
                target.applySchema(c, registry.storageSchema(), true); c.commit();
            }
            var tx = new DatabaseConfig(null, null).transactionManager(pool, target);
            var main = new MainDatabase(pool, target, registry, tx);
            try (var storage = main.forOwner("stories-owner", List.of())) {
                storage.transaction(s -> {
                    s.initializeCleanup(CLEANUP);
                    s.initializeCleanup(CLEANUP);
                    s.initializeTextIndex(INDEX);
                    s.initializeTextIndex(INDEX);
                    s.insert("stories", Map.of("id", 1L, "body", "ÉCOLE 冒险旅程 中文！ %_ a! quote \"x\""), DatabaseSession.Conflict.error());
                    s.insert("story_notes", Map.of("story_id", 1L), DatabaseSession.Conflict.error());
                    s.refreshTextIndex(INDEX, null);
                    return null;
                });
                for (String term : List.of("école", "冒险旅程", "%_", "a!", "quote \"x\"")) {
                    assertThat(storage.<List<Long>>read(s -> s.searchText(INDEX, term))).containsExactly(1L);
                }
                assertThat(storage.<List<Long>>read(s -> s.searchText(INDEX, "not present"))).isEmpty();
                var outer = new org.springframework.transaction.support.TransactionTemplate(tx);
                outer.execute(status -> {
                    storage.transaction(s -> s.update("stories", Map.of("deleted", value(1)),
                            call(EQUAL, column("stories", "id"), value(1))));
                    assertThat(storage.<Long>read(s -> (Long) s.query(Query.count("story_notes", null)).get(0).get("count"))).isZero();
                    status.setRollbackOnly(); return null;
                });
                assertThat(storage.<Long>read(s -> (Long) s.query(Query.count("story_notes", null)).get(0).get("count"))).isEqualTo(1L);
                storage.transaction(s -> s.update("stories", Map.of("deleted", value(1)), call(EQUAL, column("stories", "id"), value(1))));
                assertThat(storage.<List<Long>>read(s -> s.searchText(INDEX, "冒险旅程"))).isEmpty();
                assertThat(storage.<Long>read(s -> (Long) s.query(Query.count("story_notes", null)).get(0).get("count"))).isZero();
                storage.transaction(s -> s.refreshTextIndex(INDEX, null));
                storage.transaction(s -> {
                    s.update("stories", Map.of("deleted", value(0), "body", value("New content")),
                            call(EQUAL, column("stories", "id"), value(1)));
                    return s.refreshTextIndex(INDEX, 1L);
                });
                assertThat(storage.<List<Long>>read(s -> s.searchText(INDEX, "content"))).containsExactly(1L);
                assertThat(storage.<List<Long>>read(s -> s.searchText(INDEX, "冒险旅程"))).isEmpty();
                var changedRule = new StorageFeatures.Cleanup(CLEANUP.name(), "stories", "id", "deleted", 2, CLEANUP.dependents());
                assertThatThrownBy(() -> storage.transaction(s -> { s.initializeCleanup(changedRule); return null; }))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        } finally {
            if (postgres != null) postgres.close(); else if (pool != null) pool.close();
        }
    }
}
