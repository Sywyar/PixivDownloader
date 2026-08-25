package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("监控页资源接线守卫")
class MonitorPageGuardTest {

    private static final List<String> STYLESHEETS = List.of(
            "/monitor/monitor.css",
            "/monitor/monitor-content.css",
            "/monitor/monitor-overlays.css",
            "/monitor/monitor-responsive.css",
            "/monitor/monitor-navigation.css"
    );

    @Test
    @DisplayName("页面按职责顺序加载自有样式且基础样式支持深色模式")
    void stylesheetsKeepResponsibilityOrderAndDarkModeSupport() throws IOException {
        String html = read("monitor.html");
        int previous = -1;
        for (String stylesheet : STYLESHEETS) {
            int current = html.indexOf("href=\"" + stylesheet + "\"");
            assertThat(current).as("monitor.html 应加载 " + stylesheet).isGreaterThan(previous);
            previous = current;
            assertThat(read(stylesheet.substring(1))).as(stylesheet).isNotBlank();
        }
        assertThat(read("monitor/monitor.css"))
                .contains("html[data-theme=\"dark\"]", "--surface");
    }

    private String read(String resource) throws IOException {
        String path = "static/" + resource;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new NoSuchFileException(path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
