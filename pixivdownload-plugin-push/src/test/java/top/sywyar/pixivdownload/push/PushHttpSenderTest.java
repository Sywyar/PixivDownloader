package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送 HTTP 发送器业务响应处理")
class PushHttpSenderTest {

    private final StubRestTemplate direct = new StubRestTemplate();
    private final StubRestTemplate proxy = new StubRestTemplate();
    private final PushHttpSender sender = new PushHttpSender(direct, proxy, TestMessageResolver.INSTANCE);

    @Test
    @DisplayName("钉钉 HTTP 200 但 errcode 非零时失败并脱敏")
    void dingtalkBusinessErrorFailsAndRedactsSecret() {
        direct.respond("{\"errcode\":310000,\"errmsg\":\"secret TOKEN-1234 mismatch\"}");

        PushResult result = sender.send(PushChannelType.DINGTALK,
                request(List.of("TOKEN-1234")));

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail())
                .contains("errcode=310000")
                .contains("***")
                .doesNotContain("TOKEN-1234");
    }

    @Test
    @DisplayName("企业微信 errcode 为零时保持成功")
    void wecomErrcodeZeroOk() {
        direct.respond("{\"errcode\":0,\"errmsg\":\"ok\"}");

        PushResult result = sender.send(PushChannelType.WECOM, request(List.of()));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    @DisplayName("PushPlus HTTP 200 但 code 失败时返回失败")
    void pushplusBusinessErrorFails() {
        direct.respond("{\"code\":900,\"msg\":\"account limited\"}");

        PushResult result = sender.send(PushChannelType.PUSHPLUS, request(List.of()));

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).contains("code=900").contains("account limited");
    }

    @Test
    @DisplayName("Telegram ok 为 false 时返回失败")
    void telegramOkFalseFails() {
        direct.respond("{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request\"}");

        PushResult result = sender.send(PushChannelType.TELEGRAM, request(List.of()));

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).contains("ok=false").contains("error_code=400");
    }

    @Test
    @DisplayName("飞书 StatusCode 失败时返回失败")
    void feishuStatusCodeFailureFails() {
        direct.respond("{\"StatusCode\":19024,\"StatusMessage\":\"invalid sign\"}");

        PushResult result = sender.send(PushChannelType.FEISHU, request(List.of()));

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).contains("StatusCode=19024").contains("invalid sign");
    }

    @Test
    @DisplayName("Server 酱 code 失败时返回失败")
    void serverChanCodeFailureFails() {
        direct.respond("{\"code\":40001,\"message\":\"bad sendkey\"}");

        PushResult result = sender.send(PushChannelType.SERVERCHAN, request(List.of()));

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).contains("code=40001").contains("bad sendkey");
    }

    @Test
    @DisplayName("自定义 Webhook 不解析平台专用 code 字段")
    void webhookBusinessShapeIsIgnored() {
        direct.respond("{\"code\":900,\"msg\":\"custom failure shape\"}");

        PushResult result = sender.send(PushChannelType.WEBHOOK, request(List.of()));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    @DisplayName("非 RestClientException 的运行时异常收敛为受控失败原因")
    void unexpectedRuntimeFailureIsContained() {
        direct.failWith(new IllegalStateException("unexpected transport failure"));
        PushHttpSender brokenMessagesSender = new PushHttpSender(
                direct, proxy, TestMessageResolver.THROWING);

        PushResult result = brokenMessagesSender.send(PushChannelType.BARK, request(List.of()));
        PushResult nullRequest = brokenMessagesSender.send(PushChannelType.BARK, null);

        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
        assertThat(nullRequest.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(nullRequest.detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
    }

    @Test
    @DisplayName("日志文案解析异常不会穿透发送器故障安全边界")
    void logMessageResolutionFailureIsContained() {
        direct.respond("{\"code\":200}");
        PushHttpSender brokenMessagesSender = new PushHttpSender(
                direct, proxy, TestMessageResolver.THROWING);

        PushResult success = brokenMessagesSender.send(PushChannelType.BARK, request(List.of()));
        PushResult invalidUrl = brokenMessagesSender.send(
                PushChannelType.BARK,
                OutboundRequest.json("://invalid", new byte[0], List.of(), false));

        assertThat(success.isOk()).isTrue();
        assertThat(invalidUrl.detail()).isEqualTo(PushResult.DETAIL_INVALID_URL);
    }

    private static OutboundRequest request(List<String> secrets) {
        return OutboundRequest.json("https://example.test/send",
                "{}".getBytes(StandardCharsets.UTF_8), secrets, false);
    }

    private static final class StubRestTemplate extends RestTemplate {
        private ResponseEntity<byte[]> response;
        private RuntimeException failure;

        void respond(String json) {
            failure = null;
            response = ResponseEntity.ok(json.getBytes(StandardCharsets.UTF_8));
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method,
                                              HttpEntity<?> requestEntity, Class<T> responseType) {
            if (failure != null) {
                throw failure;
            }
            @SuppressWarnings("unchecked")
            ResponseEntity<T> cast = (ResponseEntity<T>) response;
            return cast;
        }
    }
}
