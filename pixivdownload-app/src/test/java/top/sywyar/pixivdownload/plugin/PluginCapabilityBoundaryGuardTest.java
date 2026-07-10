package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime capability 边界守卫：生命周期核心只认识中性 adapter 契约，
 * 不把具体能力类型列表写回中心注册器。
 */
@DisplayName("运行期能力边界守卫")
class PluginCapabilityBoundaryGuardTest {

    private static final List<String> FORBIDDEN_CENTRAL_TOKENS = List.of(
            "NotificationSink",
            "NotificationSinkRegistry",
            "PushChannel",
            "PushChannelRegistry",
            "AiChatClient",
            "AiChatClientRegistry",
            "NarrationVoiceEngine",
            "NarrationEngineRegistry",
            "GalleryProjectionProvider",
            "GalleryWorkProvider",
            "GalleryFrontendProvider",
            "GalleryFrontendContribution",
            "GalleryCapabilityRegistry");

    @Test
    @DisplayName("生命周期核心不得硬编码具体能力类型或注册中心")
    void lifecycleCoreDoesNotHardcodeConcreteCapabilityTypes() throws IOException {
        Path root = sourceRoot().resolve("top/sywyar/pixivdownload/plugin/lifecycle");
        try (var paths = Files.walk(root)) {
            List<Path> offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/capability/"))
                    .filter(PluginCapabilityBoundaryGuardTest::containsForbiddenToken)
                    .toList();

            assertThat(offenders)
                    .as("具体能力只能由 plugin.lifecycle.capability.* adapter 封装；"
                            + "PluginCapabilityContributionRegistrar / PluginLifecycleService 等中心代码只能依赖中性 adapter")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("中心注册器只依赖中性能力适配器列表")
    void registrarUsesNeutralAdapterList() throws IOException {
        Path registrar = sourceRoot().resolve(
                "top/sywyar/pixivdownload/plugin/lifecycle/PluginCapabilityContributionRegistrar.java");
        String source = Files.readString(registrar, StandardCharsets.UTF_8);

        assertThat(source).contains("List<PluginCapabilityContributionAdapter<?>>");
        for (String token : FORBIDDEN_CENTRAL_TOKENS) {
            assertThat(source).doesNotContain(token);
        }
    }

    private static boolean containsForbiddenToken(Path path) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
        return FORBIDDEN_CENTRAL_TOKENS.stream().anyMatch(source::contains);
    }

    private static Path sourceRoot() {
        Path root = Path.of("pixivdownload-app/src/main/java");
        if (Files.exists(root)) {
            return root;
        }
        return Path.of("src/main/java");
    }
}
