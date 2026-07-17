package com.example.pixivdownload.minimal;

import com.example.pixivdownload.minimal.web.ExampleMinimalController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.Plugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleMinimalPluginTest {

    @Test
    @DisplayName("plugin.properties 必填字段完整且主类同时满足 PF4J 与宿主入口契约")
    void descriptorDeclaresRequiredFieldsAndEntrypoint() throws Exception {
        Properties descriptor = loadProperties("plugin.properties");
        for (String key : Set.of("plugin.id", "plugin.version", "plugin.requires", "plugin.class")) {
            assertFalse(descriptor.getProperty(key, "").isBlank(), () -> "missing descriptor key: " + key);
        }

        assertEquals("example-minimal", descriptor.getProperty("plugin.id"));
        assertEquals("0.1.0", descriptor.getProperty("plugin.version"));
        assertEquals("1.0", descriptor.getProperty("plugin.requires"));
        assertEquals("com.example.pixivdownload.minimal.ExampleMinimalPf4jPlugin",
                descriptor.getProperty("plugin.class"));
        assertEquals("example-minimal", descriptor.getProperty("pixiv.display-namespace"));
        assertEquals("plugin.name", descriptor.getProperty("pixiv.display-name-key"));
        assertEquals("plugin.summary", descriptor.getProperty("pixiv.description-key"));
        assertEquals("puzzle", descriptor.getProperty("pixiv.icon-key"));
        assertEquals("blue", descriptor.getProperty("pixiv.color-token"));

        Class<?> entrypoint = Class.forName(descriptor.getProperty("plugin.class"));
        assertTrue(Plugin.class.isAssignableFrom(entrypoint));
        assertTrue(PixivPluginProvider.class.isAssignableFrom(entrypoint));
    }

    @Test
    @DisplayName("provider 返回非空、无 null 的功能插件并声明唯一配置类")
    void providerReturnsFeaturesAndConfiguration() {
        ExampleMinimalPf4jPlugin provider = new ExampleMinimalPf4jPlugin();
        List<PixivFeaturePlugin> features = provider.featurePlugins();

        assertFalse(features.isEmpty());
        assertTrue(features.stream().noneMatch(Objects::isNull));
        assertInstanceOf(ExampleMinimalPlugin.class, features.get(0));
        assertEquals(List.of(ExampleMinimalConfiguration.class), provider.configurationClasses());
    }

    @Test
    @DisplayName("子上下文配置显式装配 descriptor 与 controller")
    void configurationCreatesPluginOwnedBeans() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ExampleMinimalConfiguration.class)) {
            assertNotNull(context.getBean(ExampleMinimalPlugin.class));
            assertNotNull(context.getBean(ExampleMinimalController.class));
        }
    }

    @Test
    @DisplayName("功能 descriptor 声明稳定身份、展示 token 与管理员路由")
    void featureDeclaresIdentityAndAdminRoutes() {
        ExampleMinimalPlugin plugin = new ExampleMinimalPlugin();

        assertEquals("example-minimal", plugin.id());
        assertEquals("plugin.name", plugin.displayName());
        assertEquals("plugin.summary", plugin.description());
        assertEquals("example-minimal", plugin.displayNamespace());
        assertEquals("puzzle", plugin.iconKey());
        assertEquals("blue", plugin.colorToken());
        assertEquals(PluginKind.FEATURE, plugin.kind());

        Set<String> routePatterns = plugin.routes().stream()
                .map(WebRouteContribution::pathPattern)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "/example-minimal.html",
                "/example-minimal/**",
                "/api/example-minimal/**"), routePatterns);
        assertTrue(plugin.routes().stream().allMatch(route -> route.accessPolicy() == AccessPolicy.ADMIN));
    }

    @Test
    @DisplayName("静态资源与 i18n 贡献可由插件 classloader 解析")
    void staticAndI18nContributionsResolve() throws IOException {
        ExampleMinimalPlugin plugin = new ExampleMinimalPlugin();
        List<StaticResourceContribution> staticResources = plugin.staticResources();

        assertEquals(2, staticResources.size());
        assertTrue(staticResources.stream().allMatch(resource -> resource.pluginId().equals(plugin.id())));
        assertTrue(staticResources.stream().anyMatch(resource ->
                resource.exactFile() && resource.publicPathPrefix().equals("/example-minimal.html")));
        assertTrue(staticResources.stream().anyMatch(resource ->
                !resource.exactFile() && resource.publicPathPrefix().equals("/example-minimal/")));
        assertNotNull(getClass().getClassLoader().getResource("static/example-minimal.html"));
        assertNotNull(getClass().getClassLoader().getResource("static/example-minimal/example-minimal.css"));
        assertNotNull(getClass().getClassLoader().getResource("static/example-minimal/example-minimal-core.js"));
        assertNotNull(getClass().getClassLoader().getResource("static/example-minimal/example-minimal-init.js"));

        I18nContribution i18n = plugin.i18n().get(0);
        assertEquals("example-minimal", i18n.namespace());
        assertEquals("i18n.web.example-minimal", i18n.baseName());
        Properties zh = loadProperties("i18n/web/example-minimal.properties");
        Properties en = loadProperties("i18n/web/example-minimal_en.properties");
        assertEquals(zh.stringPropertyNames(), en.stringPropertyNames());
        for (String key : Set.of("plugin.name", "plugin.summary", "page.title", "status.ready")) {
            assertFalse(zh.getProperty(key, "").isBlank());
            assertFalse(en.getProperty(key, "").isBlank());
        }
    }

    @Test
    @DisplayName("schema 只声明插件自有的最小主键表")
    void schemaContributionIsPluginOwnedAndMinimal() {
        List<SchemaContribution> schema = new ExampleMinimalPlugin().schema();
        assertEquals(1, schema.size());

        SchemaContribution contribution = schema.get(0);
        assertEquals("example-minimal", contribution.ownerPluginId());
        assertTrue(contribution.indexes().isEmpty());
        assertTrue(contribution.columnMigrations().isEmpty());
        assertTrue(contribution.pathColumns().isEmpty());
        assertEquals(1, contribution.tables().size());

        TableSpec table = contribution.tables().get(0);
        assertEquals("example_minimal_records", table.name());
        assertTrue(table.indexes().isEmpty());
        assertEquals(1, table.columns().size());
        ColumnSpec id = table.columns().get(0);
        assertEquals("id", id.name());
        assertEquals("TEXT", id.type());
        assertTrue(id.notNull());
        assertEquals(1, id.primaryKeyPosition());
        assertFalse(id.autoIncrement());
    }

    private static Properties loadProperties(String resourceName) throws IOException {
        ClassLoader classLoader = ExampleMinimalPluginTest.class.getClassLoader();
        try (InputStream input = Objects.requireNonNull(classLoader.getResourceAsStream(resourceName), resourceName);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        }
    }
}
