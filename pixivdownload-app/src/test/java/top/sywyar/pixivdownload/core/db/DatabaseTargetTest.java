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
        assertThat(DatabaseTarget.fromConfiguration(location).engine()).isEqualTo(DatabaseEngine.POSTGRESQL);
        var profile = DatabaseTarget.fromConfiguration(location, "--spring.profiles.active=isolated");
        assertThat(profile.sqlitePath()).isEqualTo(java.nio.file.Path.of("profile.db").toAbsolutePath());
        var override = DatabaseTarget.fromConfiguration(location, "--database.engine=sqlite",
                "--database.username=", "--database.jdbc-url=jdbc:sqlite:" + directory.resolve("override.db"));
        assertThat(override.sqlitePath()).isEqualTo(directory.resolve("override.db"));
        assertThat(java.nio.file.Files.exists(override.sqlitePath())).isFalse();
        assertThatThrownBy(() -> DatabaseTarget.fromConfiguration(location, "--database.jdbc-url="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("扁平配置直接绑定已有属性类，不在字符串表示中暴露凭据")
    void flatConfigurationBindsToExistingProperties() throws Exception {
        var config = new Binder(new MapConfigurationPropertySource(Map.of(
                "database.engine", "postgresql", "database.jdbc-url", "jdbc:postgresql://example.invalid/app",
                "database.username", "account", "database.password", "private-value")))
                .bind("database", Bindable.of(DatabasePoolProperties.class)).orElseThrow(IllegalStateException::new);
        var target = DatabaseTarget.resolve(config, "unused");
        assertThat(target.engine()).isEqualTo(DatabaseEngine.POSTGRESQL);
        assertThat(target.toString()).doesNotContain("account", "private-value", "example.invalid");
        assertThatThrownBy(target::sqlitePath).isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> DatabaseTarget.resolve(config, "unused"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    }
}
