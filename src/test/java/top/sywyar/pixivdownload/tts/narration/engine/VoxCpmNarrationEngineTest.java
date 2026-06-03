package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("VoxCPM 朗读引擎")
class VoxCpmNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppMessages MESSAGES = TestI18nBeans.appMessages();

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);
    private final RestTemplate directProbe = mock(RestTemplate.class);
    private final RestTemplate proxyProbe = mock(RestTemplate.class);

    @Test
    @DisplayName("isAvailable：base-url 空 / 空白 → 不可用，非空 → 可用")
    void isAvailableReflectsBaseUrl() {
        assertThat(engine(config("", "")).isAvailable()).isFalse();
        assertThat(engine(config("   ", "")).isAvailable()).isFalse();
        assertThat(engine(config("http://127.0.0.1:8000/v1", "")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("isReachable：base-url 空 → 不可达且不触网")
    void isReachableFalseWhenUnconfigured() {
        assertThat(engine(config("", "")).isReachable()).isFalse();
        verifyNoInteractions(direct, proxy, directProbe, proxyProbe);
    }

    @Test
    @DisplayName("isReachable：GET {base}/models 返回 2xx → 可达")
    void isReachableTrueOn2xx() {
        when(directProbe.exchange(eq("http://127.0.0.1:8000/v1/models"), eq(HttpMethod.GET), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.OK));
        assertThat(engine(config("http://127.0.0.1:8000/v1/", "")).isReachable()).isTrue();
    }

    @Test
    @DisplayName("isReachable：非 2xx（401/403 凭证被拒、404、5xx）→ 不可达")
    void isReachableFalseOnNon2xx() {
        for (int status : new int[]{401, 403, 404, 503}) {
            when(directProbe.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class)))
                    .thenThrow(new RestClientResponseException("e", status, "err", null, null, null));
            assertThat(engine(config("http://h/v1", "")).isReachable()).isFalse();
        }
    }

    @Test
    @DisplayName("isReachable：连接失败 / 超时 → 不可达")
    void isReachableFalseOnConnectFailure() {
        when(directProbe.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class)))
                .thenThrow(new RestClientException("Connection refused"));
        assertThat(engine(config("http://h/v1", "")).isReachable()).isFalse();
    }

    @Test
    @DisplayName("isReachable：use-proxy=true 用代理探测模板，不碰直连探测")
    void isReachableUsesProxyProbeWhenConfigured() {
        NarrationTtsConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setUseProxy(true);
        when(proxyProbe.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.OK));

        assertThat(engine(cfg).isReachable()).isTrue();

        verify(proxyProbe).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class));
        verifyNoInteractions(directProbe);
    }

    @Test
    @DisplayName("请求体：controlInstruction 非空时 input 为 (描述)正文，voice=default，model/response_format 来自配置")
    void requestBodyWithControlInstruction() throws Exception {
        when(direct.exchange(eq("http://127.0.0.1:8000/v1/audio/speech"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(wav());

        engine(config("http://127.0.0.1:8000/v1/", ""))
                .synthesize(NarrationVoiceRequest.of("你好世界", "An elderly woman, low and cold voice", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("input")).isEqualTo("(An elderly woman, low and cold voice)你好世界");
        assertThat(body.get("voice")).isEqualTo("default");
        assertThat(body.get("model")).isEqualTo("openbmb/VoxCPM2");
        assertThat(body.get("response_format")).isEqualTo("wav");
    }

    @Test
    @DisplayName("请求体：controlInstruction 为空 / 空白时 input 仅正文")
    void requestBodyWithoutControlInstruction() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceRequest.of("仅正文", "   ", null));

        assertThat(capturedBody(direct).get("input")).isEqualTo("仅正文");
    }

    @Test
    @DisplayName("配了 api-key 时带 Bearer 头")
    void bearerHeaderWhenApiKeyConfigured() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "sk-secret-xyz")).synthesize(NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-secret-xyz");
    }

    @Test
    @DisplayName("未配 api-key 时不带 Authorization 头")
    void noBearerHeaderWhenApiKeyBlank() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("返回 byte[] + contentType（响应头优先）")
    void returnsAudioWithContentTypeFromHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[]{1, 2, 3}, headers, 200));

        NarrationAudio audio = engine(config("http://h/v1", "")).synthesize(NarrationVoiceRequest.of("t", "", null));

        assertThat(audio.data()).containsExactly(1, 2, 3);
        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("contentType 缺响应头时按 response-format 推断")
    void contentTypeInferredFromFormat() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[]{9}, new HttpHeaders(), 200));

        NarrationAudio audio = engine(config("http://h/v1", "")).synthesize(NarrationVoiceRequest.of("t", "", null));

        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate，不碰直连")
    void usesProxyTemplateWhenConfigured() {
        NarrationTtsConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(cfg).synthesize(NarrationVoiceRequest.of("t", "", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
        verifyNoInteractions(direct);
    }

    @Test
    @DisplayName("空响应抛受控异常")
    void emptyResponseThrows() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], new HttpHeaders(), 200));

        assertThatThrownBy(() -> engine(config("http://h/v1", ""))
                .synthesize(NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class);
    }

    @Test
    @DisplayName("非 2xx 抛受控异常，消息脱敏且不含 api-key")
    void httpErrorRedactsApiKey() {
        String apiKey = "sk-secret-abcdef";
        RestClientResponseException ex = new RestClientResponseException(
                "err", 401, "Unauthorized", null,
                ("{\"error\":\"invalid key " + apiKey + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenThrow(ex);

        assertThatThrownBy(() -> engine(config("http://h/v1", apiKey))
                .synthesize(NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(apiKey);
    }

    @Test
    @DisplayName("非 2xx 抛受控异常，短 api-key 同样脱敏")
    void httpErrorRedactsShortApiKey() {
        String apiKey = "x";
        RestClientResponseException ex = new RestClientResponseException(
                "err", 401, "Unauthorized", null,
                ("{\"error\":\"invalid key " + apiKey + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenThrow(ex);

        assertThatThrownBy(() -> engine(config("http://h/v1", apiKey))
                .synthesize(NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(apiKey);
    }

    @Test
    @DisplayName("base-url 未配置直接抛受控异常，不触网")
    void unavailableThrowsWithoutNetwork() {
        assertThatThrownBy(() -> engine(config("", ""))
                .synthesize(NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class);
        verifyNoInteractions(direct, proxy);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private VoxCpmNarrationEngine engine(NarrationTtsConfig cfg) {
        return new VoxCpmNarrationEngine(cfg, direct, proxy, directProbe, proxyProbe, MESSAGES);
    }

    private static NarrationTtsConfig config(String baseUrl, String apiKey) {
        NarrationTtsConfig cfg = new NarrationTtsConfig();
        NarrationTtsConfig.Voxcpm vox = new NarrationTtsConfig.Voxcpm();
        vox.setBaseUrl(baseUrl);
        vox.setApiKey(apiKey);
        vox.setModel("openbmb/VoxCPM2");
        vox.setResponseFormat("wav");
        cfg.setVoxcpm(vox);
        return cfg;
    }

    private static ResponseEntity<byte[]> wav() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        return new ResponseEntity<>(new byte[]{0x52, 0x49, 0x46, 0x46}, headers, 200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturedBody(RestTemplate rt) throws Exception {
        return MAPPER.readValue(captureEntity(rt).getBody(), Map.class);
    }

    private static HttpHeaders capturedHeaders(RestTemplate rt) {
        return captureEntity(rt).getHeaders();
    }

    @SuppressWarnings("unchecked")
    private static HttpEntity<byte[]> captureEntity(RestTemplate rt) {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rt).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(byte[].class));
        return (HttpEntity<byte[]>) captor.getValue();
    }
}
