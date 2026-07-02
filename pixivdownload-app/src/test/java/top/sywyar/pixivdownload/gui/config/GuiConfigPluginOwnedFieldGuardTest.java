package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置插件 owned 字段守卫")
class GuiConfigPluginOwnedFieldGuardTest {

    private static final List<Pattern> FORBIDDEN_PLUGIN_OWNED_TOKENS = List.of(
            Pattern.compile("NotificationConfigSection"),
            Pattern.compile("AiConfigSection"),
            Pattern.compile("notification\\.scenario"),
            Pattern.compile("\\bmail\\."),
            Pattern.compile("\\bpush\\."),
            Pattern.compile("\\bai\\."),
            Pattern.compile("\\bnarration-tts\\."),
            Pattern.compile("mail-test"),
            Pattern.compile("push-test"),
            Pattern.compile("ai-test"),
            Pattern.compile("tts-test"),
            Pattern.compile("ai\\.preset\\.name"),
            Pattern.compile("gui\\.config\\.ai\\.preset"));

    @Test
    @DisplayName("宿主 GUI 配置核心不硬编码插件 owned 字段或测试端点")
    void hostGuiConfigCoreDoesNotHardcodeNotificationPluginFields() throws IOException {
        List<Path> roots = List.of(
                appRoot().resolve("src/main/java/top/sywyar/pixivdownload/gui/config"),
                appRoot().resolve("src/main/java/top/sywyar/pixivdownload/gui/panel/configtab"),
                appRoot().resolve("src/main/java/top/sywyar/pixivdownload/gui/panel/ConfigPanel.java"));
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                collectViolations(root, violations);
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> files = paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList();
                for (Path file : files) {
                    collectViolations(file, violations);
                }
            }
        }

        assertThat(violations)
                .as("插件 owned 字段、preset 和测试端点只能由各插件 GUI contribution 声明")
                .isEmpty();
    }

    private static void collectViolations(Path file, List<String> violations) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (Pattern pattern : FORBIDDEN_PLUGIN_OWNED_TOKENS) {
            if (pattern.matcher(content).find()) {
                violations.add(appRoot().relativize(file) + " contains " + pattern.pattern());
            }
        }
    }

    private static Path appRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("pixivdownload-app".equals(cwd.getFileName().toString())) {
            return cwd;
        }
        return cwd.resolve("pixivdownload-app");
    }
}
