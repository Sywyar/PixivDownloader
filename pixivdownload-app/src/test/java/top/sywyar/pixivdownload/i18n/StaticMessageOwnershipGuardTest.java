package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 字面量调用形态的低成本 lint；locale 完整性由统一 i18n checker 负责。 */
@DisplayName("宿主静态文案归属启发式 lint")
class StaticMessageOwnershipGuardTest {
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "(?:message|MessageBundles\\.get)\\(\\s*\"([^\"]+)\"");
    private static final List<String> SOURCES = List.of(
            "src/main/java/top/sywyar/pixivdownload/gui/GuiLauncher.java",
            "src/main/java/top/sywyar/pixivdownload/ffmpeg/FfmpegInstaller.java");

    @Test
    @DisplayName("启动器与 FFmpeg 安装器的字面量 key 可由宿主解析链解析")
    void hostOwnedMessageKeysExistInHostBundle() throws IOException {
        int calls = 0;
        for (String source : SOURCES) {
            Matcher matcher = MESSAGE_CALL.matcher(Files.readString(Path.of(source), StandardCharsets.UTF_8));
            while (matcher.find()) {
                calls++;
                String key = matcher.group(1);
                assertThat(MessageBundles.get(Locale.ROOT, key))
                        .as(source + " 无法解析文案 key: " + key)
                        .isNotEqualTo(key);
            }
        }
        assertThat(calls).isPositive();
    }

    @Test
    @DisplayName("静态日志文案固定使用英文 fallback")
    void staticLogMessagesAlwaysUseEnglish() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertThat(MessageBundles.getForLog("gui.launcher.log.starting", "[]"))
                    .isEqualTo(MessageBundles.get(Locale.US, "gui.launcher.log.starting", "[]"))
                    .isNotEqualTo(MessageBundles.get(
                            Locale.SIMPLIFIED_CHINESE,
                            "gui.launcher.log.starting",
                            "[]"
                    ));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
