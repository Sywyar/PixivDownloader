package top.sywyar.pixivdownload.plugin.runtime.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("中性私库 child context 与旧 SDK 隔离")
class PluginDatabaseContextTest {
    interface CloseableDatabase extends PluginDatabase, javax.sql.DataSource { void close(); }
    interface CloseableMainDatabase extends top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase { void close(); }

    @Test @DisplayName("主库存储句柄绑定发现时的 owner 和域声明，关闭 child 时撤销")
    void bindsMainStorageToCapturedOwner() {
        var main = mock(CloseableMainDatabase.class);
        var module = new PluginContextModule("main-consumer", getClass().getClassLoader(), List.of(),
                List.of(), List.of(), List.of("published-domain"));
        var factory = new PluginApplicationContextFactory(PluginContextPropertySourceProvider.EMPTY,
                new PluginStreamRegistry(), new PluginRuntimeTaskRegistry(), null, null, null,
                captured -> { assertThat(captured).isSameAs(module); return main; });
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            try (var child = factory.create(parent, module)) {
                assertThat(child.getBean(top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase.class)).isSameAs(main);
                assertThat(parent.getBeanNamesForType(top.sywyar.pixivdownload.plugin.api.storage.PluginMainDatabase.class)).isEmpty();
            }
            verify(main).close();
        }
    }

    @Test @DisplayName("只声明私有结构的插件也在业务装配前初始化并在关闭时释放")
    void initializesDeclaredStorageWithoutBusinessBeans() {
        var database = mock(CloseableDatabase.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var schema = new top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution(List.of(
                new top.sywyar.pixivdownload.plugin.api.schema.TableSpec("entries", List.of(
                        new top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec("id", "INTEGER", false, null, 1)), List.of())),
                List.of(), List.of());
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            try (var child = factory(owner -> { calls.incrementAndGet(); return database; }).create(parent,
                    new PluginContextModule("fixture-owner", getClass().getClassLoader(), List.of(), List.of(schema), List.of()))) {
                assertThat(calls.get()).isEqualTo(1);
            }
            verify(database).close();
        }
    }

    @Test
    @DisplayName("只创建当前 owner 的数据源，主库密码被遮蔽且关闭时释放一次")
    void ownsStorageWithoutExposingMainCredentials() throws Exception {
        var database = mock(CloseableDatabase.class);
        when(database.available()).thenReturn(true);
        when(database.databaseId()).thenReturn("sqlite");
        var connection = mock(Connection.class);
        when(database.getConnection()).thenReturn(connection);
        var factory = factory(owner -> { assertThat(owner).isEqualTo("fixture-owner"); return database; });
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                    Map.of("database.password", "private-administration-password")));
            parent.refresh();
            try (var child = factory.create(parent, new PluginContextModule("fixture-owner", getClass().getClassLoader(), List.of()))) {
                assertThat(child.getEnvironment().getProperty("database.password")).isEmpty();
                assertThat(parent.getEnvironment().getProperty("database.password")).isEqualTo("private-administration-password");
                assertThat(child.getBean(PluginDatabase.class)).isSameAs(database);
                assertThat(child.getBean(PluginDataSource.class).getConnection()).isSameAs(connection);
                assertThat(parent.getBeanNamesForType(PluginDatabase.class)).isEmpty();
            }
            verify(database).close();
        }
    }

    @Test
    @DisplayName("旧 SQLite SDK 在新方言或数据库缺席时明确拒绝且不借连接")
    void legacyConsumerCannotFallBackToSqlite() throws Exception {
        var database = mock(CloseableDatabase.class);
        when(database.available()).thenReturn(true);
        when(database.databaseId()).thenReturn("postgresql");
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.refresh();
            try (var child = factory(owner -> database).create(parent,
                    new PluginContextModule("fixture-owner", getClass().getClassLoader(), List.of()))) {
                var legacy = child.getBean(PluginDataSource.class);
                assertThatThrownBy(legacy::getConnection).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("0A000"));
                when(database.available()).thenReturn(false);
                assertThatThrownBy(legacy::getConnection).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("08003"));
                verify(database, never()).getConnection();
            }
        }
    }

    private static PluginApplicationContextFactory factory(java.util.function.Function<String, PluginDatabase> create) {
        return new PluginApplicationContextFactory(PluginContextPropertySourceProvider.EMPTY,
                new PluginStreamRegistry(), new PluginRuntimeTaskRegistry(), null, null, module -> create.apply(module.sourcePluginId()));
    }
}
