package com.example.pixivdownload.downloadtype;

import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleDownloadPluginTest {

    @Test
    @DisplayName("PF4J 描述符包含全部必填身份字段")
    void descriptorContainsRequiredIdentity() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals("example-download", properties.getProperty("plugin.id"));
        assertEquals("0.1.0", properties.getProperty("plugin.version"));
        assertEquals("1.0", properties.getProperty("plugin.requires"));
        assertEquals(
                ExampleDownloadPf4jPlugin.class.getName(),
                properties.getProperty("plugin.class"));
    }

    @Test
    @DisplayName("中英文 Web 国际化键集合完全一致")
    void webI18nKeysStayAligned() throws IOException {
        Properties chinese = load("i18n/web/example-download.properties");
        Properties english = load("i18n/web/example-download_en.properties");
        assertEquals(chinese.stringPropertyNames(), english.stringPropertyNames());
    }

    @Test
    @DisplayName("Provider 暴露非空功能插件与显式配置类")
    void providerExposesFeatureAndConfiguration() {
        ExampleDownloadPf4jPlugin provider = new ExampleDownloadPf4jPlugin();
        PixivFeaturePlugin feature = provider.featurePlugin();
        assertNotNull(feature);
        assertEquals("example-download", feature.id());
        assertEquals(java.util.List.of(ExampleDownloadConfiguration.class), provider.configurationClasses());
    }

    @Test
    @DisplayName("子上下文只发布一个队列操作 bean 且可按 queueType 唯一注册")
    void childContextPublishesOneUniquelyRegisterableQueue() {
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            child.registerBean(RequestOwnerIdentityResolver.class,
                    () -> ignored -> RequestOwnerIdentity.owner("test-owner"));
            child.register(ExampleDownloadConfiguration.class);
            child.refresh();

            var operations = child.getBeansOfType(QueueOperations.class);
            assertEquals(1, operations.size());
            assertTrue(operations.containsKey("exampleDownloadQueue"));
            assertTrue(operations.values().iterator().next() instanceof ExampleDownloadQueue);
            assertEquals(
                    operations.size(),
                    new HashSet<>(operations.values().stream().map(QueueOperations::queueType).toList()).size());
        }
    }

    @Test
    @DisplayName("下载类型声明五种取得模式和真实取消边界")
    void downloadTypeDeclaresStableCapabilities() {
        ExampleDownloadPlugin plugin = new ExampleDownloadPlugin();
        var descriptor = plugin.downloadTypes().get(0);

        assertEquals(ExampleDownloadPlugin.TYPE, descriptor.type());
        assertEquals(1, descriptor.contractVersion());
        assertEquals(java.util.List.of(
                DownloadAcquisitionMode.SINGLE_IMPORT,
                DownloadAcquisitionMode.USER_PROFILE,
                DownloadAcquisitionMode.SERIES_COLLECTION,
                DownloadAcquisitionMode.SEARCH,
                DownloadAcquisitionMode.QUICK), descriptor.acquisitionModes());
        assertTrue(descriptor.cancelSupported());

        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        queue.complete("100", "A", RequestOwnerIdentity.owner("owner-a"));
        queue.cancel("100", "owner-a", false);
        assertTrue(queue.find("100", RequestOwnerIdentity.owner("owner-a")).isEmpty());
        assertEquals(ExampleDownloadPlugin.TYPE_MODULE_URL, descriptor.moduleUrl());
    }

    @Test
    @DisplayName("路由静态资源国际化槽位与计划来源均由插件声明")
    void webAndScheduleContributionsArePresent() {
        ExampleDownloadPlugin plugin = new ExampleDownloadPlugin();

        assertTrue(plugin.routes().stream().anyMatch(route ->
                route.pathPattern().equals("/api/example-download/**")));
        assertTrue(plugin.routes().stream().anyMatch(route ->
                route.pathPattern().equals("/example-download-gallery.html")));
        assertEquals(3, plugin.staticResources().size());
        assertEquals("example-download", plugin.i18n().get(0).namespace());
        assertEquals(java.util.List.of("settings-card", "quick-actions-mine"),
                plugin.uiSlots().stream().map(slot -> slot.target()).toList());
        assertEquals("example-download.ids",
                plugin.scheduledSourceDescriptors().get(0).sourceType());
    }

    private Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            properties.load(input);
        }
        return properties;
    }
}
