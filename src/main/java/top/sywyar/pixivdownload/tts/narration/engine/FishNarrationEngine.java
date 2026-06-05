package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fish Audio 朗读引擎：对接其云端 {@code POST {base-url}/v1/tts}。Fish 不做「纯文本描述生成音色」，音色来自配置的
 * {@code narration-tts.fish.reference-id}（Fish 控制台预创建 / 上传的声音模型）；逐句情绪以行内 {@code (delivery)}
 * 标记注入正文（Fish 支持文本内自然语言情绪标记）。因此本引擎只支持 {@link NarrationVoiceMode#VOICE_DESIGN}——
 * 带参考音的 {@code CLONE} 请求会被派发器降级为 {@code VOICE_DESIGN}（本机参考音字节克隆需先在 Fish 侧上传换取
 * reference_id，不在本引擎内联范围内）。
 *
 * <p>鉴权用 {@code Authorization: Bearer}，模型经 {@code model} 请求头指定。响应是二进制音频。HTTP / 脱敏 / 计时
 * 样板由 {@link AbstractHttpNarrationEngine} 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class FishNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String TTS_PATH = "/v1/tts";
    private static final String MODEL_HEADER = "model";

    private final NarrationTtsConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public FishNarrationEngine(NarrationTtsConfig config,
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
        return NarrationTtsConfig.ENGINE_FISH;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Override
    public boolean isAvailable() {
        // 云服务且 base-url 默认即非空，可用门禁取决于 api-key 是否已配置。
        NarrationTtsConfig.Fish fish = config.getFish();
        return fish != null
                && fish.getBaseUrl() != null && !fish.getBaseUrl().isBlank()
                && fish.getApiKey() != null && !fish.getApiKey().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        NarrationTtsConfig.Fish fish = config.getFish();
        if (fish == null || fish.getBaseUrl() == null || fish.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String format = normalizeFormat(fish.getFormat());
        String apiKey = fish.getApiKey();
        boolean useProxy = fish.isUseProxy();
        String input = withDeliveryMarker(text, req.delivery());
        FishTtsRequest body = new FishTtsRequest(input, NarrationSpeechText.blankToNull(fish.getReferenceId()), format);

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(fish.getBaseUrl()) + TTS_PATH;
        log.debug(forLog("narration.tts.log.synthesize.start", fish.getModel(), format, useProxy, text.length()));
        return postForAudio(restTemplate, url, fishHeaders(apiKey, fish.getModel()), serialize(body, apiKey), format, apiKey);
    }

    /** 逐句情绪：delivery 非空 → 以 Fish 行内 {@code (delivery)} 标记前置正文；否则原样。 */
    static String withDeliveryMarker(String text, String delivery) {
        String d = NarrationSpeechText.blankToNull(delivery);
        return d == null ? text : "(" + d + ")" + text;
    }

    /** JSON + Bearer 头，并附 Fish 的 {@code model} 请求头（模型不在请求体里）。 */
    private static HttpHeaders fishHeaders(String apiKey, String model) {
        HttpHeaders headers = bearerHeaders(apiKey);
        if (model != null && !model.isBlank()) {
            headers.set(MODEL_HEADER, model.trim());
        }
        return headers;
    }

    /** 输出格式归一为受支持的 {@code mp3} / {@code wav} / {@code pcm} / {@code opus}，未知 / 空回退 {@code mp3}。 */
    static String normalizeFormat(String format) {
        if (format == null) {
            return "mp3";
        }
        String f = format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "wav", "pcm", "opus" -> f;
            default -> "mp3";
        };
    }
}
