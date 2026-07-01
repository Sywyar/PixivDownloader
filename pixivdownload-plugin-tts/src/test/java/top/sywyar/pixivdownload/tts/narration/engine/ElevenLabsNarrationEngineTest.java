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
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ElevenLabs 朗读引擎")
class ElevenLabsNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：仅 voice-design（克隆请求由派发器降级）")
    void supportedModesVoiceDesignOnly() {
        assertThat(engine(config("https://api.elevenlabs.io", "k", "v")).supportedModes())
                .containsExactly(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("isAvailable：云服务需 base-url + api-key + voice-id 都就绪")
    void isAvailable() {
        assertThat(engine(config("https://api.elevenlabs.io", "", "v")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.elevenlabs.io", "k", "")).isAvailable()).isFalse();
        assertThat(engine(config("", "k", "v")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.elevenlabs.io", "k", "v")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("请求体与端点：voice_id 进 URL 路径、output_format 进 query、xi-api-key 头、text 前置 [delivery] 标签、model_id 来自配置")
    void requestBodyAndHeaders() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "whispers", null, null, 0L, 1, null, null);

        engine(config("https://api.elevenlabs.io/", "xi-key", "voice-123"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, req);

        assertThat(capturedUrl(direct))
                .isEqualTo("https://api.elevenlabs.io/v1/text-to-speech/voice-123?output_format=mp3_44100_128");
        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("text")).isEqualTo("[whispers] 原句");
        assertThat(body.get("model_id")).isEqualTo("eleven_v3");
        HttpHeaders headers = capturedHeaders(direct);
        assertThat(headers.getFirst("xi-api-key")).isEqualTo("xi-key");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("无 delivery 时 text 原样")
    void omitsBlankDelivery() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        engine(config("https://api.elevenlabs.io", "k", "v"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("纯正文", "ci", null));

        assertThat(capturedBody(direct).get("text")).isEqualTo("纯正文");
    }

    @Test
    @DisplayName("contentType 取响应头（mp3 → audio/mpeg）")
    void contentTypeFromResponse() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        NarrationAudio audio = engine(config("https://api.elevenlabs.io", "k", "v"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate")
    void usesProxy() {
        TtsPluginConfig cfg = config("https://api.elevenlabs.io", "k", "v");
        cfg.getElevenlabs().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ElevenLabsNarrationEngine engine(TtsPluginConfig cfg) {
        return new ElevenLabsNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey, String voiceId) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Elevenlabs el = new TtsPluginConfig.Elevenlabs();
        el.setBaseUrl(baseUrl);
        el.setApiKey(apiKey);
        el.setVoiceId(voiceId);
        cfg.setElevenlabs(el);
        return cfg;
    }

    private static ResponseEntity<byte[]> mp3() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/mpeg"));
        return new ResponseEntity<>(new byte[]{0x49, 0x44, 0x33}, headers, 200);
    }

    private static String capturedUrl(RestTemplate rt) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rt).exchange(captor.capture(), eq(HttpMethod.POST), any(), eq(byte[].class));
        return captor.getValue();
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
