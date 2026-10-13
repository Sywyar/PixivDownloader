package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("主库配置校验")
class DatabaseTargetTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test
    @DisplayName("未知标识可绑定目标；仅迁移提供者自行拒绝未实现的连接且不创建存储")
    void bindsUnknownProviderForMigrationWithoutClaimingRuntimeSupport() throws Exception {
        var provider = new migrationfixture.NeutralMigrationPlugin.Provider();
        var selection = new top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.Result(
                provider.id(), provider.databaseId(), java.util.Optional.of(provider),
                top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.Failure.NONE);
        var file = directory.resolve("independent.ledger");
        var settings = new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings(
                "ledger:" + file, "", "", directory.resolve("unused.db"));
        var target = DatabaseTarget.inspect(selection, settings);
        assertThat(target.available()).isTrue();
        assertThat(target.migrationEndpoint().provider()).isSameAs(provider);
        assertThat(target.migrationEndpoint().target().localFile()).contains(file);
        assertThat(target.sameConfiguration(DatabaseTarget.inspect(selection, settings))).isTrue();
        assertThat(target.toString()).isEqualTo("ledger-v1");
        assertThat(target.databaseId()).isEqualTo(provider.databaseId());
        assertThatThrownBy(target::openConnection).isInstanceOf(java.sql.SQLException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("启动检查使用 Boot 配置文件、profile 和命令行优先级")
    void startupResolutionUsesBootConfigAndOverrides() throws Exception {
        var config = directory.resolve("application.yaml");
        java.nio.file.Files.writeString(config, """
                database.engine: postgresql
                database.jdbc-url: jdbc:postgresql://example.invalid/app
                database.username: fixture
                ---
                spring.config.activate.on-profile: isolated
                database.engine: sqlite
                database.jdbc-url: jdbc:sqlite:profile.db
                database.username: ''
                """, java.nio.charset.StandardCharsets.UTF_8);
        String location = "--spring.config.location=" + config.toUri();
        assertThat(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.fromConfiguration(location).databaseId()).isEqualTo("postgresql");
        var profile = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.fromConfiguration(location, "--spring.profiles.active=isolated");
        assertThat(profile.localFile().orElseThrow()).isEqualTo(java.nio.file.Path.of("profile.db").toAbsolutePath());
        var override = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.fromConfiguration(location, "--database.engine=sqlite",
                "--database.username=", "--database.jdbc-url=jdbc:sqlite:" + directory.resolve("override.db"));
        assertThat(override.localFile().orElseThrow()).isEqualTo(directory.resolve("override.db"));
        assertThat(java.nio.file.Files.exists(override.localFile().orElseThrow())).isFalse();
        assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.fromConfiguration(location, "--database.jdbc-url="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("扁平配置直接绑定已有属性类，不在字符串表示中暴露凭据")
    void flatConfigurationBindsToExistingProperties() throws Exception {
        var config = new Binder(new MapConfigurationPropertySource(Map.of(
                "database.engine", "postgresql", "database.jdbc-url", "jdbc:postgresql://example.invalid/app",
                "database.username", "account", "database.password", "private-value")))
                .bind("database", Bindable.of(DatabasePoolProperties.class)).orElseThrow(IllegalStateException::new);
        var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(config, "unused");
        assertThat(target.databaseId()).isEqualTo("postgresql");
        assertThat(target.toString()).doesNotContain("account", "private-value", "example.invalid");
        assertThat(target.localFile()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "jdbc:sqlite:accidental.db", "jdbc:postgresql:implicit",
            "jdbc:postgresql:///app", "jdbc:postgresql://:5432/app",
            "jdbc:postgresql://host,,other/app", "jdbc:postgresql://host,:5432/app",
            "jdbc:postgresql://localhost/", "jdbc:postgresql://localhost/app?password=private",
            "jdbc:postgresql://localhost/app?%70assword=private",
            "jdbc:postgresql://localhost/app?currentSchema=public,other",
            "jdbc:postgresql://localhost/app?currentSchema=public&currentSchema=other",
            "jdbc:postgresql://localhost/app?%private=value",
            "jdbc:postgresql://localhost/app?ApplicationName=%private"})
    @DisplayName("拒绝不明确目标、凭据 URL 和多个 schema")
    void rejectsAmbiguousOrSensitiveUrls(String url) {
        var config = new DatabasePoolProperties();
        config.setEngine("postgresql");
        config.setJdbcUrl(url);
        config.setUsername("fixture");
        assertThatThrownBy(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(config, "unused"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    }
}
