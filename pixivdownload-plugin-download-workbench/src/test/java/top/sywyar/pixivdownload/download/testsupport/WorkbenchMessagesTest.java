package top.sywyar.pixivdownload.download.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.download.web.WorkbenchErrorResponses;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载工作台后端消息资源")
class WorkbenchMessagesTest {

    @Test
    @DisplayName("中英文资源键集合完全一致")
    void localizedBundlesExposeTheSameKeys() throws IOException {
        Properties chinese = load("i18n/workbench/messages.properties");
        Properties english = load("i18n/workbench/messages_en.properties");

        assertThat(chinese).isNotEmpty();
        assertThat(english.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(chinese.stringPropertyNames());
    }

    @Test
    @DisplayName("插件私有异常按请求语言投影且不依赖宿主消息实现")
    void localizedFailureUsesPluginOwnedEnglishMessage() {
        LocalizedException failure = LocalizedException.badRequest(
                "schedule.error.cookie-empty",
                "Cookie 无效或为空");

        var response = WorkbenchErrorResponses.localized(
                failure,
                WorkbenchTestMessages.messages(),
                Locale.US);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("The Cookie is invalid or empty");
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (var stream = WorkbenchMessagesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as(name).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
