package top.sywyar.pixivdownload.tts.narration.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * MiniMax 朗读引擎：对接其云端 {@code POST {base-url}/t2a_v2}。MiniMax 无逐请求自然语言音色 / 情绪通道——音色来自配置的
 * 预置 {@code narration-tts.minimax.voice-id}，情绪取自固定枚举（引擎先尝试按逐句 {@code delivery} 关键词映射到该枚举，
 * 未命中再回退配置的 {@code emotion}）。因此本引擎只支持 {@link NarrationVoiceMode#VOICE_DESIGN}；带参考音的
 * {@code CLONE} 请求由派发器降级为 {@code VOICE_DESIGN}（MiniMax 的克隆 / 音色设计是独立的多步端点，不在本引擎范围内）。
 *
 * <p>鉴权需<b>两个</b>凭证：{@code Authorization: Bearer <api-key>} 头 + URL 查询参数 {@code ?GroupId=<group-id>}（缺 GroupId
 * 会被 MiniMax 以鉴权 / group 不匹配，如 {@code 1004} 拒绝）。响应是 JSON，音频为 {@code data.audio} 的<b>十六进制字符串</b>
 * （{@link MiniMaxTtsResponse}），需 hex 解码。HTTP / 脱敏 / 计时 / JSON 解析样板由 {@link AbstractHttpNarrationEngine}
 * 承担；api-key 绝不入日志 / 回显 / 异常消息。
 */
@Component
public class MiniMaxNarrationEngine extends AbstractHttpNarrationEngine implements NarrationVoiceEngine {

    private static final String T2A_PATH = "/t2a_v2";
    private static final String OUTPUT_FORMAT_HEX = "hex";

    /** MiniMax 支持的情绪枚举（用于校验配置值 + delivery 关键词映射的目标集合）。 */
    private static final Set<String> EMOTIONS = Set.of(
            "happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent", "whisper");

    private final TtsPluginConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;

    public MiniMaxNarrationEngine(TtsPluginConfig config,
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
        return TtsPluginConfig.ENGINE_MINIMAX;
    }

    @Override
    public Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Override
    public boolean isAvailable() {
        // 云服务：base-url 默认非空，且 MiniMax 必须有 api-key + group-id（鉴权双凭证）与预置 voice-id 才能合成。
        TtsPluginConfig.Minimax mm = config.getMinimax();
        return mm != null
                && mm.getBaseUrl() != null && !mm.getBaseUrl().isBlank()
                && mm.getApiKey() != null && !mm.getApiKey().isBlank()
                && mm.getGroupId() != null && !mm.getGroupId().isBlank()
                && mm.getVoiceId() != null && !mm.getVoiceId().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        TtsPluginConfig.Minimax mm = config.getMinimax();
        if (mm == null || mm.getBaseUrl() == null || mm.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String text = req == null ? null : req.text();
        if (NarrationSpeechText.blankToNull(text) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }
        // GroupId 与 api-key 同为必填鉴权凭证：缺失会被 MiniMax 拒绝（1004），按不可用提前失败。
        String groupId = NarrationSpeechText.blankToNull(mm.getGroupId());
        if (groupId == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }

        String format = normalizeFormat(mm.getFormat());
        String apiKey = mm.getApiKey();
        boolean useProxy = mm.isUseProxy();
        String emotion = resolveEmotion(req.delivery(), mm.getEmotion());
        MiniMaxTtsRequest.VoiceSetting voiceSetting =
                new MiniMaxTtsRequest.VoiceSetting(NarrationSpeechText.blankToNull(mm.getVoiceId()), emotion);
        MiniMaxTtsRequest.AudioSetting audioSetting =
                new MiniMaxTtsRequest.AudioSetting(format, mm.getSampleRate());
        MiniMaxTtsRequest body = new MiniMaxTtsRequest(
                mm.getModel(), text, voiceSetting, audioSetting, OUTPUT_FORMAT_HEX, Boolean.FALSE);

        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = trimTrailingSlash(mm.getBaseUrl()) + T2A_PATH + "?GroupId=" + groupId;
        log.debug(forLog("narration.tts.log.synthesize.start", mm.getModel(), format, useProxy, text.length()));

        long startNs = System.nanoTime();
        MiniMaxTtsResponse response =
                postForJson(restTemplate, url, bearerHeaders(apiKey), serialize(body, apiKey), apiKey, MiniMaxTtsResponse.class);
        byte[] audio = decodeAudio(response, apiKey);
        String contentType = audioMimeForFormat(format);
        log.info(forLog("narration.tts.log.synthesize.done", audio.length, contentType, elapsedMs(startNs)));
        return new NarrationAudio(audio, contentType);
    }

    /**
     * 选定情绪：先按逐句 {@code delivery} 关键词映射到 MiniMax 枚举，未命中再回退配置的 {@code emotion}（校验在枚举内），
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

    /** 逐句 delivery 关键词 → MiniMax 情绪枚举（best-effort，未命中返回 {@code null}）。 */
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
            return "fearful";
        }
        if (containsAny(d, "disgust", "repuls")) {
            return "disgusted";
        }
        if (containsAny(d, "surpris", "shock", "astonish", "amazed")) {
            return "surprised";
        }
        if (containsAny(d, "whisper", "hush")) {
            return "whisper";
        }
        if (containsAny(d, "calm", "gentle", "soft", "soothing", "serene", "tender")) {
            return "calm";
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

    /** 校验业务状态 + hex 解出音频字节；状态非 0 / 无音频 / 非法 hex → 受控异常。 */
    private byte[] decodeAudio(MiniMaxTtsResponse response, String apiKey) {
        if (response == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        if (!response.ok()) {
            String detail = redact("MiniMax status " + response.statusCode()
                    + (response.statusMsg() == null ? "" : ": " + response.statusMsg()), apiKey);
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), null);
        }
        String hex = response.audioHex();
        if (NarrationSpeechText.blankToNull(hex) == null) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        try {
            byte[] bytes = hexDecode(hex.trim());
            if (bytes.length == 0) {
                throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed", redact(safeMessage(e), apiKey)), e);
        }
    }

    /** 十六进制字符串 → 字节；长度为奇数或含非 hex 字符抛 {@link IllegalArgumentException}。 */
    static byte[] hexDecode(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex char");
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 输出格式归一为受支持的 {@code mp3} / {@code wav} / {@code pcm} / {@code flac}，未知 / 空回退 {@code mp3}。 */
    static String normalizeFormat(String format) {
        if (format == null) {
            return "mp3";
        }
        String f = format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "wav", "pcm", "flac" -> f;
            default -> "mp3";
        };
    }
}
