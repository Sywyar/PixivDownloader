package top.sywyar.pixivdownload.gui.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing GUI 文案归属")
class GuiMessageUsageGuardTest {
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "(?:(?<![A-Za-z.])message|GuiMessages\\.get)\\(\\s*\"([^\"]+)\"\\s*(?:,|\\))");

    @Test
    @DisplayName("所有字面量 GUI key 都由 Swing GUI bundle 提供")
    void literalGuiMessageKeysExistInGuiBundle() throws IOException {
        Properties messages = new Properties();
        try (var reader = Files.newBufferedReader(
                Path.of("src/main/resources/i18n/gui.properties"), StandardCharsets.UTF_8)) {
            messages.load(reader);
        }

        int calls = 0;
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = MESSAGE_CALL.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    calls++;
                    assertThat(messages).as(source + " 缺少文案 key: " + matcher.group(1))
                            .containsKey(matcher.group(1));
                }
            }
        }
        assertThat(calls).isPositive();
    }
}
