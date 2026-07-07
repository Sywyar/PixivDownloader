package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * VoxCPM 朗读引擎：对接 VoxCPM（{@code vllm serve openbmb/VoxCPM2 --omni}）的 OpenAI 兼容音频接口
 * {@code POST {base-url}/audio/speech}。VoxCPM 是<b>外部服务</b>（需 GPU，用户自行 {@code vllm serve}），
 * 后端只作 HTTP 客户端，<b>绝不</b>内嵌 Python / GPU 模型。
 *
 * <p>HTTP / 脱敏 / 计时样板由 {@link AbstractHttpNarrationEngine} 承担；本类只保留 VoxCPM 的<b>线缆形态</b>：
 * 文本归一已由 {@code NarrationAudioService} 统一完成（本类只组装 {@code (style)正文} 与请求体）。
 *
 * <p><b>一致性：内联 voice-design。</b> VoxCPM 没有独立的 control-instruction 字段，音色描述用其
 * {@code (描述)正文} voice-design 语法拼进 {@code input}。{@code voice} 取自配置 {@code narration-tts.voxcpm.voice}：
 * 它不决定音色（VoxCPM 音色由内联描述 / 参考音承载），voice-design / 克隆模型通常<b>没有任何预设音色</b>，<b>默认留空
 * → 整个 {@code voice} 字段不下发</b>。
 *
 * <p><b>三种 {@link NarrationVoiceMode 模式}：</b>
 * <ul>
 *   <li>{@link NarrationVoiceMode#VOICE_DESIGN}：括号放 {@code controlInstruction}，不带参考音；</li>
 *   <li>{@link NarrationVoiceMode#CLONE}（可控克隆）：括号只放 {@code delivery}，带 {@code ref_audio}、<b>不带</b>
 *       {@code ref_text}——克隆音色又保住逐句情绪；</li>
 *   <li>{@link NarrationVoiceMode#HIFI_CLONE}（Hi-Fi 续写）：带 {@code ref_audio} + {@code ref_text}，最高保真但
 *       VoxCPM2 在此模式下<b>忽略</b> {@code (delivery)} 控制，故 {@code input} 用<b>干净正文</b>（不拼任何
 *       {@code (style)} 控制前缀，避免被服务端当成目标文本）。仅在 {@code clone-mode=hifi} 且参考音确有转录时启用。</li>
 * </ul>
 * 实际模式由 {@link #resolveMode} 按「请求模式 + 是否配参考音 + {@code enable-clone} / {@code clone-mode} + 参考音
 * 是否带转录」收敛；无参考音 / {@code enable-clone=false} 一律退回 voice-design。
 *
 * <p>是否走 HTTP 代理由 {@code narration-tts.voxcpm.use-proxy} 决定（per-config，独立于全局 {@code proxy.enabled}）。
 * 响应是二进制音频，按 byte[] 读取；失败抛 {@link NarrationVoiceException}，消息已脱敏、绝不含 API Key。
 */
@Component
public class VoxCpmNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String SPEECH_PATH = "/audio/speech";
    /** OpenAI 兼容存活探测路径：GET {@code {base-url}/models}。 */
    private static final String MODELS_PATH = "/models";

    /** 视为「超短输入」的可发音字符数上限：≤ 此数即收敛 token 上限（VoxCPM 在仅 1 字输入上易塌缩成长空白且不发声）。 */
    private static final int SHORT_INPUT_MAX_CHARS = 1;
    /** 短输入的 {@code max_new_tokens} 收敛上限：把孤立单字输入的最坏空白从数分钟压成短促一瞬，仍足够覆盖 1 字发音。 */
    private static final int SHORT_INPUT_TOKEN_CAP = 1024;

    private final TtsPluginConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;
    private final RestTemplate directProbeRestTemplate;
    private final RestTemplate proxyProbeRestTemplate;

    public VoxCpmNarrationEngine(TtsPluginConfig config,
                                 @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                 @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                 @Qualifier("narrationTtsProbeRestTemplate") RestTemplate directProbeRestTemplate,
                                 @Qualifier("narrationTtsProbeProxyRestTemplate") RestTemplate proxyProbeRestTemplate,
                                 MessageResolver messages) {
        super(messages);
        this.config = config;
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
        this.directProbeRestTemplate = directProbeRestTemplate;
        this.proxyProbeRestTemplate = proxyProbeRestTemplate;
    }

    @Override
    public String id() {
        return TtsPluginConfig.ENGINE_VOXCPM;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE, NarrationVoiceMode.HIFI_CLONE);
    }

    @Override
    public boolean isAvailable() {
        TtsPluginConfig.Voxcpm vox = config.getVoxcpm();
        return vox != null && vox.getBaseUrl() != null && !vox.getBaseUrl().isBlank();
    }

    /**
     * 真实可达探测：在配置就绪的前提下，<b>带上已配置 api-key</b> 对 VoxCPM 的 OpenAI 兼容服务发一次<b>短超时</b>
     * GET {@code {base-url}/models}。
     * <ul>
     *   <li>未配置 base-url → 直接 {@code false}，<b>不</b>触网；</li>
     *   <li>2xx → 在线、凭证被接受，视为可用；</li>
     *   <li>任何非 2xx（401/403 凭证被拒、404 路径错、5xx 等）或连接被拒 / 超时 / DNS 失败 → 不可用。</li>
     * </ul>
     * 因探测已带上配置的凭证，401/403 意味着同样凭证下真实合成也会失败，故一并按不可用处理。
     * 失败仅以 debug 记一条脱敏日志（绝不含 api-key），不抛异常。
     */
    @Override
    public boolean isReachable() {
        TtsPluginConfig.Voxcpm vox = config.getVoxcpm();
        if (vox == null || vox.getBaseUrl() == null || vox.getBaseUrl().isBlank()) {
            return false;
        }
        String apiKey = vox.getApiKey();
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
        RestTemplate restTemplate = vox.getUseProxy() ? proxyProbeRestTemplate : directProbeRestTemplate;
        String url = modelsUrl(vox.getBaseUrl());
        try {
            ResponseEntity<Void> response =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Void.class);
            boolean ok = response.getStatusCode().is2xxSuccessful();
            if (log.isDebugEnabled()) {
                if (ok) {
                    log.debug(forLog("narration.tts.log.reachable.ok", url));
                } else {
                    log.debug(forLog("narration.tts.log.reachable.failed",
                            "HTTP " + response.getStatusCode().value()));
                }
            }
            return ok;
        } catch (RestClientException e) {
            // RestClientResponseException（非 2xx）是其子类，连同连接失败 / 超时一并视为不可达。
            if (log.isDebugEnabled()) {
                log.debug(forLog("narration.tts.log.reachable.failed", redact(safeMessage(e), apiKey)));
            }
            return false;
        }
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        TtsPluginConfig.Voxcpm vox = config.getVoxcpm();
        if (vox == null || vox.getBaseUrl() == null || vox.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        NarrationVoiceMode effective = resolveMode(mode, req, vox);
        // 三种模式的 input 互不污染：voice-design 拼基底画像、可控克隆只拼 delivery、Hi-Fi 续写<b>不拼任何控制前缀</b>
        // （VoxCPM2 在续写模式下由参考音 + 转录主导、会忽略括号控制，混入 (delivery) 反而可能被当成目标文本干扰续写）。
        String style = switch (effective) {
            case VOICE_DESIGN -> req.controlInstruction();
            case CLONE -> req.delivery();
            case HIFI_CLONE -> null;
        };
        String input = buildInput(text, style);

        String format = normalizeFormat(vox.getResponseFormat());
        String voice = resolveVoice(vox.getVoice());
        String apiKey = vox.getApiKey();
        boolean useProxy = vox.getUseProxy();
        Integer maxNewTokens = cappedMaxNewTokens(text, vox.getMaxNewTokens());
        VoxCpmSpeechRequest body = switch (effective) {
            case VOICE_DESIGN -> VoxCpmSpeechRequest.voiceDesign(vox.getModel(), input, voice, format, maxNewTokens);
            case CLONE -> VoxCpmSpeechRequest.controllableClone(vox.getModel(), input, voice, format,
                    toDataUri(req.referenceVoice()), maxNewTokens);
            case HIFI_CLONE -> VoxCpmSpeechRequest.hifiClone(vox.getModel(), input, voice, format,
                    toDataUri(req.referenceVoice()), req.referenceVoice().text().trim(), maxNewTokens);
        };

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = speechUrl(vox.getBaseUrl());
        log.debug(forLog("narration.tts.log.synthesize.start", vox.getModel(), format, useProxy, input.length()));
        return postForAudio(restTemplate, url, bearerHeaders(apiKey), serialize(body, apiKey), format, apiKey);
    }

    /**
     * 收敛实际合成模式：
     * <ul>
     *   <li>无可用参考音 / {@code enable-clone=false} / 请求即 {@code VOICE_DESIGN} → {@link NarrationVoiceMode#VOICE_DESIGN}；</li>
     *   <li>请求 {@code HIFI_CLONE}，或请求 {@code CLONE} 且 {@code clone-mode=hifi}，且参考音<b>带转录</b> →
     *       {@link NarrationVoiceMode#HIFI_CLONE}；转录为空则降回 {@link NarrationVoiceMode#CLONE}；</li>
     *   <li>其余配了参考音的情形 → {@link NarrationVoiceMode#CLONE}（可控克隆）。</li>
     * </ul>
     */
    static NarrationVoiceMode resolveMode(NarrationVoiceMode requested, NarrationVoiceRequest req,
                                          TtsPluginConfig.Voxcpm vox) {
        boolean haveRef = req.hasReferenceVoice() && vox.getEnableClone();
        if (!haveRef || requested == NarrationVoiceMode.VOICE_DESIGN) {
            return NarrationVoiceMode.VOICE_DESIGN;
        }
        boolean cloneModeHifi = TtsPluginConfig.CLONE_MODE_HIFI
                .equalsIgnoreCase(vox.getCloneMode() == null ? "" : vox.getCloneMode().trim());
        boolean wantHifi = requested == NarrationVoiceMode.HIFI_CLONE
                || (requested == NarrationVoiceMode.CLONE && cloneModeHifi);
        boolean refHasText = NarrationSpeechText.blankToNull(req.referenceVoice().text()) != null;
        return wantHifi && refHasText ? NarrationVoiceMode.HIFI_CLONE : NarrationVoiceMode.CLONE;
    }

    /** 拼接 VoxCPM 的 {@code (style)正文} 输入；{@code text} 已由集中层归一，{@code style} 为空时仅正文。 */
    static String buildInput(String text, String style) {
        String body = text == null ? "" : text;
        String prefix = style == null ? "" : style.trim();
        return prefix.isEmpty() ? body : "(" + prefix + ")" + body;
    }

    /**
     * 短输入 token 上限收敛：仅 ≤ {@value #SHORT_INPUT_MAX_CHARS} 个可发音字的超短文本（如孤立的「吗？」），
     * 自回归模型易塌缩成长时间空白且不发声，强制一个低 {@code max_new_tokens} 上限把最坏空白压成短促一瞬而非数分钟；
     * 普通长度维持配置上限（{@code <=0} 表示不限制）。按<b>原句文本</b>计可发音字数，<b>不含</b> {@code (style)} 控制前缀。
     */
    private static Integer cappedMaxNewTokens(String text, int configured) {
        if (!NarrationSpeechText.isShortInput(text, SHORT_INPUT_MAX_CHARS)) {
            return positiveOrNull(configured);
        }
        return configured > 0 ? Math.min(configured, SHORT_INPUT_TOKEN_CAP) : SHORT_INPUT_TOKEN_CAP;
    }

    /** token 上限：{@code <=0} → {@code null}（不下发上限）。 */
    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    /**
     * voice id 归一：空 / 空白 → {@code null}（{@code NON_NULL} 序列化下<b>不下发</b> {@code voice} 字段，
     * 适配没有预设音色、带任何 voice 值都报 {@code Supported: none} 的 voice-design / 克隆服务端）。
     */
    static String resolveVoice(String voice) {
        return NarrationSpeechText.blankToNull(voice);
    }

    /** 输出格式归一为受支持的 {@code wav} / {@code pcm}，未知 / 空回退 {@code wav}。 */
    static String normalizeFormat(String responseFormat) {
        if (responseFormat == null) {
            return "wav";
        }
        String f = responseFormat.trim().toLowerCase(Locale.ROOT);
        return "pcm".equals(f) ? "pcm" : "wav";
    }

    /** 在 base-url 后拼接 {@code /audio/speech}，自动处理结尾斜杠。 */
    private static String speechUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + SPEECH_PATH;
    }

    /** 在 base-url 后拼接 {@code /models}（存活探测），自动处理结尾斜杠。 */
    private static String modelsUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + MODELS_PATH;
    }
}
