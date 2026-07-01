package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Qwen3-TTS 朗读引擎")
class QwenNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;
    private static final String GEN_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String AUDIO_URL = "https://oss-example.aliyuncs.com/result.wav?Signature=xyz";
    private static final String SIGNED_AUDIO_URL =
            "https://oss-example.aliyuncs.com/result.wav?Signature=a%2Bb%2Fc%3D&OSSAccessKeyId=tmp%2Fkey";

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：仅 voice-design")
    void supportedModesVoiceDesignOnly() {
        assertThat(engine(config("https://dashscope.aliyuncs.com/api/v1", "k")).supportedModes())
                .containsExactly(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("isAvailable：云服务需 base-url + api-key 都就绪")
    void isAvailable() {
        assertThat(engine(config("https://dashscope.aliyuncs.com/api/v1", "")).isAvailable()).isFalse();
        assertThat(engine(config("", "k")).isAvailable()).isFalse();
        assertThat(engine(config("https://dashscope.aliyuncs.com/api/v1", "k")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("请求体：model + input.text/voice，Bearer 头，端点 multimodal-generation；再 GET 取临时 URL 的音频字节")
    void requestBodyAndAudioFetch() throws Exception {
        when(direct.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(AUDIO_URL));
        when(direct.exchange(eq(URI.create(AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(wav());

        NarrationAudio audio = engine(config("https://dashscope.aliyuncs.com/api/v1/", "sk-qwen"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("你好世界", "ci", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("qwen3-tts-flash");
        assertThat(input(body)).containsEntry("text", "你好世界").containsEntry("voice", "Cherry");
        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-qwen");
        assertThat(audio.data()).containsExactly(0x52, 0x49, 0x46, 0x46);
        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("language-type 留空时不下发该字段")
    void omitsBlankLanguageType() throws Exception {
        when(direct.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_URL));
        when(direct.exchange(eq(URI.create(AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("https://dashscope.aliyuncs.com/api/v1", "k"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        assertThat(input(capturedBody(direct)).containsKey("language_type")).isFalse();
    }

    @Test
    @DisplayName("响应带错误码、无音频 URL → 受控异常，且不泄露 api-key")
    void errorResponseThrows() {
        String resp = "{\"code\":\"InvalidApiKey\",\"message\":\"invalid api key sk-secret\"}";
        when(direct.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(jsonRaw(resp));

        assertThatThrownBy(() -> engine(config("https://dashscope.aliyuncs.com/api/v1", "sk-secret"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("InvalidApiKey")
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    @DisplayName("use-proxy=true 时合成与取音频两次请求都走代理 RestTemplate")
    void usesProxy() {
        TtsPluginConfig cfg = config("https://dashscope.aliyuncs.com/api/v1", "k");
        cfg.getQwen().setUseProxy(true);
        when(proxy.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_URL));
        when(proxy.exchange(eq(URI.create(AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class))).thenReturn(wav());

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        verify(proxy).exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class));
        verify(proxy).exchange(eq(URI.create(AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class));
    }

    @Test
    @DisplayName("临时签名音频 URL 使用 URI 直发，避免二次编码")
    void fetchesSignedAudioUrlAsUri() {
        when(direct.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(SIGNED_AUDIO_URL));
        when(direct.exchange(eq(URI.create(SIGNED_AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(wav());

        engine(config("https://dashscope.aliyuncs.com/api/v1", "k"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(direct).exchange(uriCaptor.capture(), eq(HttpMethod.GET), any(), eq(byte[].class));
        assertThat(uriCaptor.getValue().toASCIIString())
                .contains("Signature=a%2Bb%2Fc%3D")
                .contains("OSSAccessKeyId=tmp%2Fkey");
    }

    @Test
    @DisplayName("临时音频下载失败时不回显签名 URL 或 api-key")
    void audioFetchFailureDoesNotLeakSignedUrl() {
        when(direct.exchange(eq(GEN_URL), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(SIGNED_AUDIO_URL));
        when(direct.exchange(eq(URI.create(SIGNED_AUDIO_URL)), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new RestClientException("GET " + SIGNED_AUDIO_URL + " failed with sk-secret"));

        assertThatThrownBy(() -> engine(config("https://dashscope.aliyuncs.com/api/v1", "sk-secret"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("audio download failed")
                .hasMessageNotContaining("Signature")
                .hasMessageNotContaining("OSSAccessKeyId")
                .hasMessageNotContaining("a%2Bb")
                .hasMessageNotContaining("sk-secret");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private QwenNarrationEngine engine(TtsPluginConfig cfg) {
        return new QwenNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Qwen qwen = new TtsPluginConfig.Qwen();
        qwen.setBaseUrl(baseUrl);
        qwen.setApiKey(apiKey);
        cfg.setQwen(qwen);
        return cfg;
    }

    private static ResponseEntity<byte[]> json(String audioUrl) {
        return jsonRaw("{\"output\":{\"audio\":{\"url\":\"" + audioUrl + "\"}}}");
    }

    private static ResponseEntity<byte[]> jsonRaw(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, 200);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(Map<String, Object> body) {
        return (Map<String, Object>) body.get("input");
    }

    private static HttpHeaders capturedHeaders(RestTemplate rt) {
        return captureEntity(rt).getHeaders();
    }

    @SuppressWarnings("unchecked")
    private static HttpEntity<byte[]> captureEntity(RestTemplate rt) {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rt).exchange(eq(GEN_URL), eq(HttpMethod.POST), captor.capture(), eq(byte[].class));
        return (HttpEntity<byte[]>) captor.getValue();
    }
}
