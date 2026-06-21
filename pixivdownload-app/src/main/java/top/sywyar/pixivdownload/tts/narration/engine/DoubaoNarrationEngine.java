package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 字节火山引擎 豆包 / Seed-TTS 朗读引擎：对接其语音合成大模型的 HTTP 非流式「query」端点 {@code POST {base-url}/api/v1/tts}
 * （<b>不</b>走 WebSocket）。豆包无逐句自然语言音色通道——音色来自配置的预置 {@code narration-tts.doubao.voice-type}；逐句
 * 情绪经火山的 {@code emotion} + {@code enable_emotion} 控制（引擎先按逐句 {@code delivery} 关键词映射到火山情绪枚举，未命中
 * 再回退配置的 {@code emotion}；仅支持情绪的音色生效）。因此本引擎只支持 {@link NarrationVoiceMode#VOICE_DESIGN}；带参考音的
 * {@code CLONE} 请求由派发器降级为 {@code VOICE_DESIGN}（豆包声音复刻需单独训练换 voice_type，不在本引擎范围内）。
 *
 * <p>鉴权用火山特有的 {@code Authorization: Bearer;<access-token>}（<b>带分号、无空格</b>）。响应是 JSON、音频为
 * <b>base64</b> 位于 {@code data}（{@code code==3000} 为成功，{@link DoubaoTtsResponse}）。HTTP / 脱敏 / 计时 / JSON 解析
 * 样板由 {@link AbstractHttpNarrationEngine} 承担；access-token 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class DoubaoNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String TTS_PATH = "/api/v1/tts";
    private static final String OPERATION_QUERY = "query";
    private static final int SUCCESS_CODE = 3000;
    /** 调用方标识：火山要求 {@code user.uid} 非空，固定一个非敏感的本应用标识即可。 */
    private static final String UID = "pixiv-narration";

    /** 火山支持的情绪枚举（用于校验配置值 + delivery 关键词映射的目标集合）。 */
    private static final Set<String> EMOTIONS = Set.of(
            "happy", "sad", "angry", "scare", "hate", "surprise", "tear", "comfort",
            "pleased", "sorry", "annoyed", "serious", "conniving", "charming", "storytelling");

    private final NarrationTtsConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public DoubaoNarrationEngine(NarrationTtsConfig config,
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
        return NarrationTtsConfig.ENGINE_DOUBAO;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Override
    public boolean isAvailable() {
        // 云服务且 base-url 默认即非空，可用门禁取决于 appid + access-token + 预置 voice-type 是否都已配置。
        NarrationTtsConfig.Doubao doubao = config.getDoubao();
        return doubao != null
                && doubao.getBaseUrl() != null && !doubao.getBaseUrl().isBlank()
                && doubao.getAppId() != null && !doubao.getAppId().isBlank()
                && doubao.getAccessToken() != null && !doubao.getAccessToken().isBlank()
                && doubao.getVoiceType() != null && !doubao.getVoiceType().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        NarrationTtsConfig.Doubao doubao = config.getDoubao();
        if (doubao == null || doubao.getBaseUrl() == null || doubao.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String encoding = normalizeEncoding(doubao.getEncoding());
        String accessToken = doubao.getAccessToken();
        boolean useProxy = doubao.isUseProxy();
        String emotion = resolveEmotion(req.delivery(), doubao.getEmotion());
        DoubaoTtsRequest.App app = new DoubaoTtsRequest.App(doubao.getAppId(), accessToken, doubao.getCluster());
        DoubaoTtsRequest.Audio audio = new DoubaoTtsRequest.Audio(
                doubao.getVoiceType(), encoding, emotion, emotion == null ? null : Boolean.TRUE);
        DoubaoTtsRequest.Request request =
                new DoubaoTtsRequest.Request(UUID.randomUUID().toString(), text, OPERATION_QUERY);
        DoubaoTtsRequest body = new DoubaoTtsRequest(app, new DoubaoTtsRequest.User(UID), audio, request);

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(doubao.getBaseUrl()) + TTS_PATH;
        log.debug(forLog("narration.tts.log.synthesize.start", doubao.getVoiceType(), encoding, useProxy, text.length()));

        long startNs = System.nanoTime();
        DoubaoTtsResponse response =
                postForJson(restTemplate, url, doubaoHeaders(accessToken), serialize(body, accessToken), accessToken, DoubaoTtsResponse.class);
        byte[] data = decodeAudio(response, accessToken);
        String contentType = contentTypeForEncoding(encoding);
        log.info(forLog("narration.tts.log.synthesize.done", data.length, contentType, elapsedMs(startNs)));
        return new NarrationAudio(data, contentType);
    }

    /**
     * 选定情绪：先按逐句 {@code delivery} 关键词映射到火山情绪枚举，未命中再回退配置的 {@code emotion}（校验在枚举内），
     * 都没有则 {@code null}（不下发、用模型默认）。
     */
    static String resolveEmotion(String delivery, String configured) {
        String mapped = mapDeliveryToEmotion(delivery);
        if (mapped != null) {
            return mapped;
        }
        String c = NarrationSpeechText.blankToNull(configured);
        return c != null && EMOTIONS.contains(c.toLowerCase(Locale.ROOT)) ? c.toLowerCase(Locale.ROOT) : null;
    }

    /** 逐句 delivery 关键词 → 火山情绪枚举（best-effort，未命中返回 {@code null}）。 */
    private static String mapDeliveryToEmotion(String delivery) {
        String d = delivery == null ? "" : delivery.toLowerCase(Locale.ROOT);
        if (d.isBlank()) {
            return null;
        }
        if (containsAny(d, "happy", "joy", "cheer", "delight", "excit", "glad")) {
            return "happy";
        }
        if (containsAny(d, "sad", "sorrow", "melanchol", "grief", "cry", "tear")) {
            return "sad";
        }
        if (containsAny(d, "angry", "anger", "furious", "rage", "irritat")) {
            return "angry";
        }
        if (containsAny(d, "fear", "afraid", "scared", "terrified", "nervous", "anxious")) {
            return "scare";
        }
        if (containsAny(d, "disgust", "repuls", "hate")) {
            return "hate";
        }
        if (containsAny(d, "surpris", "shock", "astonish", "amazed")) {
            return "surprise";
        }
        if (containsAny(d, "calm", "gentle", "soft", "soothing", "serene", "tender", "comfort")) {
            return "comfort";
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 校验业务状态 + base64 解出音频字节；状态非 3000 / 无音频 / 非法 base64 → 受控异常。 */
    private byte[] decodeAudio(DoubaoTtsResponse response, String accessToken) {
        if (response == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        if (!response.ok()) {
            String detail = redact("Doubao code " + response.code()
                    + (response.message() == null ? "" : ": " + response.message()), accessToken);
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), null);
        }
        String data = response.data();
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
                    localized("narration.tts.error.request-failed", redact(safeMessage(e), accessToken)), e);
        }
    }

    /** JSON 请求头 + 火山特有的 {@code Authorization: Bearer;<token>}（带分号、无空格）；token 空 / 空白则不带。 */
    private static HttpHeaders doubaoHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null && !accessToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer;" + accessToken.trim());
        }
        return headers;
    }

    /** 编码归一为受支持的 {@code mp3} / {@code wav} / {@code pcm} / {@code ogg_opus}，未知 / 空回退 {@code mp3}。 */
    static String normalizeEncoding(String encoding) {
        if (encoding == null) {
            return "mp3";
        }
        String f = encoding.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "wav", "pcm", "ogg_opus" -> f;
            default -> "mp3";
        };
    }

    /** 编码 → contentType；{@code ogg_opus} → {@code audio/ogg}，其余复用基类映射。 */
    private static String contentTypeForEncoding(String encoding) {
        return "ogg_opus".equals(encoding) ? "audio/ogg" : audioMimeForFormat(encoding);
    }
}
