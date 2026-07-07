package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * CosyVoice 朗读引擎：对接 CosyVoice 的<b>OpenAI 兼容音频接口</b> {@code POST {base-url}/audio/speech}（覆盖 vox-box /
 * Xinference 等常见自建部署）。CosyVoice 是<b>外部服务</b>（用户自行部署，需 GPU），后端只作 HTTP 客户端，<b>绝不</b>
 * 内嵌 Python / GPU 模型。
 *
 * <p><b>模式映射：</b>
 * <ul>
 *   <li>{@link NarrationVoiceMode#VOICE_DESIGN}：{@code instruct} 放角色音色画像（自然语言），可带配置的预置 {@code voice}；</li>
 *   <li>{@link NarrationVoiceMode#CLONE}：{@code reference_audio} 放参考音 base64、{@code reference_text} 放其转录，
 *       {@code instruct} 放逐句 {@code delivery}（音色取自参考音，情绪仍可控）。</li>
 * </ul>
 * 实际模式由「请求模式 + 是否配参考音 + {@code enable-clone}」收敛；无参考音 / 关闭克隆一律退回 voice-design。
 * 字段命名随部署略有差异（本实现采用社区常见命名）。响应是二进制音频，HTTP / 脱敏 / 计时样板由
 * {@link AbstractHttpNarrationEngine} 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class CosyVoiceNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String SPEECH_PATH = "/audio/speech";

    private final TtsPluginConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public CosyVoiceNarrationEngine(TtsPluginConfig config,
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
        return TtsPluginConfig.ENGINE_COSYVOICE;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE);
    }

    @Override
    public boolean isAvailable() {
        TtsPluginConfig.Cosyvoice cosy = config.getCosyvoice();
        return cosy != null && cosy.getBaseUrl() != null && !cosy.getBaseUrl().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        TtsPluginConfig.Cosyvoice cosy = config.getCosyvoice();
        if (cosy == null || cosy.getBaseUrl() == null || cosy.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        boolean clone = req.hasReferenceVoice() && cosy.getEnableClone() && mode != NarrationVoiceMode.VOICE_DESIGN;
        String format = normalizeFormat(cosy.getResponseFormat());
        String apiKey = cosy.getApiKey();
        boolean useProxy = cosy.getUseProxy();
        CosyVoiceSpeechRequest body;
        if (clone) {
            // 克隆：音色取自参考音，instruct 只放 delivery（切勿再塞基底画像，会与参考音打架）。
            body = CosyVoiceSpeechRequest.clone(cosy.getModel(), text, format,
                    NarrationSpeechText.blankToNull(req.delivery()),
                    toDataUri(req.referenceVoice()),
                    NarrationSpeechText.blankToNull(req.referenceVoice().text()));
        } else {
            body = CosyVoiceSpeechRequest.voiceDesign(cosy.getModel(), text,
                    NarrationSpeechText.blankToNull(cosy.getVoice()), format,
                    NarrationSpeechText.blankToNull(req.controlInstruction()));
        }

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(cosy.getBaseUrl()) + SPEECH_PATH;
        log.debug(forLog("narration.tts.log.synthesize.start", cosy.getModel(), format, useProxy, text.length()));
        return postForAudio(restTemplate, url, bearerHeaders(apiKey), serialize(body, apiKey), format, apiKey);
    }

    /** 输出格式归一为受支持的 {@code wav} / {@code mp3} / {@code pcm}，未知 / 空回退 {@code wav}。 */
    static String normalizeFormat(String responseFormat) {
        if (responseFormat == null) {
            return "wav";
        }
        String f = responseFormat.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "mp3", "pcm" -> f;
            default -> "wav";
        };
    }
}
