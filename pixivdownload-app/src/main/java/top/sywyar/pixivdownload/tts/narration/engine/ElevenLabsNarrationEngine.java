package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * ElevenLabs 朗读引擎：对接其云端 {@code POST {base-url}/v1/text-to-speech/{voice_id}}。ElevenLabs 不做「纯文本描述生成
 * 音色」，音色来自配置的 {@code narration-tts.elevenlabs.voice-id}（控制台预建 / 克隆的声音）；逐句情绪以 Eleven v3 的行内
 * <b>音频标签</b>（方括号自然语言指令，如 {@code [whispers]} / {@code [angry]}）注入正文。因此本引擎只支持
 * {@link NarrationVoiceMode#VOICE_DESIGN}——带参考音的 {@code CLONE} 请求会被派发器降级为 {@code VOICE_DESIGN}
 * （本机参考音字节克隆需先在 ElevenLabs 侧上传换取 voice_id，不在本引擎内联范围内）。
 *
 * <p>鉴权用 {@code xi-api-key} 请求头（<b>非</b> Bearer），输出格式经 query 参数 {@code output_format} 指定，响应是二进制
 * 音频（默认 mp3）。HTTP / 脱敏 / 计时样板由 {@link AbstractHttpNarrationEngine} 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class ElevenLabsNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String TTS_PATH = "/v1/text-to-speech/";
    private static final String API_KEY_HEADER = "xi-api-key";
    private static final String DEFAULT_OUTPUT_FORMAT = "mp3_44100_128";

    private final NarrationTtsConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public ElevenLabsNarrationEngine(NarrationTtsConfig config,
                                     @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                     @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                     AppMessages messages) {
        super(messages);
        this.config = config;
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
    }

    @Override
    public String id() {
        return NarrationTtsConfig.ENGINE_ELEVENLABS;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Override
    public boolean isAvailable() {
        // 云服务且 base-url 默认即非空，可用门禁取决于 api-key 与预置 voice-id 是否都已配置。
        NarrationTtsConfig.Elevenlabs el = config.getElevenlabs();
        return el != null
                && el.getBaseUrl() != null && !el.getBaseUrl().isBlank()
                && el.getApiKey() != null && !el.getApiKey().isBlank()
                && el.getVoiceId() != null && !el.getVoiceId().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        NarrationTtsConfig.Elevenlabs el = config.getElevenlabs();
        if (el == null || el.getBaseUrl() == null || el.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String voiceId = NarrationSpeechText.blankToNull(el.getVoiceId());
        if (voiceId == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String outputFormat = normalizeOutputFormat(el.getOutputFormat());
        String apiKey = el.getApiKey();
        boolean useProxy = el.isUseProxy();
        String input = withDeliveryTag(text, req.delivery());
        ElevenLabsTtsRequest body = new ElevenLabsTtsRequest(input, NarrationSpeechText.blankToNull(el.getModel()));

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(el.getBaseUrl()) + TTS_PATH + voiceId.trim() + "?output_format=" + outputFormat;
        String fallbackFormat = formatPrefix(outputFormat);
        log.debug(forLog("narration.tts.log.synthesize.start", el.getModel(), outputFormat, useProxy, text.length()));
        return postForAudio(restTemplate, url, apiKeyHeaders(apiKey), serialize(body, apiKey), fallbackFormat, apiKey);
    }

    /** 逐句情绪：delivery 非空 → 以 Eleven v3 行内 {@code [delivery]} 音频标签前置正文；否则原样。 */
    static String withDeliveryTag(String text, String delivery) {
        String d = NarrationSpeechText.blankToNull(delivery);
        return d == null ? text : "[" + d + "] " + text;
    }

    /** JSON + ElevenLabs 的 {@code xi-api-key} 鉴权头（非 Bearer）；key 空 / 空白则不带。 */
    private static HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(API_KEY_HEADER, apiKey.trim());
        }
        return headers;
    }

    /** 输出格式归一：空 / 空白回退默认 {@code mp3_44100_128}，否则去空白小写透传（由 ElevenLabs 服务端最终校验枚举值）。 */
    static String normalizeOutputFormat(String outputFormat) {
        String f = NarrationSpeechText.blankToNull(outputFormat);
        return f == null ? DEFAULT_OUTPUT_FORMAT : f.trim().toLowerCase(Locale.ROOT);
    }

    /** 取 {@code output_format} 的容器前缀（{@code mp3_44100_128} → {@code mp3}），供响应缺 Content-Type 时推断 MIME。 */
    static String formatPrefix(String outputFormat) {
        int underscore = outputFormat.indexOf('_');
        return underscore > 0 ? outputFormat.substring(0, underscore) : outputFormat;
    }
}
