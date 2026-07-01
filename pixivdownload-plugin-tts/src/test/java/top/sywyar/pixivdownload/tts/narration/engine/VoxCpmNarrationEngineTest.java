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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("VoxCPM 朗读引擎")
class VoxCpmNarrationEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageResolver MESSAGES = TestMessageResolver.INSTANCE;

    private final RestTemplate direct = mock(RestTemplate.class);
    private final RestTemplate proxy = mock(RestTemplate.class);
    private final RestTemplate directProbe = mock(RestTemplate.class);
    private final RestTemplate proxyProbe = mock(RestTemplate.class);

    @Test
    @DisplayName("supportedModes：VoxCPM 支持 voice-design / 可控克隆 / Hi-Fi 续写三种模式")
    void supportedModesCoverAllThree() {
        assertThat(engine(config("http://h/v1", "")).supportedModes())
                .containsExactlyInAnyOrder(NarrationVoiceMode.VOICE_DESIGN,
                        NarrationVoiceMode.CLONE, NarrationVoiceMode.HIFI_CLONE);
    }

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
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setUseProxy(true);
        when(proxyProbe.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.OK));

        assertThat(engine(cfg).isReachable()).isTrue();

        verify(proxyProbe).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Void.class));
        verifyNoInteractions(directProbe);
    }

    @Test
    @DisplayName("请求体：controlInstruction 非空时 input 为 (描述)正文，默认不下发 voice，model/response_format 来自配置")
    void requestBodyWithControlInstruction() throws Exception {
        when(direct.exchange(eq("http://127.0.0.1:8000/v1/audio/speech"), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(wav());

        engine(config("http://127.0.0.1:8000/v1/", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("你好世界", "An elderly woman, low and cold voice", null));

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("input")).isEqualTo("(An elderly woman, low and cold voice)你好世界");
        assertThat(body.containsKey("voice")).isFalse();
        assertThat(body.get("model")).isEqualTo("openbmb/VoxCPM2");
        assertThat(body.get("response_format")).isEqualTo("wav");
    }

    @Test
    @DisplayName("请求体：配了 voice 名时透传该值")
    void requestBodyUsesConfiguredVoice() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setVoice("alloy");

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedBody(direct).get("voice")).isEqualTo("alloy");
    }

    @Test
    @DisplayName("请求体：voice 配置留空 / 空白时不下发 voice 字段")
    void requestBodyBlankVoiceOmitsField() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setVoice("   ");

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedBody(direct).containsKey("voice")).isFalse();
    }

    @Test
    @DisplayName("请求体：controlInstruction 为空 / 空白时 input 仅正文")
    void requestBodyWithoutControlInstruction() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("仅正文", "   ", null));

        assertThat(capturedBody(direct).get("input")).isEqualTo("仅正文");
    }

    @Test
    @DisplayName("可控克隆（CLONE）：input 只含 delivery，body 带 ref_audio(data URI) / max_new_tokens，绝不下发 ref_text")
    void cloneModeBuildsReferenceBody() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子句");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman, low and cold voice", "angry", null, null, 9L, 1, null, ref);

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("input")).isEqualTo("(angry)原句");
        assertThat((String) body.get("ref_audio")).startsWith("data:audio/wav;base64,");
        assertThat(body.containsKey("ref_text")).isFalse();
        assertThat(body.get("max_new_tokens")).isEqualTo(4096);
    }

    @Test
    @DisplayName("Hi-Fi 续写（clone-mode=hifi 且参考音带转录）：body 同时带 ref_audio 与 ref_text")
    void hifiCloneSendsRefText() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setCloneMode("hifi");
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子句");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        engine(cfg).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        // Hi-Fi 续写忽略括号控制，input 必须是干净正文（不拼 (delivery) 控制前缀），三种请求体互不污染。
        assertThat(body.get("input")).isEqualTo("原句");
        assertThat((String) body.get("ref_audio")).startsWith("data:audio/wav;base64,");
        assertThat(body.get("ref_text")).isEqualTo("种子句");
        assertThat(body.get("max_new_tokens")).isEqualTo(4096);
    }

    @Test
    @DisplayName("Hi-Fi 续写：参考音无转录时降回可控克隆（不下发 ref_text）")
    void hifiFallsBackToControllableWithoutTranscript() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setCloneMode("hifi");
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "   ");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        engine(cfg).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat((String) body.get("ref_audio")).startsWith("data:audio/wav;base64,");
        assertThat(body.containsKey("ref_text")).isFalse();
    }

    @Test
    @DisplayName("克隆全局关闭（enable-clone=false）：即便配了参考音也退回内联 voice-design，body 不带 ref_audio，但仍带 max_new_tokens 防跑飞")
    void cloneDisabledFallsBackToVoiceDesign() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setEnableClone(false);
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1}, "audio/wav", "x");
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        engine(cfg).synthesize(NarrationVoiceMode.CLONE, req);

        Map<String, Object> body = capturedBody(direct);
        assertThat(body.get("input")).isEqualTo("(An elderly woman)原句");
        assertThat(body.containsKey("ref_audio")).isFalse();
        assertThat(body.containsKey("ref_text")).isFalse();
        assertThat(body.get("max_new_tokens")).isEqualTo(4096);
    }

    @Test
    @DisplayName("voice-design 请求也带 max_new_tokens 上限（防自回归停止符不触发的跑飞）")
    void voiceDesignAlsoSendsMaxNewTokens() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("正文", "", null));

        assertThat(capturedBody(direct).get("max_new_tokens")).isEqualTo(4096);
    }

    @Test
    @DisplayName("超短输入（仅单字）收敛 max_new_tokens 上限，避免长时间空白")
    void shortInputCapsMaxNewTokens() throws Exception {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "")).synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of("吗？", "", null));

        assertThat(capturedBody(direct).get("max_new_tokens")).isEqualTo(1024);
    }

    @Test
    @DisplayName("配了 api-key 时带 Bearer 头")
    void bearerHeaderWhenApiKeyConfigured() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", "sk-secret-xyz"))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-secret-xyz");
    }

    @Test
    @DisplayName("未配 api-key 时不带 Authorization 头")
    void noBearerHeaderWhenApiKeyBlank() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(config("http://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(capturedHeaders(direct).getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("返回 byte[] + contentType（响应头优先）")
    void returnsAudioWithContentTypeFromHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[]{1, 2, 3}, headers, 200));

        NarrationAudio audio = engine(config("http://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(audio.data()).containsExactly(1, 2, 3);
        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("contentType 缺响应头时按 response-format 推断")
    void contentTypeInferredFromFormat() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[]{9}, new HttpHeaders(), 200));

        NarrationAudio audio = engine(config("http://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        assertThat(audio.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("use-proxy=true 走代理 RestTemplate，不碰直连")
    void usesProxyTemplateWhenConfigured() {
        TtsPluginConfig cfg = config("http://h/v1", "");
        cfg.getVoxcpm().setUseProxy(true);
        when(proxy.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenReturn(wav());

        engine(cfg).synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null));

        verify(proxy).exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class));
        verifyNoInteractions(direct);
    }

    @Test
    @DisplayName("空响应抛受控异常")
    void emptyResponseThrows() {
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], new HttpHeaders(), 200));

        assertThatThrownBy(() -> engine(config("http://h/v1", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null)))
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
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(apiKey);
    }

    @Test
    @DisplayName("非 2xx 错误回显参考音 data URI / 转录时一并脱敏，不泄露 base64 与 ref_text")
    void httpErrorRedactsReferenceAudioAndTranscript() {
        String base64 = "QUJDREVGR0hJSktMTU5PUFFSU1Q=";
        String transcript = "这是用户上传的参考音转录原话";
        String echo = "{\"error\":\"bad\",\"ref_audio\":\"data:audio/wav;base64," + base64
                + "\",\"ref_text\":\"" + transcript + "\"}";
        RestClientResponseException ex = new RestClientResponseException(
                "err", 400, "Bad Request", null, echo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(direct.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class))).thenThrow(ex);
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", transcript);
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                "原句", "An elderly woman", "angry", null, null, 9L, 1, null, ref);

        assertThatThrownBy(() -> engine(config("http://h/v1", "")).synthesize(NarrationVoiceMode.CLONE, req))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(base64)
                .hasMessageNotContaining(transcript);
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
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class)
                .hasMessageContaining("***")
                .hasMessageNotContaining(apiKey);
    }

    @Test
    @DisplayName("base-url 未配置直接抛受控异常，不触网")
    void unavailableThrowsWithoutNetwork() {
        assertThatThrownBy(() -> engine(config("", ""))
                .synthesize(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceRequest.of("t", "", null)))
                .isInstanceOf(NarrationVoiceException.class);
        verifyNoInteractions(direct, proxy);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private VoxCpmNarrationEngine engine(TtsPluginConfig cfg) {
        return new VoxCpmNarrationEngine(cfg, direct, proxy, directProbe, proxyProbe, MESSAGES);
    }

    private static TtsPluginConfig config(String baseUrl, String apiKey) {
        TtsPluginConfig cfg = new TtsPluginConfig();
        TtsPluginConfig.Voxcpm vox = new TtsPluginConfig.Voxcpm();
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
