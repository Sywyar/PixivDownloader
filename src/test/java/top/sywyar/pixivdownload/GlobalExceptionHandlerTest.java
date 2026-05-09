package top.sywyar.pixivdownload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestI18nBeans.appMessages());

    @Test
    @DisplayName("SecurityException 应返回 400 和错误消息")
    void shouldHandle400ForSecurityException() {
        SecurityException ex = new SecurityException("只允许 HTTPS 协议的下载 URL");

        ResponseEntity<ErrorResponse> response = handler.handleSecurity(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("只允许 HTTPS 协议的下载 URL");
    }

    @Test
    @DisplayName("通用异常应返回 500 和错误消息")
    void shouldHandle500ForGenericException() {
        Exception ex = new RuntimeException("意外错误");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("意外错误");
    }

    @Test
    @DisplayName("异常消息为 null 时应返回本地化默认消息")
    void shouldHandleNullMessage() {
        Exception ex = new RuntimeException();

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("发生未处理异常");
    }

    @Test
    @DisplayName("客户端断开导致的 IO 异常应视为无响应体的正常断连")
    void shouldTreatClientDisconnectAsNoContent() {
        IOException ex = new IOException("你的主机中的软件中止了一个已建立的连接。");

        ResponseEntity<?> response = handler.handleIOException(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }
}
