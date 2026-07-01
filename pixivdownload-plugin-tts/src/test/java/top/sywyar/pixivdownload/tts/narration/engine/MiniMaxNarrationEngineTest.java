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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MiniMax 朗读引擎")
class MiniMaxNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;
    /** hex 编码的 4 字节音频（52 49 46 46 = "RIFF"）。 */
    private static final String AUDIO_HEX = "52494646";
    /** 测试用 GroupId（控制台为 19 位数字）。 */
    private static final String GROUP_ID = "1234567890123456789";

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：仅 voice-design")
    void supportedModesVoiceDesignOnly() {
        assertThat(engine(config("https://api.minimax.io/v1", "k", "v1")).supportedModes())
                .containsExactly(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("isAvailable：需 base-url + api-key + group-id + voice-id 都就绪")
    void isAvailable() {
        assertThat(engine(config("https://api.minimax.io/v1", "", "v1")).isAvailable()).isFalse();
        assertThat(engine(config("https://api.minimax.io/v1", "k", "")).isAvailable()).isFalse();
        TtsPluginConfig noGroup = config("https://api.minimax.io/v1", "k", "v1");
        noGroup.getMinimax().setGroupId("");
        assertThat(engine(noGroup).isAvailable()).isFalse();
        assertThat(engine(config("https://api.minimax.io/v1", "k", "v1")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("请求体：text/model/voice_setting.voice_id/audio_setting/output_format=hex，端点 /t2a_v2?GroupId=")
    void requestBody() throws Exception {
        when(direct.exchange(eq("https://api.minimax.io/v1/t2a_v2?GroupId=" + GROUP_ID), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(json(AUDIO_HEX));

        engine(config("https://api.minimax.io/v1/", "k", "English_expressive_narrator"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("你好", "ci", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("model")).isEqualTo("speech-2.8-hd");
        assertThat(body.get("text")).isEqualTo("你好");
        assertThat(body.get("output_format")).isEqualTo("hex");
        assertThat(voiceSetting(body).get("voice_id")).isEqualTo("English_expressive_narrator");
        assertThat(audioSetting(body)).containsEntry("format", "mp3").containsEntry("sample_rate", 32000);
        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer k");
    }

    @Test
    @DisplayName("delivery 关键词映射到情绪枚举（angry → angry）")
    void deliveryMapsToEmotion() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_HEX));
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An old woman", "very angry and cold", null, null, 0L, 1, null, null);

        engine(config("https://h/v1", "k", "v1")).synthesize(NarrationVoiceMode.VOICE_DESIGN, req);

        assertThat(voiceSetting(capturedBody(direct)).get("emotion")).isEqualTo("angry");
    }

    @Test
    @DisplayName("delivery 未命中关键词时回退配置 emotion；非法配置值则不下发")
    void fallsBackToConfiguredEmotion() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_HEX));
        TtsPluginConfig cfg = config("https://h/v1", "k", "v1");
        cfg.getMinimax().setEmotion("calm");

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("t", "ci", null));

        assertThat(voiceSetting(capturedBody(direct)).get("emotion")).isEqualTo("calm");
    }

    @Test
    @DisplayName("无 delivery 命中且无配置 emotion → 不下发 emotion 字段")
    void omitsEmotionWhenNone() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_HEX));

        engine(config("https://h/v1", "k", "v1")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("t", "ci", null));

        assertThat(voiceSetting(capturedBody(direct)).containsKey("emotion")).isFalse();
    }

    @Test
    @DisplayName("响应 hex 解码为音频字节，contentType=audio/mpeg")
    void decodesHexAudio() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_HEX));

        NarrationAudio audio = engine(config("https://h/v1", "k", "v1"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        assertThat(audio.data()).containsExactly(0x52, 0x49, 0x46, 0x46);
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("base_resp.status_code 非 0 → 受控异常，且不泄露 api-key")
    void nonZeroStatusThrows() {
        String resp = "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"auth failed\"}}";
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(jsonRaw(resp));

        assertThatThrownBy(() -> engine(config("https://h/v1", "sk-secret", "v1"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("1004")
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate")
    void usesProxy() {
        TtsPluginConfig cfg = config("https://h/v1", "k", "v1");
        cfg.getMinimax().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(json(AUDIO_HEX));

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "ci", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MiniMaxNarrationEngine engine(TtsPluginConfig cfg) {
        return new MiniMaxNarrationEngine(cfg, direct, proxy, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey, String voiceId) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Minimax mm = new TtsPluginConfig.Minimax();
        mm.setBaseUrl(baseUrl);
        mm.setApiKey(apiKey);
        mm.setGroupId(GROUP_ID);
        mm.setVoiceId(voiceId);
        cfg.setMinimax(mm);
        return cfg;
    }

    private static ResponseEntity<byte[]> json(String audioHex) {
        return jsonRaw("{\"data\":{\"audio\":\"" + audioHex + "\",\"status\":2},\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}");
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
    private static Map<String, Object> voiceSetting(Map<String, Object> body) {
        return (Map<String, Object>) body.get("voice_setting");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> audioSetting(Map<String, Object> body) {
        return (Map<String, Object>) body.get("audio_setting");
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
