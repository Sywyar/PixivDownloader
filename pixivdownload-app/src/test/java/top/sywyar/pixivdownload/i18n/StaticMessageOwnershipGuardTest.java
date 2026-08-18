package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主静态文案归属")
class StaticMessageOwnershipGuardTest {
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "(?:message|MessageBundles\\.get)\\(\\s*\"([^\"]+)\"");
    private static final Pattern SWING_HOST_MESSAGE_CALL = Pattern.compile(
            "(?:SwingHost\\.host\\(\\)\\.message|logMessage)\\(\\s*\"([^\"]+)\"");
    private static final List<String> SOURCES = List.of(
            "src/main/java/top/sywyar/pixivdownload/gui/GuiLauncher.java",
            "src/main/java/top/sywyar/pixivdownload/ffmpeg/FfmpegInstaller.java");

    @Test
    @DisplayName("启动器与 FFmpeg 安装器的字面量 key 都由宿主 messages bundle 提供")
    void hostOwnedMessageKeysExistInHostBundle() throws IOException {
        Properties messages = new Properties();
        try (var reader = Files.newBufferedReader(
                Path.of("src/main/resources/i18n/messages.properties"), StandardCharsets.UTF_8)) {
            messages.load(reader);
        }

        int calls = 0;
        for (String source : SOURCES) {
            Matcher matcher = MESSAGE_CALL.matcher(Files.readString(Path.of(source), StandardCharsets.UTF_8));
            while (matcher.find()) {
                calls++;
                assertThat(messages).as(source + " 缺少文案 key: " + matcher.group(1))
                        .containsKey(matcher.group(1));
            }
        }
        assertThat(calls).isPositive();
    }

    @Test
    @DisplayName("Swing 经宿主解析的字面量 key 都由宿主 messages bundle 提供")
    void swingHostMessageKeysExistInHostBundle() throws IOException {
        Properties messages = new Properties();
        try (var reader = Files.newBufferedReader(
                Path.of("src/main/resources/i18n/messages.properties"), StandardCharsets.UTF_8)) {
            messages.load(reader);
        }

        int calls = 0;
        Path swingSources = Path.of("..", "pixivdownload-plugin-gui-swing", "src", "main", "java");
        try (var sources = Files.walk(swingSources)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = SWING_HOST_MESSAGE_CALL.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    calls++;
                    assertThat(messages).as(source + " 缺少宿主文案 key: " + matcher.group(1))
                            .containsKey(matcher.group(1));
                }
            }
        }
        assertThat(calls).isPositive();
    }
}
