package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;

@DisplayName("插件主库存储域授权与真实事务")
class PluginMainStorageTest {
    @TempDir Path directory;

    private static SchemaContribution owned(String name) {
        return new SchemaContribution(List.of(new TableSpec(name,
                List.of(new ColumnSpec("id", "INTEGER", true, null, 1)), List.of())), List.of(), List.of());
    }
    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "postgresql"})
    @DisplayName("自有表隔离、共享只读域、回调失效与事务回滚")
    void ownerIsolationAndTransactions(String engine) throws Exception {
        PostgresTestDatabase postgres = null;
        com.zaxxer.hikari.HikariDataSource pool = null;
        try {
            DatabaseTarget target;
            if (engine.equals("postgresql")) {
                Assumptions.assumeTrue(System.getenv("PIXIV_TEST_POSTGRES_URL") != null);
                postgres = new PostgresTestDatabase(); pool = postgres.pool();
                target = DatabaseProviderTestFixtures.resolve(postgres.properties(), "unused");
            } else {
                target = DatabaseProviderTestFixtures.sqlite(directory.resolve("scope.db"));
                pool = target.openPool(DatabasePoolProperties.MINIMUM_POOL_SIZE);
            }
            var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
            registry.register("first-owner", owned("first_facts"));
            registry.register("second-owner", owned("second_facts"));
            try (var connection = pool.getConnection()) {
                top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.prepareOffline(connection, target);
                connection.setAutoCommit(false);
                target.prepareTransaction(connection, false);
                target.applySchema(connection, registry.storageSchema(), true);
                connection.commit();
            }
            var transactions = new DatabaseConfig(null, null).transactionManager(pool, target);
            var main = new MainDatabase(pool, target, registry, transactions);
            try (var first = main.forOwner("first-owner", List.of("pixiv-tags"));
                 var second = main.forOwner("second-owner", List.of())) {
                assertThatThrownBy(() -> main.forOwner("first-owner", List.of("unpublished"))).hasMessage("STORAGE_DOMAIN_UNAVAILABLE");
                first.transaction(s -> s.insert("first_facts", Map.of("id", 1L), DatabaseSession.Conflict.error()));
                assertThat(first.<Long>read(s -> (Long) s.query(Query.count("first_facts", null)).get(0).get("count"))).isEqualTo(1L);
                assertThatThrownBy(() -> second.read(s -> s.query(Query.count("first_facts", null)))).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> first.read(s -> s.query(Query.count("scheduled_task_credentials", null)))).isInstanceOf(IllegalArgumentException.class);
                assertThat(first.<List<Map<String, Object>>>read(s -> s.query(Query.count("tags", null)))).hasSize(1);
                assertThatThrownBy(() -> first.transaction(s -> {
                    s.insert("first_facts", Map.of("id", 2L), DatabaseSession.Conflict.error());
                    try { s.insert("tags", Map.of("name", "forbidden"), DatabaseSession.Conflict.error()); }
                    catch (java.sql.SQLException rejected) { assertThat(rejected.getSQLState()).isEqualTo("42501"); }
                    return null;
                })).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(first.<Long>read(s -> (Long) s.query(Query.count("first_facts", null)).get(0).get("count"))).isEqualTo(1L);
                var escaped = first.read(s -> s);
                assertThatThrownBy(() -> escaped.query(Query.count("first_facts", null)))
                        .isInstanceOf(java.sql.SQLException.class).hasMessage("STORAGE_SESSION_UNAVAILABLE");
                var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
                outer.execute(status -> {
                    first.transaction(s -> s.insert("first_facts", Map.of("id", 3L), DatabaseSession.Conflict.error()));
                    status.setRollbackOnly(); return null;
                });
                assertThat(first.<Long>read(s -> (Long) s.query(Query.count("first_facts", null)).get(0).get("count"))).isEqualTo(1L);
                var proxy = new org.springframework.aop.framework.ProxyFactory(new NestedWriter(first, transactions));
                proxy.setProxyTargetClass(true);
                proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                ((NestedWriter) proxy.getProxy()).writeWithFailedSavepoint();
                var id = column("first_facts", "id");
                assertThat(first.<List<Map<String, Object>>>read(s -> s.query(new Query("first_facts", Map.of("id", id),
                        null, List.of(), List.of(new Query.Order(id, false, false)), 10, 0))))
                        .extracting(row -> row.get("id")).containsExactly(1L, 5L, 7L);
                first.transaction(s -> s.delete("first_facts", call(Expression.Operator.GREATER, id, value(1L))));
                assertThatThrownBy(() -> first.transaction(s -> {
                    s.insert("first_facts", Map.of("id", 4L), DatabaseSession.Conflict.error());
                    first.close(); return null;
                })).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(first.available()).isFalse();
                assertThatThrownBy(() -> first.read(s -> null))
                        .isInstanceOf(top.sywyar.pixivdownload.core.database.DatabaseUnavailableException.class);
                assertThat(main.<Long>read(s -> (Long) s.query(Query.count("first_facts", null)).get(0).get("count"))).isEqualTo(1L);
            }
        } finally {
            if (postgres != null) postgres.close();
            else if (pool != null) pool.close();
        }
    }

    public static class NestedWriter {
        private final top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase database;
        private final org.springframework.transaction.PlatformTransactionManager transactions;
        NestedWriter(top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase database,
                     org.springframework.transaction.PlatformTransactionManager transactions) {
            this.database = database;
            this.transactions = transactions;
        }
        @org.springframework.transaction.annotation.Transactional
        public void writeWithFailedSavepoint() {
            database.transaction(s -> s.insert("first_facts", Map.of("id", 5L), DatabaseSession.Conflict.error()));
            var nested = new org.springframework.transaction.support.TransactionTemplate(transactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.executeWithoutResult(status -> database.transaction(s -> {
                s.insert("first_facts", Map.of("id", 6L), DatabaseSession.Conflict.error());
                return s.insert("first_facts", Map.of("id", 1L), DatabaseSession.Conflict.error());
            }))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            database.transaction(s -> s.insert("first_facts", Map.of("id", 7L), DatabaseSession.Conflict.error()));
        }
    }

    @Test
    @DisplayName("第三方不能公开主库域且当前结构快照只在发布变化后失效")
    void exportsAndSnapshotLifecycle() {
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        var initial = registry.storageSchema();
        assertThat(registry.storageSchema()).isSameAs(initial);
        assertThatThrownBy(() -> registry.register("third-party", new SchemaContribution(owned("attempt").tables(),
                List.of(), List.of(), new SchemaContribution.SharedAccess("forged", false))))
                .hasMessage("STORAGE_EXPORT_NOT_CORE");
        assertThat(registry.storageSchema()).isSameAs(initial);
        registry.register("scope-owner", owned("owner_facts"));
        var withOwner = registry.storageSchema();
        assertThat(withOwner).isNotSameAs(initial);
        assertThat(registry.storageSchema()).isSameAs(withOwner);
        try (var reservation = registry.reserveOwnerEvolution("scope-owner", List.of(owned("owner_facts")))) {
            assertThat(registry.storageSchema()).isSameAs(withOwner);
        }
        assertThat(registry.storageSchema()).isSameAs(withOwner);
        registry.unregister("scope-owner");
        assertThat(registry.storageSchema().tables()).isEqualTo(initial.tables());
    }
}
