package top.sywyar.pixivdownload.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateService 单元测试")
class UpdateServiceTest {

    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @SuppressWarnings("unchecked")
    private static ResponseEntity<byte[]> okBytes(byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @Test
    @DisplayName("manifest 走 byte[] 路径按 UTF-8 解析，中文发布说明不乱码")
    void shouldParseManifestAsUtf8Bytes() {
        // 中文发布说明：若按 ISO-8859-1（Spring StringHttpMessageConverter 默认）解码会乱码。
        String releaseNotes = "本次更新：修复了若干问题，新增「听书」功能。";
        String manifestJson = """
                {
                  "latestVersion": "999.0.0",
                  "releaseDate": "2026-05-22",
                  "releaseNotes": "%s",
                  "releaseNotesUrl": "https://example.com/notes",
                  "assets": {}
                }
                """.formatted(releaseNotes);
        // 模拟 GitHub release 资产：application/octet-stream 且不带 charset。
        byte[] utf8Body = manifestJson.getBytes(StandardCharsets.UTF_8);

        UpdateConfig config = new UpdateConfig();
        config.setEnabled(true);
        config.setManifestUrl("https://example.com/update.json");
        config.setCheckNightly(false);

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(okBytes(utf8Body));

        UpdateService service = new UpdateService(config, restTemplate, APP_MESSAGES);

        UpdateCheckResult result = service.checkForUpdate(true);

        assertThat(result.isCheckSucceeded()).isTrue();
        assertThat(result.getLatestVersion()).isEqualTo("999.0.0");
        // 关键断言：中文原样保留，证明走的是 UTF-8 字节解析而非 ISO-8859-1。
        assertThat(result.getReleaseNotes()).isEqualTo(releaseNotes);
    }

    @Test
    @DisplayName("update.enabled=false 时不联网，直接返回未启用结果")
    void shouldShortCircuitWhenDisabled() {
        UpdateConfig config = new UpdateConfig();
        config.setEnabled(false);

        RestTemplate restTemplate = mock(RestTemplate.class);
        UpdateService service = new UpdateService(config, restTemplate, APP_MESSAGES);

        UpdateCheckResult result = service.checkForUpdate(true);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isCheckSucceeded()).isFalse();
        assertThat(result.isUpdateAvailable()).isFalse();
    }
}
