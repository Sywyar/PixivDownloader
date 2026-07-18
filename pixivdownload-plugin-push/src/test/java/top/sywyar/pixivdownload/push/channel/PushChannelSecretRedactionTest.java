package top.sywyar.pixivdownload.push.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;
import top.sywyar.pixivdownload.push.TestMessageResolver;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkConfig;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkPushChannel;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkSettings;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuConfig;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuPushChannel;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuSettings;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookConfig;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookPushChannel;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookSettings;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送通道凭证脱敏")
class PushChannelSecretRedactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RenderedMessage MESSAGE = new RenderedMessage(
            "标题", "正文", PushFormat.PLAIN_TEXT, PushLevel.INFO);

    @Test
    @DisplayName("钉钉网络异常不会回显访问令牌或派生签名")
    void dingtalkNetworkFailureRedactsTokenAndDerivedSignature() {
        FailingRestTemplate transport = FailingRestTemplate.echoUri();
        DingTalkPushChannel channel = new DingTalkPushChannel(
                new DingTalkConfig(), sender(transport));
        String token = "DINGTALK-TOKEN-1234";
        String secret = "SEC-DINGTALK-SECRET-1234";

        PushResult result = channel.sendTest(new DingTalkSettings(token, secret, false), MESSAGE);

        String uri = transport.uri().toString();
        String sign = uri.substring(uri.indexOf("&sign=") + "&sign=".length());
        assertThat(sign).isNotBlank();
        assertThat(result.detail()).contains("***")
                .doesNotContain(token, secret, sign);
    }

    @Test
    @DisplayName("飞书错误响应不会回显请求体中的派生签名")
    void feishuErrorResponseRedactsDerivedSignature() throws Exception {
        FailingRestTemplate transport = FailingRestTemplate.echoBody();
        FeishuPushChannel channel = new FeishuPushChannel(
                new FeishuConfig(), sender(transport));
        String secret = "FEISHU-SECRET-1234";

        PushResult result = channel.sendTest(
                new FeishuSettings("FEISHU-WEBHOOK-KEY-1234", secret, false), MESSAGE);

        JsonNode requestBody = JSON.readTree(transport.body());
        String sign = requestBody.path("sign").asText();
        assertThat(sign).isNotBlank();
        assertThat(result.detail()).contains("***")
                .doesNotContain(secret, sign);
    }

    @Test
    @DisplayName("Webhook 网络异常不会回显可能携带令牌的完整 URL")
    void webhookNetworkFailureRedactsWholeUrl() {
        FailingRestTemplate transport = FailingRestTemplate.echoUri();
        WebhookPushChannel channel = new WebhookPushChannel(
                new WebhookConfig(), sender(transport));
        String url = "https://example.test/hook?token=WEBHOOK-TOKEN-1234";

        PushResult result = channel.sendTest(
                new WebhookSettings(url, "application/json", "", false), MESSAGE);

        assertThat(result.detail()).contains("***")
                .doesNotContain(url, "example.test", "WEBHOOK-TOKEN-1234");
    }

    private static PushHttpSender sender(RestTemplate transport) {
        return new PushHttpSender(transport, transport, TestMessageResolver.INSTANCE);
    }

    private static final class FailingRestTemplate extends RestTemplate {
        private final boolean echoBody;
        private URI uri;
        private byte[] body;

        private FailingRestTemplate(boolean echoBody) {
            this.echoBody = echoBody;
        }

        private static FailingRestTemplate echoUri() {
            return new FailingRestTemplate(false);
        }

        private static FailingRestTemplate echoBody() {
            return new FailingRestTemplate(true);
        }

        private URI uri() {
            return uri;
        }

        private byte[] body() {
            return body;
        }

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method,
                                              HttpEntity<?> requestEntity, Class<T> responseType) {
            uri = url;
            body = (byte[]) requestEntity.getBody();
            if (echoBody) {
                throw HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        body,
                        StandardCharsets.UTF_8);
            }
            throw new ResourceAccessException("request failed for " + url);
        }
    }
}
