package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.MaintenanceTaskCapabilityAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("维护任务可信 publication 边界守卫")
class MaintenanceTaskPublicationBoundaryTest {

    @Test
    @DisplayName("功能插件接口不再暴露维护任务 Class 自声明面")
    void featurePluginDoesNotSelfDeclareMaintenanceTaskClasses() {
        assertThat(Arrays.stream(PixivFeaturePlugin.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("maintenanceTasks");
    }

    @Test
    @DisplayName("协调器不再依赖插件注册表做构造期类型过滤")
    void coordinatorDoesNotDependOnPluginRegistry() {
        assertThat(Arrays.stream(MaintenanceCoordinator.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName))
                .doesNotContain("top.sywyar.pixivdownload.plugin.registry.PluginRegistry");
    }

    @Test
    @DisplayName("外置维护任务只经统一运行期 capability adapter 发布")
    void maintenanceAdapterUsesExactRuntimeCapabilityBoundary() {
        assertThat(ExternalRuntimeCapabilityAdapter.class)
                .isAssignableFrom(MaintenanceTaskCapabilityAdapter.class);
    }

    @Test
    @DisplayName("宿主状态页与 GUI 文案不硬编码外置维护任务 id")
    void hostGuiDoesNotKnowExternalMaintenanceTaskIds() throws IOException {
        Path appRoot = appRoot();
        List<Path> hostFiles = List.of(
                appRoot.resolve("src/main/java/top/sywyar/pixivdownload/gui/panel/StatusPanel.java"),
                appRoot.resolve("src/main/resources/i18n/gui.properties"),
                appRoot.resolve("src/main/resources/i18n/gui_en.properties"));

        for (Path hostFile : hostFiles) {
            assertThat(Files.readString(hostFile, StandardCharsets.UTF_8))
                    .as("宿主文件不得认识 duplicate 插件私有维护任务：%s", hostFile)
                    .doesNotContain("duplicate-hash-backfill");
        }
    }

    @Test
    @DisplayName("宿主消息资源只保留核心 Hash 文案，不拥有 duplicate 私有错误与任务日志")
    void hostMessagesDoNotOwnDuplicatePrivateMessages() throws IOException {
        Path appRoot = appRoot();
        List<Path> hostBundles = List.of(
                appRoot.resolve("src/main/resources/i18n/messages.properties"),
                appRoot.resolve("src/main/resources/i18n/messages_en.properties"));

        for (Path hostBundle : hostBundles) {
            String content = Files.readString(hostBundle, StandardCharsets.UTF_8);
            assertThat(content)
                    .as("宿主消息资源不得拥有 duplicate 插件私有文案：%s", hostBundle)
                    .doesNotContain("duplicate.error.", "duplicate.log.scan.", "duplicate.log.backfill.",
                            "duplicate.log.hash.")
                    .contains("core.hash.log.artwork-failed=", "core.hash.log.source-missing=",
                            "core.hash.log.decode-failed=", "core.hash.log.page-failed=");
        }

        Path hashService = appRoot.resolve(
                "src/main/java/top/sywyar/pixivdownload/core/hash/ArtworkHashService.java");
        assertThat(Files.readString(hashService, StandardCharsets.UTF_8))
                .doesNotContain("duplicate.log.hash.")
                .contains("core.hash.log.artwork-failed", "core.hash.log.source-missing",
                        "core.hash.log.decode-failed", "core.hash.log.page-failed");

        Path downloadExecutor = appRoot.resolveSibling("pixivdownload-plugin-download-workbench")
                .resolve("src/main/java/top/sywyar/pixivdownload/download/ArtworkDownloadExecutor.java");
        assertThat(Files.readString(downloadExecutor, StandardCharsets.UTF_8))
                .doesNotContain("duplicate.log.hash.")
                .contains("core.hash.log.artwork-failed");
    }

    private static Path appRoot() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("pixivdownload-app".equals(workingDirectory.getFileName().toString())) {
            return workingDirectory;
        }
        return workingDirectory.resolve("pixivdownload-app");
    }
}
