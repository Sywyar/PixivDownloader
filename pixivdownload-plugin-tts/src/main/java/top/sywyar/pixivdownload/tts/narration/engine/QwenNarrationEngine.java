package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;

/**
 * 阿里 DashScope（百炼）Qwen3-TTS 朗读引擎：对接其多模态语音合成端点
 * {@code POST {base-url}/services/aigc/multimodal-generation/generation}。Qwen3-TTS 无逐句自然语言音色 / 情绪通道——音色来自
 * 配置的预置 {@code narration-tts.qwen.voice}（如 {@code Cherry}），可选 {@code language-type} 提示文本语言以保证发音 / 语调。
 * 因此本引擎只支持 {@link NarrationVoiceMode#VOICE_DESIGN}；带参考音的 {@code CLONE} 请求由派发器降级为 {@code VOICE_DESIGN}
 * （Qwen 的声音复刻是独立的多步端点，不在本引擎范围内）。
 *
 * <p>鉴权用 {@code Authorization: Bearer <DASHSCOPE_KEY>}。响应是 JSON，音频以<b>临时签名 URL</b> 位于
 * {@code output.audio.url}（{@link QwenTtsResponse}），故本引擎在 JSON 响应后再发<b>一次 GET</b> 取音频字节。HTTP / 脱敏 /
 * 计时 / JSON 解析样板由 {@link AbstractHttpNarrationEngine} 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class QwenNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String GENERATION_PATH = "/services/aigc/multimodal-generation/generation";

    private final TtsPluginConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public QwenNarrationEngine(TtsPluginConfig config,
                               @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                               @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                               MessageResolver messages) {
        super(messages);
        this.config = config;
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
    }

    @Override
    public String id() {
        return TtsPluginConfig.ENGINE_QWEN;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Override
    public boolean isAvailable() {
        // 云服务且 base-url 默认即非空，可用门禁取决于 api-key 是否已配置（voice 有默认值）。
        TtsPluginConfig.Qwen qwen = config.getQwen();
        return qwen != null
                && qwen.getBaseUrl() != null && !qwen.getBaseUrl().isBlank()
                && qwen.getApiKey() != null && !qwen.getApiKey().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        TtsPluginConfig.Qwen qwen = config.getQwen();
        if (qwen == null || qwen.getBaseUrl() == null || qwen.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String apiKey = qwen.getApiKey();
        boolean useProxy = qwen.isUseProxy();
        QwenTtsRequest.Input input = new QwenTtsRequest.Input(
                text, NarrationSpeechText.blankToNull(qwen.getVoice()), NarrationSpeechText.blankToNull(qwen.getLanguageType()));
        QwenTtsRequest body = new QwenTtsRequest(qwen.getModel(), input);

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(qwen.getBaseUrl()) + GENERATION_PATH;
        log.debug(forLog("narration.tts.log.synthesize.start", qwen.getModel(), qwen.getVoice(), useProxy, text.length()));

        long startNs = System.nanoTime();
        // bearerHeaders 已带 Content-Type application/json + Authorization Bearer（DashScope 用标准 Bearer 鉴权）。
        QwenTtsResponse response =
                postForJson(restTemplate, url, bearerHeaders(apiKey), serialize(body, apiKey), apiKey, QwenTtsResponse.class);
        String audioUrl = resolveAudioUrl(response, apiKey);
        NarrationAudio audio = fetchAudio(restTemplate, audioUrl, apiKey);
        log.info(forLog("narration.tts.log.synthesize.done",
                audio.data().length, audio.contentType(), elapsedMs(startNs)));
        return audio;
    }

    /** 校验业务状态并取临时音频 URL；缺 URL / 带错误码 → 受控异常（脱敏）。 */
    private String resolveAudioUrl(QwenTtsResponse response, String apiKey) {
        if (response == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        String audioUrl = NarrationSpeechText.blankToNull(response.audioUrl());
        if (audioUrl == null) {
            String code = NarrationSpeechText.blankToNull(response.errorCode());
            if (code != null) {
                String detail = redact("Qwen " + code
                        + (response.errorMessage() == null ? "" : ": " + response.errorMessage()), apiKey);
                throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), null);
            }
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        return audioUrl;
    }

    /** 再发一次 GET 取临时 URL 上的音频字节（OSS 签名 URL，无需鉴权）；失败 / 空音频 → 受控异常。 */
    private NarrationAudio fetchAudio(RestTemplate restTemplate, String audioUrl, String apiKey) {
        URI audioUri;
        try {
            audioUri = URI.create(audioUrl);
        } catch (IllegalArgumentException e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed", "invalid audio url"), e);
        }
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(audioUri, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            byte[] audio = resp.getBody();
            if (audio == null || audio.length == 0) {
                throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
            }
            return new NarrationAudio(audio, resolveContentType(resp, "wav"));
        } catch (RestClientResponseException e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed",
                            redact(audioDownloadFailureDetail(audioUri, e), apiKey)), e);
        } catch (RestClientException e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed",
                            redact(audioDownloadFailureDetail(audioUri, e), apiKey)), e);
        }
    }

    private static String audioDownloadFailureDetail(URI audioUri, RestClientException e) {
        String host = audioUri.getHost();
        String endpoint = host == null || host.isBlank() ? "temporary audio url" : "temporary audio url host " + host;
        if (e instanceof RestClientResponseException responseException) {
            return "audio download failed: HTTP " + responseException.getRawStatusCode() + " from " + endpoint;
        }
        return "audio download failed: " + e.getClass().getSimpleName() + " from " + endpoint;
    }
}
