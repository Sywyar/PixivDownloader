package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 小米 MiMo v2.5 朗读引擎：对接其云端 OpenAI 风格 {@code POST {base-url}/chat/completions} 接口。MiMo 把三件事映射成
 * 原生的三个模型 + chat 消息形态：
 * <ul>
 *   <li>{@link NarrationVoiceMode#VOICE_DESIGN}：
 *     <ul>
 *       <li>配了预置音色 {@code narration-tts.mimo.voice} → 用<b>预置音色模型</b>，{@code audio.voice}=该预置 id，
 *           {@code user} 消息放 {@code controlInstruction} 作风格微调；</li>
 *       <li>未配预置音色 → 用<b>描述音色模型</b>（voicedesign），{@code user} 消息=音色画像（必填，缺省给中性兜底）；</li>
 *     </ul>
 *   </li>
 *   <li>{@link NarrationVoiceMode#CLONE}：用<b>克隆模型</b>（voiceclone），参考音以 {@code data:audio/...;base64,...}
 *       放进 {@code audio.voice}，{@code user} 消息放 {@code delivery} 作逐句情绪。</li>
 * </ul>
 * 待合成文本一律放在 {@code assistant} 消息。鉴权用 {@code api-key} 请求头（非 Bearer）。响应是 JSON，音频 base64 位于
 * {@code choices[0].message.audio.data}（{@link MiMoTtsResponse}）。HTTP / 脱敏 / 计时 / JSON 解析样板由
 * {@link AbstractHttpNarrationEngine} 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class MiMoNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String CHAT_PATH = "/chat/completions";
    private static final String API_KEY_HEADER = "api-key";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** voicedesign 模型要求 {@code user} 描述非空；音色 / 描述都缺省时的中性兜底画像。 */
    private static final String DEFAULT_VOICE_DESIGN = "A clear, natural, neutral narrator voice.";

    private final TtsPluginConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public MiMoNarrationEngine(TtsPluginConfig config,
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
        return TtsPluginConfig.ENGINE_MIMO;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE);
    }

    @Override
    public boolean isAvailable() {
        // MiMo 是云服务且 base-url 默认即非空，故可用门禁取决于 api-key 是否已配置。
        TtsPluginConfig.Mimo mimo = config.getMimo();
        return mimo != null
                && mimo.getBaseUrl() != null && !mimo.getBaseUrl().isBlank()
                && mimo.getApiKey() != null && !mimo.getApiKey().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        TtsPluginConfig.Mimo mimo = config.getMimo();
        if (mimo == null || mimo.getBaseUrl() == null || mimo.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        NarrationVoiceMode effective = resolveMode(mode, req, mimo);
        String format = normalizeFormat(mimo.getResponseFormat());
        String apiKey = mimo.getApiKey();
        boolean useProxy = mimo.isUseProxy();
        MiMoTtsRequest body = buildRequest(effective, req, mimo, text, format);

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(mimo.getBaseUrl()) + CHAT_PATH;
        log.debug(forLog("narration.tts.log.synthesize.start", body.model(), format, useProxy, text.length()));

        long startNs = System.nanoTime();
        MiMoTtsResponse response =
                postForJson(restTemplate, url, apiKeyHeaders(apiKey), serialize(body, apiKey), apiKey, MiMoTtsResponse.class);
        byte[] audio = decodeAudio(response, apiKey);
        String contentType = audioMimeForFormat(format);
        log.info(forLog("narration.tts.log.synthesize.done", audio.length, contentType, elapsedMs(startNs)));
        return new NarrationAudio(audio, contentType);
    }

    /**
     * 收敛实际合成模式：无可用参考音 / {@code enable-clone=false} / 请求即 {@code VOICE_DESIGN} → {@code VOICE_DESIGN}；
     * 其余配了参考音的情形 → {@code CLONE}。MiMo 无「Hi-Fi 续写」概念，故只在 voice-design 与 clone 间二选一。
     */
    static NarrationVoiceMode resolveMode(NarrationVoiceMode requested, NarrationVoiceRequest req,
                                          TtsPluginConfig.Mimo mimo) {
        boolean haveRef = req.hasReferenceVoice() && mimo.isEnableClone();
        return (!haveRef || requested == NarrationVoiceMode.VOICE_DESIGN)
                ? NarrationVoiceMode.VOICE_DESIGN : NarrationVoiceMode.CLONE;
    }

    /** 按模式组装 chat 请求体：选模型、放 user 风格 / 描述、assistant 文本、audio.voice（预置 id 或克隆 data URI）。 */
    private static MiMoTtsRequest buildRequest(NarrationVoiceMode mode, NarrationVoiceRequest req,
                                               TtsPluginConfig.Mimo mimo, String text, String format) {
        if (mode == NarrationVoiceMode.CLONE) {
            String style = NarrationSpeechText.blankToNull(req.delivery());
            MiMoTtsRequest.Audio audio = new MiMoTtsRequest.Audio(format, toDataUri(req.referenceVoice()), null);
            return new MiMoTtsRequest(mimo.getVoiceCloneModel(), messages(style, text), audio);
        }
        String presetVoice = NarrationSpeechText.blankToNull(mimo.getVoice());
        String description = NarrationSpeechText.blankToNull(req.controlInstruction());
        if (presetVoice != null) {
            // 预置音色：音色由 voice 决定，controlInstruction 作可选风格微调。
            MiMoTtsRequest.Audio audio = new MiMoTtsRequest.Audio(format, presetVoice, null);
            return new MiMoTtsRequest(mimo.getModel(), messages(description, text), audio);
        }
        // 描述音色：user 描述必填，缺省给中性兜底；不下发 voice。
        String userDesc = description == null ? DEFAULT_VOICE_DESIGN : description;
        MiMoTtsRequest.Audio audio = new MiMoTtsRequest.Audio(format, null, null);
        return new MiMoTtsRequest(mimo.getVoiceDesignModel(), messages(userDesc, text), audio);
    }

    /** {@code user}（可选风格 / 描述）+ {@code assistant}（待合成文本）消息序列；{@code userContent} 为空时只发 assistant。 */
    private static List<MiMoTtsRequest.Message> messages(String userContent, String text) {
        List<MiMoTtsRequest.Message> list = new ArrayList<>(2);
        if (userContent != null) {
            list.add(new MiMoTtsRequest.Message(ROLE_USER, userContent));
        }
        list.add(new MiMoTtsRequest.Message(ROLE_ASSISTANT, text));
        return list;
    }

    /** base64 解出音频字节；缺失 / 非法 base64 → 受控异常。 */
    private byte[] decodeAudio(MiMoTtsResponse response, String apiKey) {
        String data = response == null ? null : response.audioData();
        if (NarrationSpeechText.blankToNull(data) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data.trim());
            if (bytes.length == 0) {
                throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed", redact(safeMessage(e), apiKey)), e);
        }
    }

    /** JSON 请求头 + MiMo 的 {@code api-key} 鉴权头（非 Bearer）；key 空 / 空白则不带。 */
    private static HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(API_KEY_HEADER, apiKey.trim());
        }
        return headers;
    }

    /** 输出格式归一为 {@code wav} / {@code pcm16}，未知 / 空回退 {@code wav}。 */
    static String normalizeFormat(String responseFormat) {
        if (responseFormat == null) {
            return "wav";
        }
        String f = responseFormat.trim().toLowerCase(Locale.ROOT);
        return "pcm16".equals(f) ? "pcm16" : "wav";
    }
}
