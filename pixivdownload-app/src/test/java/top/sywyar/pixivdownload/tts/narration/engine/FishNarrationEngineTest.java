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
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Fish Audio 朗读引擎")
class FishNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppMessages MESSAGES = TestI18nBeans.appMessages();

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：仅 voice-design（克隆请求由派发器降级）")
    void supportedModesVoiceDesignOnly() {
        assertThat(engine(config("https://api.fish.audio", "k")).supportedModes())
                .containsExactly(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("isAvailable：云服务需 base-url + api-key 都就绪")
    void isAvailable() {
        assertThat(engine(config("https://api.fish.audio", "")).isAvailable()).isFalse();
        assertThat(engine(config("", "k")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.fish.audio", "k")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("请求体：text 前置 (delivery) 标记、reference_id 透传、format 来自配置；model 走请求头")
    void requestBodyAndHeaders() throws Exception {
        when(direct.exchange(eq("https://api.fish.audio/v1/tts"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(mp3());
        NarrationTtsConfig cfg = config("https://api.fish.audio", "sk-fish");
        cfg.getFish().setReferenceId("voice-123");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 0L, 1, null, null);

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("text")).isEqualTo("(angry)原句");
        assertThat(body.get("reference_id")).isEqualTo("voice-123");
        assertThat(body.get("format")).isEqualTo("mp3");
        HttpHeaders headers = capturedHeaders(direct);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-fish");
        assertThat(headers.getFirst("model")).isEqualTo("s1");
    }

    @Test
    @DisplayName("无 delivery 时 text 原样、无 reference-id 时不下发该字段")
    void omitsBlankFields() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        engine(config("https://api.fish.audio", "k"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("纯正文", "", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("text")).isEqualTo("纯正文");
        assertThat(body.containsKey("reference_id")).isFalse();
    }

    @Test
    @DisplayName("contentType 取响应头（mp3 → audio/mpeg）")
    void contentTypeFromResponse() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        NarrationAudio audio = engine(config("https://api.fish.audio", "k"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate")
    void usesProxy() {
        NarrationTtsConfig cfg = config("https://api.fish.audio", "k");
        cfg.getFish().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(mp3());

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FishNarrationEngine engine(NarrationTtsConfig cfg) {
        return new FishNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static NarrationTtsConfig config(String baseUrl, String apiKey) {
        NarrationTtsConfig cfg = new NarrationTtsConfig();
        NarrationTtsConfig.Fish fish = new NarrationTtsConfig.Fish();
        fish.setBaseUrl(baseUrl);
        fish.setApiKey(apiKey);
        cfg.setFish(fish);
        return cfg;
    }

    private static ResponseEntity<byte[]> mp3() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/mpeg"));
        return new ResponseEntity<>(new byte[]{0x49, 0x44, 0x33}, headers, 200);
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
