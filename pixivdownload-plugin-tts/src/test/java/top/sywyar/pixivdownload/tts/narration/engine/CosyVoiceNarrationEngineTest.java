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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CosyVoice 朗读引擎")
class CosyVoiceNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：voice-design 与 clone")
    void supportedModes() {
        assertThat(engine(config("http://h/v1")).supportedModes())
                .containsExactlyInAnyOrder(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE);
    }

    @Test
    @DisplayName("isAvailable：base-url 空 → 不可用")
    void isAvailable() {
        assertThat(engine(config("")).isAvailable()).isFalse();
        assertThat(engine(config("http://127.0.0.1:8000/v1")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("VOICE_DESIGN：input=正文、instruct=画像、配了 voice 时透传，绝不带 reference_audio")
    void voiceDesignBody() throws Exception {
        when(direct.exchange(eq("http://h/v1/audio/speech"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1");
        cfg.getCosyvoice().setVoice("中文女");

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("你好", "An elderly woman, cold voice"));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("input")).isEqualTo("你好");
        assertThat(body.get("voice")).isEqualTo("中文女");
        assertThat(body.get("instruct")).isEqualTo("An elderly woman, cold voice");
        assertThat(body.containsKey("reference_audio")).isFalse();
    }

    @Test
    @DisplayName("VOICE_DESIGN：未配 voice 时不下发 voice 字段")
    void voiceDesignOmitsBlankVoice() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("t", ""));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.containsKey("voice")).isFalse();
        assertThat(body.containsKey("instruct")).isFalse();
    }

    @Test
    @DisplayName("CLONE：reference_audio=data URI、reference_text=转录、instruct=delivery，不带 voice")
    void cloneBody() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子句");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", ref);

        engine(config("http://h/v1")).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat((String) body.get("reference_audio")).startsWith("data:audio/wav;base64,");
        assertThat(body.get("reference_text")).isEqualTo("种子句");
        assertThat(body.get("instruct")).isEqualTo("angry");
        assertThat(body.containsKey("voice")).isFalse();
    }

    @Test
    @DisplayName("克隆全局关闭：即便配了参考音也退回 voice-design，不带 reference_audio")
    void cloneDisabledFallsBack() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1");
        cfg.getCosyvoice().setEnableClone(false);
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1}, "audio/wav", "x");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", ref);

        engine(cfg).synthesize(NarrationVoiceMode.CLONE, req);

        assertThat(capturedBody(direct).containsKey("reference_audio")).isFalse();
    }

    @Test
    @DisplayName("配了 api-key 时带 Bearer 头")
    void bearerHeader() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "sk-cosy"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", ""));

        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-cosy");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate，不碰直连")
    void usesProxy() {
        TtsPluginConfig cfg = config("http://h/v1");
        cfg.getCosyvoice().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", ""));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
        verifyNoInteractions(direct);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CosyVoiceNarrationEngine engine(TtsPluginConfig cfg) {
        return new CosyVoiceNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl) {
        return config(baseUrl, "");
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Cosyvoice cosy = new TtsPluginConfig.Cosyvoice();
        cosy.setBaseUrl(baseUrl);
        cosy.setApiKey(apiKey);
        cfg.setCosyvoice(cosy);
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
