package top.sywyar.pixivdownload.databasepostgresql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.*;

@DisplayName("外置 PostgreSQL 目标配置")
class PostgresConnectionTargetTest {
    @ParameterizedTest
    @ValueSource(ints = {17, 18})
    @DisplayName("受支持的服务器主版本通过产品与编码校验")
    void acceptsSupportedServerVersions(int major) throws Exception {
        PostgresConnectionTarget.validate(server("PostgreSQL", major, "UTF8"));
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 19})
    @DisplayName("支持范围之外的服务器版本仍然拒绝")
    void rejectsUnsupportedServerVersions(int major) {
        assertThatThrownBy(() -> PostgresConnectionTarget.validate(server("PostgreSQL", major, "UTF8")))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_PRODUCT_UNSUPPORTED")
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("0A000"));
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 18})
    @DisplayName("支持的主版本不会绕过产品与编码检查")
    void supportedVersionStillRequiresPostgresAndUtf8(int major) {
        assertThatThrownBy(() -> PostgresConnectionTarget.validate(server("OtherDatabase", major, "UTF8")))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_PRODUCT_UNSUPPORTED");
        assertThatThrownBy(() -> PostgresConnectionTarget.validate(server("PostgreSQL", major, "SQL_ASCII")))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_ENCODING_UNSUPPORTED");
    }

    @Test
    @DisplayName("未填用户名与不存在的存储空间分别报告可修正原因")
    void distinguishesUsernameAndMissingSchema() {
        assertThatThrownBy(() -> new PostgresConnectionTarget(new DatabaseConnectionSettings(
                "jdbc:postgresql://fixture.invalid/library", "", "private-password", Path.of("unused"))))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_USERNAME_REQUIRED").hasNoCause();
        var values = new java.util.HashMap<String, Object>();
        values.put("getMetaData", jdbc(DatabaseMetaData.class,
                Map.of("getDatabaseProductName", "PostgreSQL", "getDatabaseMajorVersion", 17)));
        values.put("getSchema", null);
        assertThatThrownBy(() -> PostgresConnectionTarget.validate(jdbc(Connection.class, values)))
                .isInstanceOf(SQLException.class).hasMessage("DATABASE_SCHEMA_MISSING").hasNoCause();
    }

    @Test
    @DisplayName("市场描述符与运行期身份一致，命名空间能实际解析展示文案")
    void marketIdentityMatchesRuntimeAndResolvesBundle() throws Exception {
        var plugin = new PostgresDatabasePlugin();
        Properties descriptor = new Properties();
        try (var input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        assertThat(descriptor.getProperty("pixiv.display-namespace")).isEqualTo(plugin.displayNamespace());
        assertThat(descriptor.getProperty("pixiv.display-name-key")).isEqualTo(plugin.displayName());
        assertThat(descriptor.getProperty("pixiv.description-key")).isEqualTo(plugin.description());
        assertThat(descriptor.getProperty("pixiv.icon-key")).isEqualTo(plugin.iconKey());
        assertThat(descriptor.getProperty("pixiv.color-token")).isEqualTo(plugin.colorToken());
        var contribution = plugin.i18n().stream()
                .filter(value -> value.namespace().equals(plugin.displayNamespace())).findFirst().orElseThrow();
        var bundle = ResourceBundle.getBundle(
                contribution.baseName(), Locale.ENGLISH, plugin.getClass().getClassLoader());
        assertThat(bundle.getLocale().getLanguage()).isEqualTo(Locale.ENGLISH.getLanguage());
        assertThat(bundle.getString(plugin.displayName())).isNotBlank().isNotEqualTo(plugin.displayName());
        assertThat(bundle.getString(plugin.description())).isNotBlank().isNotEqualTo(plugin.description());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql:implicit", "jdbc:postgresql://", "jdbc:postgresql://server/",
            "jdbc:postgresql://user@server/data", "jdbc:postgresql://server/data?password=secret",
            "jdbc:postgresql://server/data?currentSchema=one,two",
            "jdbc:postgresql://server/data?sslmode=require&sslmode=disable",
            "jdbc:postgresql://server/data?currentSchema=%",
            "jdbc:postgresql://server/data?unknown=value"
    })
    @DisplayName("非法地址、URL 凭据及多 schema 不能被默认推断接管")
    void rejectsInvalidConfiguration(String url) {
        assertThatThrownBy(() -> target(url)).isInstanceOf(SQLException.class)
                .hasMessage("DATABASE_CONFIGURATION_INVALID").hasNoCause();
    }

    @Test
    @DisplayName("目标说明不含凭据和 TLS 选项，空连接用途被拒绝")
    void descriptionIsSafeAndNullModeRejected() throws Exception {
        var target = target("jdbc:postgresql://fixture.invalid:55432/library?currentSchema=owner&sslmode=verify-full&sslrootcert=private-file");
        assertThat(target.description()).isEqualTo("postgresql: fixture.invalid:55432/library [owner]");
        assertThat(target.localFile()).isEmpty();
        assertThat(target.toString()).isEqualTo("postgresql");
        assertThatThrownBy(() -> target.openConnection(null)).isInstanceOf(SQLException.class)
                .hasMessage("DATABASE_CONFIGURATION_INVALID");
    }

    @Test
    @DisplayName("草稿检查的非法超时在连接前被拒绝")
    void invalidProbeTimeoutDoesNotConnect() throws Exception {
        var target = target("jdbc:postgresql://fixture.invalid/library?connectTimeout=-1");
        assertThatThrownBy(target::checkConnection).isInstanceOf(SQLException.class)
                .hasMessage("DATABASE_CONFIGURATION_INVALID").hasNoCause();
    }

    private static Connection server(String product, int major, String encoding) {
        var metadata = jdbc(DatabaseMetaData.class, Map.of("getDatabaseProductName", product, "getDatabaseMajorVersion", major));
        var rows = jdbc(ResultSet.class, Map.of("next", true, "getString", encoding));
        var statement = jdbc(Statement.class, Map.of("executeQuery", rows));
        return jdbc(Connection.class, Map.of("getMetaData", metadata, "getSchema", "fixture", "createStatement", statement));
    }

    private static <T> T jdbc(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("close")) return null;
            if (values.containsKey(method.getName())) return values.get(method.getName());
            throw new AssertionError("Unexpected JDBC call: " + method.getName());
        }));
    }

    private static PostgresConnectionTarget target(String url) throws SQLException {
        return new PostgresConnectionTarget(new DatabaseConnectionSettings(url, "fixture-user", "fixture-secret",
                Path.of("unused.db")));
    }
}
