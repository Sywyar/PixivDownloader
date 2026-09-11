package top.sywyar.pixivdownload.plugin.runtime.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("仓库 HTTPS 地址共享语法")
class HttpsLocationTest {
    @Test
    @DisplayName("保留原始 URL 字节语义并约束凭据查询和长度")
    void validatesLocation() throws Exception {
        String raw = "HTTPS://example.org/a/../b?q=%2F#x";
        assertThat(HttpsLocation.parse(raw, false).toString()).isEqualTo(raw);
        assertThatThrownBy(() -> HttpsLocation.parse(raw, true)).isInstanceOf(URISyntaxException.class);
        String prefix = "https://example.org/";
        String exact = prefix + "x".repeat(HttpsLocation.MAX_URL_CHARS - prefix.length());
        assertThat(HttpsLocation.parse(exact, true).toString()).isEqualTo(exact);
        assertThatThrownBy(() -> HttpsLocation.parse(exact + "x", true)).isInstanceOf(URISyntaxException.class);
        for (String invalid : List.of("http://example.org/", "https://user:password@example.org/", "//example.org/a",
                " https://example.org/", "https://example.org/ ", "https://example.org/\n", "https:///path")) {
            assertThatThrownBy(() -> HttpsLocation.parse(invalid, false)).isInstanceOf(URISyntaxException.class);
        }
    }
}
