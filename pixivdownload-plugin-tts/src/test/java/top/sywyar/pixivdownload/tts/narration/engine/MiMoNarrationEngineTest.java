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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("小米 MiMo 朗读引擎")
class MiMoNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;
    /** 任意 base64 音频（解码后 4 字节）。 */
    private static final String AUDIO_B64 = Base64.getEncoder().encodeToString(new byte[]{0x52, 0x49, 0x46, 0x46});

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：支持 voice-design 与 clone（无 Hi-Fi 续写概念）")
    void supportedModes() {
        assertThat(engine(config("https://h/v1", "")).supportedModes())
                .containsExactlyInAnyOrder(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE);
    }

    @Test
    @DisplayName("isAvailable：云服务需 base-url + api-key 都就绪（base-url 默认非空，门禁在 key）")
    void isAvailableReflectsBaseUrlAndKey() {
        assertThat(engine(config("", "k")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.xiaomimimo.com/v1", "")).isAvailable()).isFalse();
        assertThat(engine(config("  ", "k")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.xiaomimimo.com/v1", "sk-key")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("VOICE_DESIGN 未配预置音色：用 voicedesign 模型，user=画像、assistant=正文，不下发 audio.voice")
    void voiceDesignUsesDescriptionModel() throws Exception {
        when(direct.exchange(eq("https://api.xiaomimimo.com/v1/chat/completions"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(AUDIO_B64));

        engine(config("https://api.xiaomimimo.com/v1/", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("你好世界", "An elderly woman, low cold voice", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("mimo-v2.5-tts-voicedesign");
        List<Map<String, Object>> messages = messages(body);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "user").containsEntry("content", "An elderly woman, low cold voice");
        assertThat(messages.get(1)).containsEntry("role", "assistant").containsEntry("content", "你好世界");
        assertThat(audio(body)).containsEntry("format", "wav");
        assertThat(audio(body).containsKey("voice")).isFalse();
    }

    @Test
    @DisplayName("VOICE_DESIGN 配了预置音色：用预置模型 + audio.voice，user 仍带 controlInstruction 作风格")
    void voiceDesignUsesPresetVoice() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));
        TtsPluginConfig cfg = config("https://h/v1", "");
        cfg.getMimo().setVoice("Chloe");

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("hi", "bright", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("mimo-v2.5-tts");
        assertThat(audio(body)).containsEntry("voice", "Chloe");
        assertThat(messages(body).get(0)).containsEntry("content", "bright");
    }

    @Test
    @DisplayName("VOICE_DESIGN 画像与预置音色都缺省：voicedesign 模型 + 中性兜底描述（user 非空）")
    void voiceDesignFallsBackToNeutralDescription() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        engine(config("https://h/v1", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("正文", "  ", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("mimo-v2.5-tts-voicedesign");
        assertThat((String) messages(body).get(0).get("content")).isNotBlank();
    }

    @Test
    @DisplayName("CLONE：用 voiceclone 模型，audio.voice=参考音 data URI，user=delivery")
    void cloneUsesVoiceCloneModelAndDataUri() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子句");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        engine(config("https://h/v1", "")).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("mimo-v2.5-tts-voiceclone");
        assertThat((String) audio(body).get("voice")).startsWith("data:audio/wav;base64,");
        assertThat(messages(body).get(0)).containsEntry("role", "user").containsEntry("content", "angry");
    }

    @Test
    @DisplayName("克隆全局关闭（enable-clone=false）：即便配了参考音也退回 voice-design 模型，不下发 data URI")
    void cloneDisabledFallsBackToVoiceDesign() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));
        TtsPluginConfig cfg = config("https://h/v1", "");
        cfg.getMimo().setEnableClone(false);
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1}, "audio/wav", "x");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        engine(cfg).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("mimo-v2.5-tts-voicedesign");
        assertThat((String) audio(body).getOrDefault("voice", "")).doesNotStartWith("data:");
    }

    @Test
    @DisplayName("鉴权用 api-key 头（非 Authorization Bearer）")
    void usesApiKeyHeaderNotBearer() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        engine(config("https://h/v1", "sk-mimo-123"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        HttpHeaders headers = capturedHeaders(direct);
        assertThat(headers.getFirst("api-key")).isEqualTo("sk-mimo-123");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("响应 base64 解码为音频字节，contentType=audio/wav")
    void decodesBase64Audio() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        NarrationAudio audio = engine(config("https://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        assertThat(audio.data()).containsExactly(0x52, 0x49, 0x46, 0x46);
        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("响应无音频数据（choices 空）→ 受控异常")
    void emptyAudioDataThrows() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(jsonRaw("{\"choices\":[]}"));

        assertThatThrownBy(() -> engine(config("https://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class);
    }

    @Test
    @DisplayName("非 2xx 抛受控异常，消息脱敏且不含 api-key")
    void httpErrorRedactsApiKey() {
        String apiKey = "sk-secret-abcdef";
        RestClientResponseException ex = new RestClientResponseException(
                "err", 401, "Unauthorized", null,
                ("{\"error\":\"bad key " + apiKey + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenThrow(ex);

        assertThatThrownBy(() -> engine(config("https://h/v1", apiKey))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(apiKey);
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate，不碰直连")
    void usesProxyTemplateWhenConfigured() {
        TtsPluginConfig cfg = config("https://h/v1", "");
        cfg.getMimo().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_B64));

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MiMoNarrationEngine engine(TtsPluginConfig cfg) {
        return new MiMoNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Mimo mimo = new TtsPluginConfig.Mimo();
        mimo.setBaseUrl(baseUrl);
        mimo.setApiKey(apiKey);
        cfg.setMimo(mimo);
        return cfg;
    }

    private static ResponseEntity<byte[]> json(String audioBase64) {
        return jsonRaw("{\"choices\":[{\"message\":{\"audio\":{\"data\":\"" + audioBase64 + "\"}}}]}");
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
    private static List<Map<String, Object>> messages(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> audio(Map<String, Object> body) {
        return (Map<String, Object>) body.get("audio");
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
