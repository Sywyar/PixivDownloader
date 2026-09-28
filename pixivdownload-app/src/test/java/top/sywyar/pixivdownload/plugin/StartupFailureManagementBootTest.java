package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.schedule.*;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeGate;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.repairprobe.StartupRepairProbePlugin;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"setup.browser.auto-open=false", "update.auto-check=false"})
@ContextConfiguration(initializers = StartupFailureManagementBootTest.Bootstrap.class)
@Import(StartupFailureManagementBootTest.Policy.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestExecutionListeners(listeners = StartupFailureManagementBootTest.Cleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@DisplayName("真实启动失败包经核心管理修复，保留插件数据与未完成计划")
class StartupFailureManagementBootTest {
    private static final String REQUIRED = "repair-required", OPTIONAL = "repair-optional";
    private static final Map<String, String> originalProperties = new LinkedHashMap<>();
    private static Path home;
    @Autowired WebApplicationContext context;
    @Autowired PluginRuntimeManager runtime;
    @Autowired PluginManagementService management;
    @Autowired RecoveryModeService recovery;
    @Autowired ScheduledTaskStore tasks;

    @TestConfiguration
    static class Policy {
        @Bean @Primary
        RequiredPluginPolicy repairPolicy() {
            return RequiredPluginPolicy.of(List.of(new RequiredPluginPolicy.RequiredPlugin(
                    REQUIRED, VersionRequirement.unspecified(), false, "plugin.recovery.blocked")));
        }
    }

    public static class Bootstrap implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                home = Files.createTempDirectory("pixiv-startup-repair-");
                for (String kind : List.of("config", "state", "data", "plugins")) {
                    String key = "pixivdownload." + kind + "-dir";
                    originalProperties.put(key, System.getProperty(key));
                    String value = home.resolve(kind).toString();
                    System.setProperty(key, value);
                    TestPropertyValues.of(key + "=" + value).applyTo(context);
                    Files.createDirectories(Path.of(value));
                }
                for (String id : List.of(REQUIRED, OPTIONAL)) {
                    Path artifact = packageFile(home.resolve("plugins"), id, "7.2.0");
                    PluginTestProvenance.writeVerifiedLocalUpload(home.resolve("plugins"), artifact);
                    Path data = home.resolve("data/" + id + "/saved.txt");
                    Files.createDirectories(data.getParent());
                    Files.writeString(data, "retained plugin data", StandardCharsets.UTF_8);
                }
                PluginTestProvenance.registerBootstrapSession(context, PluginEnabledSnapshot.empty(), PluginTestProvenance.verifier());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }

    @Test
    @DisplayName("必选失败仍禁止移除且可换包，可选失败可移除，任务及数据始终保留")
    void repairThroughManagementAfterRealStartupFailure() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RecoveryModeGate.class)).build();
        assertThat(runtime.status().orElseThrow().failures())
                .extracting(top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure::source).contains(REQUIRED, OPTIONAL);
        assertThat(management.list().plugins()).filteredOn(entry -> List.of(REQUIRED, OPTIONAL).contains(entry.id()))
                .allSatisfy(entry -> assertThat(entry.status()).isEqualTo(PluginStatus.FAILED));
        assertThat(recovery.isActive()).isTrue();
        assertThat(management.list().plugins()).filteredOn(entry -> entry.id().equals(OPTIONAL) && entry.version() != null)
                .singleElement().satisfies(entry -> assertThat(entry.availableActions()).contains("remove"));
        mvc.perform(get("/api/plugins/status")).andExpect(status().isOk());
        long task = tasks.create(new ScheduledTaskCreate("retained", "fixture-source", OPTIONAL, "fixture.definition", 1,
                "{}", "{}", ScheduledTask.TRIGGER_INTERVAL, 60, null, 1000L, System.currentTimeMillis()));
        var pending = new ScheduledPendingWork(task, "fixture", "unfinished", "fixture.payload", 1, "{}", "[]", "{}",
                "retry", "{}", 0, 2000L, 2000L);
        tasks.upsertPendingWork(pending);
        var originalTask = tasks.findById(task);
        mvc.perform(post("/api/plugins/" + REQUIRED + "/remove"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUIRED_PLUGIN"));
        mvc.perform(post("/api/plugins/" + OPTIONAL + "/remove"))
                .andExpect(status().isOk());
        assertThat(runtime.packagePhases()).doesNotContainKey(OPTIONAL);
        assertThat(home.resolve("plugins/" + OPTIONAL + "-7.2.0.jar")).doesNotExist();

        Path replacement = packageFile(home.resolve("candidate"), REQUIRED, "8.0.0");
        PluginTestProvenance.writeVerifiedLocalUpload(replacement.getParent(), replacement);
        var signature = new top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore(replacement.getParent())
                .read(replacement).orElseThrow().signature();
        mvc.perform(multipart("/api/plugins/install").file(new MockMultipartFile("file", replacement.getFileName().toString(),
                        "application/java-archive", Files.readAllBytes(replacement)))
                        .file(new MockMultipartFile("signature", "signature.json", "application/json",
                                context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsBytes(signature)))
                        .param("confirmTrust", PluginPackageIntegrity.sha256Hex(replacement)))
                .andDo(result -> assertThat(result.getResponse().getStatus())
                        .as("%s; %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                                context.getBean(top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller.class).recoveryGateSnapshot()).isEqualTo(200))
                .andExpect(jsonPath("$.activated").value(true));
        assertThat(runtime.loadedDescriptor(REQUIRED).orElseThrow().version()).isEqualTo("8.0.0");
        assertThat(recovery.isActive()).isFalse();
        assertThat(runtime.status().orElseThrow().failures()).isEmpty();
        assertThat(tasks.findById(task)).isEqualTo(originalTask);
        assertThat(tasks.listPendingWork(task)).containsExactly(pending);
        for (String id : List.of(REQUIRED, OPTIONAL)) {
            assertThat(Files.readString(home.resolve("data/" + id + "/saved.txt"), StandardCharsets.UTF_8))
                    .isEqualTo("retained plugin data");
        }
    }

    private static Path packageFile(Path directory, String id, String version) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(id + "-" + version + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=" + id + "\nplugin.version=" + version + "\nplugin.requires=" + SdkVersion.MAJOR + "." + SdkVersion.MINOR
                    + "\npixiv.lifecycle-policy=hot-reload\npixiv.execution-mode=host-process-full-trust\nplugin.class="
                    + StartupRepairProbePlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Class<?> type : List.of(StartupRepairProbePlugin.class, StartupRepairProbePlugin.Feature.class)) {
                String entry = type.getName().replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(entry));
                try (var input = type.getResourceAsStream("/" + entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
        return file;
    }

    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 0; }
        @Override public void afterTestClass(TestContext ignored) throws Exception {
            originalProperties.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
            if (home != null) org.springframework.util.FileSystemUtils.deleteRecursively(home);
        }
    }
}
