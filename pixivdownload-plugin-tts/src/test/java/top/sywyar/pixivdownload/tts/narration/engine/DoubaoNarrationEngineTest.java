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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("豆包 / Seed-TTS 朗读引擎")
class DoubaoNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;
    /** 任意 base64 音频（解码后 4 字节）。 */
    private static final String AUDIO_B64 = Base64.getEncoder().encodeToString(new byte[]{0x49, 0x44, 0x33, 0x04});

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：仅 voice-design")
    void supportedModesVoiceDesignOnly() {
        assertThat(engine(config("https://openspeech.bytedance.com", "app", "tok", "vt")).supportedModes())
                .containsExactly(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("isAvailable：需 base-url + app-id + access-token + voice-type 都就绪")
    void isAvailable() {
        assertThat(engine(config("https://h", "", "tok", "vt")).isAvailable()).isFalse();
        assertThat(engine(config("https://h", "app", "", "vt")).isAvailable()).isFalse();
        assertThat(engine(config("https://h", "app", "tok", "")).isAvailable()).isFalse();
        assertThat(engine(config("", "app", "tok", "vt")).isAvailable()).isFalse();
        assertThat(engine(config("https://h", "app", "tok", "vt")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("请求体：app/user/audio/request 四段，operation=query，端点 /api/v1/tts，鉴权头 Bearer;token")
    void requestBodyAndHeaders() throws Exception {
        when(direct.exchange(eq("https://openspeech.bytedance.com/api/v1/tts"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(AUDIO_B64));

        engine(config("https://openspeech.bytedance.com/", "app-1", "tok-1", "zh_female_x"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("你好", "ci", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(app(body)).containsEntry("appid", "app-1").containsEntry("cluster", "volcano_tts");
        assertThat(audio(body)).containsEntry("voice_type", "zh_female_x").containsEntry("encoding", "mp3");
        assertThat(request(body)).containsEntry("text", "你好").containsEntry("operation", "query");
        assertThat((String) request(body).get("reqid")).isNotBlank();
        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer;tok-1");
    }

    @Test
    @DisplayName("delivery 关键词映射到火山情绪枚举（angry → angry，且 enable_emotion=true）")
    void deliveryMapsToEmotion() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An old woman", "very angry and cold", null, null, 0L, 1, null, null);

        engine(config("https://h", "app", "tok", "vt")).synthesize(NarrationVoiceMode.VOICE_DESIGN, req);

        Map<String, Object> audio = audio(capturedBody(direct));
        assertThat(audio.get("emotion")).isEqualTo("angry");
        assertThat(audio.get("enable_emotion")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("无 delivery 命中且无配置 emotion → 不下发 emotion / enable_emotion")
    void omitsEmotionWhenNone() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        engine(config("https://h", "app", "tok", "vt"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        Map<String, Object> audio = audio(capturedBody(direct));
        assertThat(audio.containsKey("emotion")).isFalse();
        assertThat(audio.containsKey("enable_emotion")).isFalse();
    }

    @Test
    @DisplayName("响应 base64 解码为音频字节，contentType 跟随编码（mp3 → audio/mpeg）")
    void decodesBase64Audio() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        NarrationAudio audio = engine(config("https://h", "app", "tok", "vt"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        assertThat(audio.data()).containsExactly(0x49, 0x44, 0x33, 0x04);
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("业务 code 非 3000 → 受控异常，且不泄露 access-token")
    void nonSuccessCodeThrows() {
        String resp = "{\"code\":3001,\"message\":\"invalid token\"}";
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(jsonRaw(resp));

        assertThatThrownBy(() -> engine(config("https://h", "app", "sk-secret", "vt"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("3001")
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate")
    void usesProxy() {
        TtsPluginConfig cfg = config("https://h", "app", "tok", "vt");
        cfg.getDoubao().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DoubaoNarrationEngine engine(TtsPluginConfig cfg) {
        return new DoubaoNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String appId, String accessToken, String voiceType) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Doubao doubao = new TtsPluginConfig.Doubao();
        doubao.setBaseUrl(baseUrl);
        doubao.setAppId(appId);
        doubao.setAccessToken(accessToken);
        doubao.setVoiceType(voiceType);
        cfg.setDoubao(doubao);
        return cfg;
    }

    private static ResponseEntity<byte[]> json(String audioBase64) {
        return jsonRaw("{\"code\":3000,\"message\":\"Success\",\"data\":\"" + audioBase64 + "\"}");
    }

    private static ResponseEntity<byte[]> jsonRaw(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, 200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturedBody(RestTemplate rt) throws Exception {
        return MAPPER.readValue(captureEntity(rt).getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> app(Map<String, Object> body) {
        return (Map<String, Object>) body.get("app");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> audio(Map<String, Object> body) {
        return (Map<String, Object>) body.get("audio");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(Map<String, Object> body) {
        return (Map<String, Object>) body.get("request");
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
