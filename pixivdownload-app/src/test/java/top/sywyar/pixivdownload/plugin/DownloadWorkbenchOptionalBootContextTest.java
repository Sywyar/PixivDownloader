package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties = {
        "database.provider=database-sqlite",
        "setup.browser.auto-open=false",
        "update.enabled=false",
        "plugin-catalog.enabled=false"
})
@ContextConfiguration(initializers = DownloadWorkbenchOptionalBootContextTest.Initializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("下载工作台缺失时，宿主与已有作品入口仍可使用")
class DownloadWorkbenchOptionalBootContextTest {
    @Autowired private RecoveryModeService recovery;
    @Autowired private RequiredPluginPolicy required;
    @Autowired private RouteAccessRegistry routes;
    @Autowired private NavigationRegistry navigation;
    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private top.sywyar.pixivdownload.setup.SetupService setup;
    @Autowired private top.sywyar.pixivdownload.schedule.ScheduleHostLifecycle scheduleHost;

    @Test
    @DisplayName("工作台缺席时通用管理和独立导入脚本可用，鉴权仍生效")
    void hostHttpServicesRemainAvailableWithoutWorkbench() throws Exception {
        if (!setup.isSetupComplete()) setup.init("host-test", "fixture-password", "solo");
        var cookie = new jakarta.servlet.http.Cookie("pixiv_session", setup.createSession(false));
        var status = org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
        for (String path : List.of("/api/scripts", "/api/download/tasks", "/api/download/extensions",
                "/api/schedule/sources", "/api/schedule/tasks")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(status.isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).cookie(cookie))
                    .andExpect(status.isOk());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/scripts/pixiv-batch-downloader-import.user.js").cookie(cookie))
                .andExpect(status.isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("// ==UserScript==")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/scripts/experience-toolbox.user.js").cookie(cookie))
                .andExpect(status.isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/download/status"))
                .andExpect(status.isOk());
        String tokenResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/pixiv-batch-downloader-import/token")
                        .header("Host", "localhost").header("X-Pixiv-Collector", "1").cookie(cookie))
                .andExpect(status.isOk()).andReturn().getResponse().getContentAsString();
        String importToken = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tokenResponse)
                .path("token").asText();
        assertThat(importToken).isNotBlank();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/pixiv-batch-downloader-import")
                        .header("Host", "localhost").header("Origin", "http://localhost")
                        .header("X-Pixiv-Collector", "1").header("X-Import-Token", importToken)
                        .cookie(cookie).contentType("application/json").content("{}"))
                .andExpect(status.isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("SOURCE_ROOT_REQUIRED"));
        assertThat(scheduleHost.isRunning()).isTrue();
        for (String path : List.of("/api/scripts", "/api/download/tasks", "/api/download/extensions",
                "/api/schedule/sources", "/api/download/status", "/api/pixiv/me/uid",
                "/api/pixiv/thumbnail-proxy", "/api/pixiv/user/100/meta")) {
            assertThat(routes.resolve(path, HttpMethod.GET).orElseThrow().pluginId()).isEqualTo("core");
        }
    }


    @Test
    @DisplayName("不进入恢复模式，管理员首页由宿主提供")
    void hostRemainsAvailable() {
        assertThat(required.isRequired("download-workbench")).isFalse();
        assertThat(recovery.isActive()).isFalse();
        var home = routes.resolve("/home.html", HttpMethod.GET).orElseThrow();
        assertThat(home.pluginId()).isEqualTo("core");
        assertThat(home.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN);
        assertThat(routes.isDeclared("/home/home.js", HttpMethod.GET)).isTrue();
    }

    @Test
    @DisplayName("工作台路由和导航缺席，画廊与小说入口仍存在")
    void onlyWorkbenchContributionsAreAbsent() {
        assertThat(routes.isDeclared("/pixiv-batch.html", HttpMethod.GET)).isFalse();
        assertThat(routes.isDeclared("/api/download/status/active", HttpMethod.GET)).isFalse();
        assertThat(routes.isDeclared("/pixiv-gallery.html", HttpMethod.GET)).isTrue();
        assertThat(routes.isDeclared("/pixiv-novel-gallery.html", HttpMethod.GET)).isTrue();
        assertThat(navigation.navigation()).extracting(item -> item.pluginId())
                .contains("core", "gallery", "novel").doesNotContain("download-workbench");
    }

    @AfterAll
    static void clearRuntimePaths() {
        for (String key : List.of(RuntimeFiles.CONFIG_DIR_PROPERTY, RuntimeFiles.STATE_DIR_PROPERTY,
                RuntimeFiles.DATA_DIR_PROPERTY, RuntimeFiles.PLUGINS_DIR_PROPERTY)) {
            System.clearProperty(key);
        }
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                boolean disabled = !context.getEnvironment()
                        .getProperty("plugins.download-workbench.enabled", Boolean.class, true);
                Path parent = Files.createDirectories(Path.of("target/test-runtime"));
                Path root = Files.createTempDirectory(parent, "optional-workbench-").toAbsolutePath();
                var properties = new LinkedHashMap<String, Object>();
                for (var entry : java.util.Map.of(
                        RuntimeFiles.CONFIG_DIR_PROPERTY, "config",
                        RuntimeFiles.STATE_DIR_PROPERTY, "state",
                        RuntimeFiles.DATA_DIR_PROPERTY, "data",
                        RuntimeFiles.PLUGINS_DIR_PROPERTY, "plugins").entrySet()) {
                    String value = root.resolve(entry.getValue()).toString();
                    System.setProperty(entry.getKey(), value);
                    properties.put(entry.getKey(), value);
                }
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-runtime", properties));
                Path plugins = RuntimeFiles.pluginsDirectory();
                PluginTestProvenance.stageSqliteProvider(plugins);
                for (String id : disabled ? List.of("gallery", "novel", "pixiv-batch-downloader-import", "download-workbench") : List.of("gallery", "novel", "pixiv-batch-downloader-import")) {
                    Path classes = Path.of(java.util.Objects.requireNonNull(System.getProperty(id + ".plugin.classes")));
                    if (!Files.isRegularFile(classes.resolve("plugin.properties"))) {
                        throw new IOException("Current plugin classes are missing: " + id);
                    }
                    Path artifact = plugins.resolve(id + ".jar");
                    PluginTestArtifacts.zipDirectoryAsJar(classes, artifact);
                    PluginTestProvenance.writeVerifiedLocalUpload(plugins, artifact);
                }
                PluginTestProvenance.registerBootstrapSession(context,
                        disabled ? PluginEnabledSnapshot.ofDisabled(List.of("download-workbench"), List.of())
                                : PluginEnabledSnapshot.empty(),
                        PluginTestProvenance.verifier());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
