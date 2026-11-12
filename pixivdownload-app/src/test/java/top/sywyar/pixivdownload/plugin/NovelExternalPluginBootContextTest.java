package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static top.sywyar.pixivdownload.plugin.PluginTestArtifacts.zipDirectoryAsJar;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/translation-boot/config",
        "pixivdownload.state-dir=target/test-runtime/translation-boot/state",
        "pixivdownload.data-dir=target/test-runtime/translation-boot/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-novel",
        "download.novel-max-concurrent=3",
        "download.root-folder=target/test-runtime/translation-boot/downloads",
        "setup.browser.auto-open=false"
})
@ContextConfiguration(initializers = PluginTestProvenance.VerifiedLocalSqliteBootstrapInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalNovelJarStaged")
@DisplayName("外置 novel 插件经真实上下文接入：EXTERNAL 来源 + 小说下载 / 展示贡献 classloader-aware 可见")
class NovelExternalPluginBootContextTest {

    private static final String NOVEL_CLASSES_PROPERTY = "novel.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-novel");
    private static HttpServer translationServer;
    private static final AtomicInteger translationRequests = new AtomicInteger();
    private static final boolean STAGED = stageExternalNovelJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/translation-boot/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/translation-boot/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/translation-boot/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalNovelJarStaged() {
        return STAGED;
    }

    @Autowired
    private PluginRuntimeManager pluginRuntimeManager;
    @Autowired
    private PluginRuntimeStatus pluginRuntimeStatus;
    @Autowired
    private PluginDiscoveryResult pluginDiscoveryResult;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private LandingRegistry landingRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private ScheduleCapabilityRegistry scheduleCapabilityRegistry;

    @AfterAll
    void releasePluginsAndCleanup() {
        if (pluginRuntimeManager != null) {
            pluginRuntimeManager.shutdown();
        }
        deleteRecursivelyQuietly(PLUGINS_DIR);
        if (translationServer != null) translationServer.stop(0);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 novel、无失败、发现 novel")
    void runtimeStatusAndDiscoveryBeansSeeNovel() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("novel");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("novel");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：内置 + 外置 novel，novel 来源 EXTERNAL")
    void pluginRegistryBeanContainsNovelAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder("core", "plugin-market", "database-sqlite", "novel", "novel-translation-contract", "translation");
        assertThat(pluginRegistry.source("novel")).contains(PluginSource.EXTERNAL);
        assertThat(externalNovelClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("novel 的路由 / 静态资源 / i18n 经外置插件 classloader 注册")
    void novelWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalNovelClassLoader();

        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("novel"))
                .extracting(r -> r.route().pathPattern())
                .contains(
                        "/api/novel/download",
                        "/api/pixiv/novel/*/meta",
                        "/api/pixiv/novel/*/bookmark-count",
                        "/api/pixiv/novel/series/*",
                        "/api/pixiv/novel-search**",
                        "/api/download/pixiv/novel",
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/**",
                        "/pixiv-novel/**",
                        "/api/gallery/novel/**",
                        "/api/gallery/novels/**",
                        "/api/gallery/novels");
        assertThat(staticResourceRegistry.resources())
                .filteredOn(s -> s.pluginId().equals("novel"))
                .allSatisfy(s -> assertThat(s.classLoader()).isSameAs(externalCl))
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-novel-download/",
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/",
                        "/pixiv-novel/");

        WebI18nBundleRegistry.RegisteredBundle novelBundle = webI18nBundleRegistry.resolve("novel");
        assertThat(novelBundle).isNotNull();
        assertThat(novelBundle.pluginId()).isEqualTo("novel");
        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("novel-gallery");
        assertThat(bundle).isNotNull();
        assertThat(bundle.pluginId()).isEqualTo("novel");
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "小说");

        assertThat(externalCl.getResource("static/pixiv-novel-download/novel-queue-type.js")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel-gallery/novel-gallery-frontend.js")).isNull();
        assertThat(externalCl.getResource("static/pixiv-novel-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel/pixiv-novel-render.js")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/novel.properties")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/novel-gallery.properties")).isNotNull();
        assertThat(externalCl.getResource("i18n/novel/messages.properties")).isNotNull();
        assertThat(externalCl.getResource("i18n/novel/messages_en.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-novel-gallery.html")).isNull();
        assertThat(getClass().getClassLoader()
                .getResource("static/pixiv-novel-gallery/novel-gallery-frontend.js")).isNull();
        assertThat(getClass().getClassLoader().getResource("i18n/novel/messages.properties")).isNull();
        assertThat(getClass().getClassLoader().getResource("i18n/novel/messages_en.properties")).isNull();
    }

    @Test
    @DisplayName("novel 子上下文的插件文案解析器读取 owner 资源并跟随请求语言")
    void novelPluginMessagesUseOwnerBundleAndRequestLocale() throws IOException {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel").orElseThrow();
        MessageResolver messages = child.getBean("novelPluginMessages", MessageResolver.class);
        Map<String, Object[]> cases = Map.of(
                "novel.epub.chapter", new Object[]{2},
                "novel.series.log.refresh.failed.exception", new Object[]{7},
                "pixiv.proxy.novel.id.invalid", new Object[]{"bad"},
                "novel.translate.success", new Object[]{},
                "narration.error.missing-novel", new Object[]{},
                "narration.tts.text-too-long", new Object[]{100},
                "download.status.completed", new Object[]{1, 2},
                "narration.tts.error.empty-text", new Object[]{});
        var previousLocaleContext = LocaleContextHolder.getLocaleContext();
        try {
            for (var locale : Map.of(Locale.US, "_en", Locale.SIMPLIFIED_CHINESE, "").entrySet()) {
                Properties expectedMessages = new Properties();
                for (String basename : List.of("i18n/messages", "i18n/novel/messages")) {
                    try (var input = child.getClassLoader().getResourceAsStream(
                            basename + locale.getValue() + ".properties")) {
                        assertThat(input).isNotNull();
                        expectedMessages.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                    }
                }
                LocaleContextHolder.setLocale(locale.getKey());
                cases.forEach((key, args) -> {
                    String pattern = expectedMessages.getProperty(key);
                    assertThat(pattern).as(key).isNotBlank();
                    String rendered = messages.get(key, args);
                    assertThat(rendered).as("%s (%s)", key, locale.getKey())
                            .isEqualTo(args.length == 0 ? pattern
                                    : new MessageFormat(pattern, locale.getKey()).format(args));
                    for (Object argument : args) {
                        assertThat(rendered).as(key).contains(argument.toString());
                    }
                });
            }
        } finally {
            LocaleContextHolder.setLocaleContext(previousLocaleContext);
        }
    }

    @Test
    @DisplayName("novel 导航 / 落点贡献随外置插件注册")
    void novelNavigationAndEntryContributionsAreRegistered() {
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("novel"))
                .extracting(nav -> nav.navigation().id())
                .containsExactlyInAnyOrder("novel-gallery", "novel-type-switch");
        assertThat(landingRegistry.landings())
                .filteredOn(landing -> landing.pluginId().equals("novel"))
                .singleElement()
                .satisfies(landing -> assertThat(landing.landing().href()).isEqualTo("/pixiv-novel-gallery.html"));
    }

    @Test
    @DisplayName("novel 只发布 plugin-api 当前计划作品执行器")
    void novelPublishesOnlyCurrentScheduledWorkExecutor() {
        assertThat(scheduleCapabilityRegistry.resolveWorkExecutor("novel"))
                .get()
                .satisfies(handle -> assertThat(handle.owner().featurePluginId()).isEqualTo("novel"));
        assertThatThrownBy(() -> externalNovelClassLoader().loadClass(
                "top.sywyar.pixivdownload.novel.download.ScheduledNovelDownloadDelegate"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("novel 子 ApplicationContext 托管下载和展示 service/controller，controller 动态注册进父分发表")
    void externalNovelChildContextHostsBeansAndControllerMapping() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel").orElseThrow();
        ClassLoader externalCl = externalNovelClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> downloadServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.novel.download.NovelDownloadService");
        Class<?> downloadControllerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novel.controller.NovelDownloadController");
        Class<?> serviceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelGalleryService");
        Class<?> batchServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelBatchService");
        Class<?> controllerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController");
        Class<?> executionSettingsClass = externalCl.loadClass(
                "top.sywyar.pixivdownload.novel.config.NovelExecutionSettings");

        assertThat(child.getBeanNamesForType(downloadServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(downloadControllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(serviceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(batchServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(controllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(executionSettingsClass)).isNotEmpty();
        assertThat(child.getBean("novelDownloadTaskExecutor"))
                .isInstanceOfSatisfying(ThreadPoolTaskExecutor.class,
                        executor -> assertThat(executor.getMaxPoolSize()).isEqualTo(3));
        assertThat(child.containsLocalBean("novelTranslateTaskExecutor")).isFalse();
        assertThat(child.containsLocalBean("novelStatusTaskScheduler")).isTrue();
        assertThat(child.getBean("novelStatusTaskScheduler"))
                .isInstanceOfSatisfying(ThreadPoolTaskScheduler.class, scheduler -> {
                    assertThat(scheduler).isNotSameAs(applicationContext.getBean("taskScheduler"));
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                            .isEqualTo(1);
                    assertThat(scheduler.getThreadNamePrefix())
                            .isEqualTo("novel-status-scheduler-");
                });
        assertThat(applicationContext.getBeanNamesForType(controllerClass)).isEmpty();
        assertThat(applicationContext.containsBean("novelDownloadTaskExecutor")).isFalse();
        assertThat(applicationContext.containsBean("novelTranslateTaskExecutor")).isFalse();
        assertThat(applicationContext.containsBean("novelStatusTaskScheduler")).isFalse();
        assertThat(novelGalleryListHandlerBean()).isSameAs(child.getBean(controllerClass));
    }

    @Test
    @DisplayName("novel 子 ApplicationContext 中的事务 Bean 被 CGLIB 代理")
    void externalNovelTransactionalBeansAreProxied() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel").orElseThrow();
        ClassLoader externalCl = externalNovelClassLoader();

        assertTransactionalProxy(child, externalCl, "top.sywyar.pixivdownload.novel.db.NovelDatabase");
        assertTransactionalProxy(child, externalCl,
                "top.sywyar.pixivdownload.novel.translation.NovelGlossaryService");
        assertTransactionalProxy(child, externalCl,
                "top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"sort\":null}"})
    @DisplayName("小说批量筛选省略排序或显式空排序时，经真实插件请求链返回导出选项")
    void batchExportOptionsAcceptsMissingSort(String filter) throws Exception {
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(applicationContext).build();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/gallery/novels/export/options").contentType("application/json")
                        .content("{\"mode\":\"filter\",\"filter\":" + filter + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.variants").isArray());
    }

    @Test
    @DisplayName("单篇直接下载与批量归档经真实装配读取精确译文，日志不含正文")
    void exportsStoredVariantThroughPlugin() throws Exception {
        var novel = externalPluginContextManager.contextFor("novel").orElseThrow();
        Object database = novel.getBean("novelDatabase");
        var insert = Arrays.stream(database.getClass().getMethods())
                .filter(method -> method.getName().equals("insertNovel") && method.getParameterCount() == 22)
                .findFirst().orElseThrow();
        Path folder = Files.createDirectories(Path.of("target/test-runtime/translation-boot/novel-732"));
        Files.writeString(folder.resolve("732.txt"), "private-original", StandardCharsets.UTF_8);
        insert.invoke(database, 732L, "Export title", folder.toString(), 1, "txt", 1L, 0, false,
                null, "Export description", 732L, null, null, null, 1, 10, 1, 1, false, "ja", "private-original", null);
        database.getClass().getMethod("saveTranslation", long.class, String.class, String.class, String.class, String.class)
                .invoke(database, 732L, "zh-CN@youdao", "private-youdao", "译文", null);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "top.sywyar.pixivdownload.novel.export.NovelExportService");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        var previousLocale = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.JAPANESE);
            var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(applicationContext).build();
            var attachment = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/novel/732/export").param("variant", "zh-CN@youdao"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .contentType("application/epub+zip"))
                    .andReturn().getResponse();
            try (var zip = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(attachment.getContentAsByteArray()))) {
                assertThat(zip.getNextEntry().getName()).isEqualTo("mimetype");
                assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("application/epub+zip");
                boolean chapterFound = false;
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (entry.getName().equals("OEBPS/chapter-1.xhtml")) {
                        assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                                .contains("private-youdao").doesNotContain("private-original");
                        chapterFound = true;
                    }
                }
                assertThat(chapterFound).isTrue();
            }
            try (var files = Files.list(folder)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("732.txt");
            }
            assertThat(routeAccessRegistry.resolve("/api/novel/732/export", top.sywyar.pixivdownload.plugin.api.web.HttpMethod.GET)
                    .orElseThrow().route().accessPolicy())
                    .isEqualTo(top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.ADMIN);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/gallery/novels/export/options").contentType("application/json")
                            .content("{\"ids\":[732]}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.variants[0]").value("zh-CN@youdao"));
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/gallery/novels/export/options").contentType("application/json")
                            .content("""
                                    {"mode":"filter","filter":{"searchType":"id","search":"732"}}
                                    """))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.variants[0]").value("zh-CN@youdao"));
            String response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/gallery/novels/export").contentType("application/json")
                            .content("{\"ids\":[732],\"format\":\"zip\",\"groupBy\":\"id\",\"variant\":\"zh-CN@youdao\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String token = JsonMapper.builderWithJackson2Defaults().build().readTree(response).get("archiveToken").asString();
            var archives = applicationContext.getBean(top.sywyar.pixivdownload.quota.UserQuotaService.class);
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (List.of("pending", "creating").contains(archives.getArchive(token).getStatus())
                        && System.nanoTime() < deadline) Thread.sleep(25);
                var archive = archives.getArchive(token);
                assertThat(archive.getStatus()).isEqualTo("ready");
                try (var zip = new java.util.zip.ZipFile(archive.getArchivePath().toFile())) {
                    var document = zip.stream().filter(entry -> entry.getName().endsWith(".txt")).findFirst().orElseThrow();
                    assertThat(document.getName()).startsWith("732/");
                    try (var input = zip.getInputStream(document)) {
                        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("private-youdao");
                    }
                    assertThat(zip.getEntry("manifest.json")).isNotNull();
                }
                assertThat(Files.readString(folder.resolve("732.txt"), StandardCharsets.UTF_8)).isEqualTo("private-original");
            } finally {
                archives.deleteArchive(token);
            }
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/gallery/novels/export").contentType("application/json")
                            .content("{\"ids\":[732],\"variant\":\"zh-CN@ai\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            var logs = captured.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs).anySatisfy(line -> assertThat(line).contains("Novel export finished", "novelId=732", "bytes=", "durationMs=", "runId="));
            assertThat(logs).allSatisfy(line -> assertThat(line).doesNotContain("private-original", "private-youdao", "Export title", "译文"));
        } finally {
            LocaleContextHolder.setLocale(previousLocale);
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    @DisplayName("无 AI 插件时经独立类加载器翻译；长文分片读写不超过服务单次纯值预算")
    void translationRunsWithoutAiAndTransfersLongDocuments() throws Exception {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("ai");
        var novel = externalPluginContextManager.contextFor("novel").orElseThrow();
        var translation = externalPluginContextManager.contextFor("translation").orElseThrow();
        var novelLoader = novel.getClassLoader();
        var translationLoader = translation.getClassLoader();
        Object boundConfig = translation.getBean("translationConfig");
        Object machineSettings = boundConfig.getClass().getMethod("getLibretranslate").invoke(boundConfig);
        assertThat(machineSettings.getClass().getMethod("getRequestChars").invoke(machineSettings)).isEqualTo(2048);
        assertThat(machineSettings.getClass().getMethod("getBatchSize").invoke(machineSettings)).isEqualTo(4);
        Object youdaoSettings = boundConfig.getClass().getMethod("getYoudao").invoke(boundConfig);
        assertThat(youdaoSettings.getClass().getMethod("getRequestIntervalMs").invoke(youdaoSettings)).isEqualTo(900);
        assertThat(boundConfig.getClass().getMethod("getAiSegmentSize").invoke(boundConfig)).isEqualTo(1728);
        Object client = translation.getBean("machineTranslationClient");
        assertThat(client.getClass().getMethod("translate", String.class, String.class, String.class, int.class)
                .invoke(client, "libretranslate", "one\ntwo\nthree", "en", 0))
                .isEqualTo("translated:one\ntranslated:two\ntranslated:three");
        String api = "top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments";
        assertThat(novelLoader.loadClass(api)).isSameAs(translationLoader.loadClass(api));
        assertThat(novelLoader.loadClass(api).getClassLoader()).isNotSameAs(novelLoader);
        String content = "正文😀".repeat(150_000);
        Object database = novel.getBean(novelLoader.loadClass("top.sywyar.pixivdownload.novel.db.NovelDatabase"));
        var insert = Arrays.stream(database.getClass().getMethods())
                .filter(method -> method.getName().equals("insertNovel") && method.getParameterCount() == 22)
                .findFirst().orElseThrow();
        insert.invoke(database, 731L, "題名", "novel-translation-test", 1, "txt", 1L, 0, false,
                null, "紹介", 731L, null, null, null, 1, content.length(), 1, 1, false, "ja", content, null);
        Object documents = translation.getBean("translationDocuments");
        Object source = documents.getClass().getMethod("getNovel", long.class).invoke(documents, 731L);
        assertThat(source.getClass().getMethod("rawContent").invoke(source)).isEqualTo(content);
        documents.getClass().getMethod("saveTranslation", long.class, String.class, String.class, String.class, String.class)
                .invoke(documents, 731L, "ko", content, "번역", null);
        assertThat(database.getClass().getMethod("getTranslationContent", long.class, String.class)
                .invoke(database, 731L, "ko@ai")).isEqualTo(content);
        Object legacy = novel.getBean("novelTranslationService");
        Object result = legacy.getClass().getMethod("translateChapter", long.class, String.class, int.class,
                boolean.class, String.class, Long.class, boolean.class, boolean.class, boolean.class)
                .invoke(legacy, 731L, "en", 0, true, null, null, false, true, true);
        assertThat(result.getClass().getMethod("status").invoke(result).toString()).isEqualTo("OK");
        assertThat(result.getClass().getMethod("langCode").invoke(result)).isEqualTo("en-US");
        assertThat(database.getClass().getMethod("getTranslationTitle", long.class, String.class)
                .invoke(database, 731L, "en-US@libretranslate")).isEqualTo("translated:題名");
        assertThat(database.getClass().getMethod("getTranslationDescription", long.class, String.class)
                .invoke(database, 731L, "en-US@libretranslate")).isEqualTo("translated:紹介");
        assertThat(translationRequests.get()).isGreaterThanOrEqualTo(2);
        Class<?> publicationType = translationLoader.loadClass(api + "$Publication");
        Class<?> digestType = translationLoader.loadClass("top.sywyar.pixivdownload.translation.api.TranslationDigest");
        var digestMethod = digestType.getMethod("of", String[].class);
        String emptyDigest = (String) digestMethod.invoke(null, (Object) new String[]{null, null, null});
        String outputDigest = (String) digestMethod.invoke(null, (Object) new String[]{"replacement", null, null});
        Object publication = publicationType.getConstructors()[0].newInstance(java.util.UUID.randomUUID(),
                emptyDigest, emptyDigest, outputDigest);
        Class<?> kindType = translationLoader.loadClass(api + "$Kind");
        Object novelKind = java.util.Arrays.stream(kindType.getEnumConstants())
                .filter(value -> value.toString().equals("NOVEL")).findFirst().orElseThrow();
        assertThatThrownBy(() -> documents.getClass().getMethod("publish", kindType, long.class, String.class,
                String.class, String.class, String.class, publicationType).invoke(documents, novelKind, 731L, "en",
                "replacement", null, null, publication))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("translation.source-changed");
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(applicationContext).build();
        String jobId = java.util.UUID.randomUUID().toString();
        String jobBody = "{\"requestId\":\"" + jobId + "\",\"request\":{\"novelId\":731,\"targetLanguage\":\"en\",\"translateTitle\":true,\"overwrite\":true}}";
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/translation/jobs")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(jobBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/api/translation/jobs/" + jobId))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(jobId));
        }
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/translation/jobs/" + jobId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.engine").value("libretranslate"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/translation/jobs")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(jobBody.replace("\"en\"", "\"ja\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/gallery/novel/731/content")
                        .param("variant", "en-US@libretranslate"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.translated").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.translatedTitle").value("translated:題名"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/gallery/novel/731/content")
                        .param("variant", "en-US@youdao"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.translated").value(false));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/translation/novel")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"novelId\":731,\"targetLanguage\":\"en\",\"translateTitle\":true,\"overwrite\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.variantKey").value("en-US@libretranslate"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/translation/novel")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"novelId\":-1,\"targetLanguage\":\"en\",\"translateTitle\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("TRANSLATION_INVALID_REQUEST"));
    }

    private static void assertTransactionalProxy(ConfigurableApplicationContext child,
                                                 ClassLoader classLoader,
                                                 String className) throws Exception {
        Class<?> type = classLoader.loadClass(className);
        Object bean = child.getBean(type);
        assertThat(AopUtils.isCglibProxy(bean)).as(className).isTrue();
        assertThat(type.isInstance(bean)).as(className).isTrue();
    }

    private Object novelGalleryListHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/gallery/novels"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalNovelClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("novel")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalNovelJar() {
        try {
            String configured = System.getProperty(NOVEL_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path classes = Path.of(configured);
            if (!Files.isDirectory(classes) || !Files.exists(classes.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("novel-plugin.jar");
            stagePluginArtifact("novel", classes, jar);
            PluginTestProvenance.writeVerifiedLocalUpload(PLUGINS_DIR, jar);
            for (String id : List.of("novel-translation-contract", "translation")) {
                Path dependency = Path.of(System.getProperty(id + ".plugin.classes"));
                Path artifact = PLUGINS_DIR.resolve(id + ".jar");
                stagePluginArtifact(id, dependency, artifact);
                PluginTestProvenance.writeVerifiedLocalUpload(PLUGINS_DIR, artifact);
            }
            translationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            translationServer.createContext("/translate", exchange -> {
                var mapper = JsonMapper.builderWithJackson2Defaults().build();
                var query = mapper.readTree(exchange.getRequestBody()).path("q");
                translationRequests.incrementAndGet();
                Object translated;
                if (query.isArray()) {
                    var values = new java.util.ArrayList<String>();
                    query.forEach(text -> values.add("translated:" + text.asString()));
                    translated = values;
                } else translated = "translated:" + query.asString();
                byte[] response = mapper.writeValueAsBytes(Map.of("translatedText", translated));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            translationServer.start();
            Path config = Path.of("target/test-runtime/translation-boot/config/plugins/translation.properties");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "translation.engine=libretranslate\ntranslation.libretranslate-url=http://127.0.0.1:"
                    + translationServer.getAddress().getPort() + "\ntranslation.libretranslate.request-chars=2048\n"
                    + "translation.libretranslate.batch-size=4\ntranslation.youdao.request-chars=4000\n"
                    + "translation.youdao.request-interval-ms=900\ntranslation.ai-segment-size=1728\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException ex) {
            if (translationServer != null) translationServer.stop(0);
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void stagePluginArtifact(String id, Path classes, Path target) throws IOException {
        String packaged = System.getProperty(id + ".plugin.jar");
        if (packaged == null || packaged.isBlank()) {
            zipDirectoryAsJar(classes, target);
        } else {
            Files.copy(Path.of(packaged), target);
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
