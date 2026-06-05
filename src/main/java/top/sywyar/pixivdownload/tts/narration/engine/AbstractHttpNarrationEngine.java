package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 基于 HTTP 的 OpenAI 兼容朗读引擎<b>共享基类</b>：把所有「对接外部 OpenAI 兼容 {@code /audio/speech} 服务」都要做、
 * 但<b>与具体引擎无关</b>的 HTTP / 脱敏 / 计时样板收敛到这里，让 {@code VoxCpmNarrationEngine} 等子类只保留各自的
 * <b>线缆形态</b>（请求体 DTO、模式 → 字段路由）。新增同类引擎（MiMo / CosyVoice / Fish 等）继承本类即可复用。
 *
 * <p>提供：{@link #postForAudio} 发起合成并把字节响应 / 空音频 / 非 2xx / 连接失败统一转成脱敏的
 * {@link NarrationVoiceException}（复用 {@code narration.tts.log.synthesize.*} 文案）；{@link #redact} /
 * {@link #safeMessage} 脱敏；{@link #bearerHeaders} / {@link #trimTrailingSlash} / {@link #resolveContentType} /
 * {@link #elapsedMs} 等小工具；{@link #localized} / {@link #forLog} 文案。<b>api-key 绝不入日志 / 回显 / 异常消息。</b>
 */
public abstract class AbstractHttpNarrationEngine {

    /** 子类共用日志器，按<b>运行时具体类</b>命名（如 {@code VoxCpmNarrationEngine}），日志可定位到引擎。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 子类共用的 JSON 编解码器：序列化请求体（{@link #serialize}）、解析 JSON 响应体（{@link #postForJson}）。 */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** 非 2xx 错误体 / 失败摘要的上限，避免超长正文进异常 / 日志。 */
    protected static final int MAX_DETAIL_LENGTH = 500;

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");

    /** JSON 字段 {@code ref_audio} / {@code ref_text} 的值（用户上传的参考音 base64 与转录）：连值一并遮蔽。 */
    private static final Pattern REF_FIELD_PATTERN =
            Pattern.compile("(?i)\"(ref_audio|ref_text)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"");

    /** {@code data:audio/...;base64,...} 数据 URI（参考音字节）：兜底遮蔽，避免参考音 base64 进日志 / 错误回显。 */
    private static final Pattern AUDIO_DATA_URI_PATTERN =
            Pattern.compile("(?i)data:audio/[^;,\\s\"]*;base64,[A-Za-z0-9+/=]+");

    protected final AppMessages messages;

    protected AbstractHttpNarrationEngine(AppMessages messages) {
        this.messages = messages;
    }

    /**
     * 发起合成请求并把响应规整为 {@link NarrationAudio}：{@code exchange(byte[])} 取二进制音频，空音频 / 非 2xx /
     * 连接失败统一转脱敏的 {@link NarrationVoiceException} 并计时记日志。
     *
     * @param rt             选用的 RestTemplate（直连 / 代理由子类决定）
     * @param url            完整合成端点 URL
     * @param headers        请求头（含 Content-Type / 可选 Bearer，见 {@link #bearerHeaders}）
     * @param body           已序列化的请求体字节
     * @param fallbackFormat 响应缺 Content-Type 时按此格式推断（如 {@code wav} / {@code pcm}）
     * @param secret         本次请求使用的 api-key，仅用于对异常 / 日志文本脱敏（绝不入日志）
     */
    protected NarrationAudio postForAudio(RestTemplate rt, String url, HttpHeaders headers,
                                          byte[] body, String fallbackFormat, String secret) {
        long startNs = System.nanoTime();
        ResponseEntity<byte[]> response = exchangeForBytes(rt, url, headers, body, secret, startNs);
        byte[] audio = response.getBody();
        long elapsedMs = elapsedMs(startNs);
        if (audio == null || audio.length == 0) {
            log.warn(forLog("narration.tts.log.synthesize.empty-audio", elapsedMs));
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        String contentType = resolveContentType(response, fallbackFormat);
        log.info(forLog("narration.tts.log.synthesize.done", audio.length, contentType, elapsedMs));
        return new NarrationAudio(audio, contentType);
    }

    /**
     * 发起合成请求并把 <b>JSON 响应体</b>解析为 {@code type}：供以 chat / JSON 形态返回 base64 / hex 音频的引擎
     * （如 MiMo、MiniMax）复用。解码出音频字节后由调用方按各自字段路径取出并构造 {@link NarrationAudio}。
     * 非 2xx / 连接失败 / 空响应 / JSON 解析失败统一转脱敏的 {@link NarrationVoiceException} 并计时记日志。
     *
     * @param secret 本次请求使用的 api-key，仅用于对异常 / 日志文本脱敏（绝不入日志）
     */
    protected <T> T postForJson(RestTemplate rt, String url, HttpHeaders headers,
                                byte[] body, String secret, Class<T> type) {
        long startNs = System.nanoTime();
        ResponseEntity<byte[]> response = exchangeForBytes(rt, url, headers, body, secret, startNs);
        byte[] raw = response.getBody();
        long elapsedMs = elapsedMs(startNs);
        if (raw == null || raw.length == 0) {
            log.warn(forLog("narration.tts.log.synthesize.empty-audio", elapsedMs));
            throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
        }
        try {
            // 按 UTF-8 字节流解析（遵循「禁止 String.class、按 byte[] 取后解析」的项目约束）。
            return MAPPER.readValue(raw, type);
        } catch (IOException e) {
            String detail = redact(safeMessage(e), secret);
            log.warn(forLog("narration.tts.log.synthesize.failed", detail, elapsedMs));
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        }
    }

    /**
     * POST 并返回 2xx 响应体（{@code byte[]}），把非 2xx / 连接失败统一转成脱敏的 {@link NarrationVoiceException}
     * 并计时记日志。空体由调用方按各自语义判断（二进制音频 / JSON）。{@link #postForAudio} 与 {@link #postForJson}
     * 共用本方法，确保两类引擎的错误处理 / 脱敏 / 计时完全一致。
     */
    private ResponseEntity<byte[]> exchangeForBytes(RestTemplate rt, String url, HttpHeaders headers,
                                                    byte[] body, String secret, long startNs) {
        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        try {
            return rt.exchange(url, HttpMethod.POST, entity, byte[].class);
        } catch (RestClientResponseException e) {
            // 已连通但返回非 2xx：附状态码与（脱敏 / 截断后的）响应正文摘要。
            long elapsedMs = elapsedMs(startNs);
            String redactedBody = redact(e.getResponseBodyAsString(StandardCharsets.UTF_8), secret);
            log.warn(forLog("narration.tts.log.synthesize.http-error",
                    e.getRawStatusCode(), redactedBody, elapsedMs));
            String detail = "HTTP " + e.getRawStatusCode()
                    + (redactedBody.isBlank() ? "" : ": " + redactedBody);
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startNs);
            String detail = redact(safeMessage(e), secret);
            log.warn(forLog("narration.tts.log.synthesize.failed", detail, elapsedMs));
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        }
    }

    /** 序列化请求体 DTO 为 JSON 字节；失败转脱敏的 {@link NarrationVoiceException}（{@code secret} 仅用于脱敏）。 */
    protected byte[] serialize(Object body, String secret) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new NarrationVoiceException(
                    localized("narration.tts.error.request-failed", redact(safeMessage(e), secret)), e);
        }
    }

    /** JSON 请求头：Content-Type application/json + 可选 Bearer（api-key 空 / 空白则不带 Authorization）。 */
    protected static HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
        return headers;
    }

    /**
     * 参考音转 {@code data:audio/...;base64,...} URI（不依赖服务端文件开关，直接把字节内联进请求）。MIME 缺省按
     * {@code audio/wav}。供所有支持参考音克隆的引擎（VoxCPM / MiMo / CosyVoice 等）复用。
     */
    protected static String toDataUri(NarrationReferenceVoice ref) {
        String mime = ref.mime() == null || ref.mime().isBlank() ? "audio/wav" : ref.mime().trim();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(ref.audio());
    }

    /** 去掉 base-url 结尾的全部斜杠，便于安全拼接子路径。 */
    protected static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** contentType 取响应头；缺失时按输出格式推断（见 {@link #audioMimeForFormat}）。 */
    protected static String resolveContentType(ResponseEntity<byte[]> response, String fallbackFormat) {
        MediaType type = response.getHeaders().getContentType();
        if (type != null) {
            return type.toString();
        }
        return audioMimeForFormat(fallbackFormat);
    }

    /** 音频格式名 → MIME（响应缺 Content-Type 时 / JSON 引擎按所选格式标注 contentType 时复用）。未知 → {@code audio/wav}。 */
    protected static String audioMimeForFormat(String format) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "mp3", "mpeg" -> "audio/mpeg";
            case "pcm", "pcm16" -> "audio/pcm";
            case "opus" -> "audio/opus";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            default -> "audio/wav";
        };
    }

    protected static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    protected static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    /**
     * 对面向异常消息 / 日志的文本脱敏 + 截断：先把本次请求使用的 {@code secret}（api-key）字面量替换为 {@code ***}
     * （防止部分服务在错误体中回显），再盖掉 Bearer token 碎片，接着遮蔽<b>克隆请求可能带出的参考音 / 转录</b>
     * （{@code ref_audio} / {@code ref_text} 字段值与 {@code data:audio/...;base64,...} 数据 URI——上游回显请求体时
     * 会把用户上传的音频 base64 与转录文本带进错误正文），最后压一行并截断到 {@link #MAX_DETAIL_LENGTH}。
     */
    protected static String redact(String text, String secret) {
        if (text == null) {
            return "";
        }
        String s = text;
        String trimmedKey = secret == null ? "" : secret.trim();
        if (!trimmedKey.isEmpty()) {
            s = s.replace(secret, "***");
            if (!trimmedKey.equals(secret)) {
                s = s.replace(trimmedKey, "***");
            }
        }
        s = BEARER_PATTERN.matcher(s).replaceAll("Bearer ***");
        s = REF_FIELD_PATTERN.matcher(s).replaceAll("\"$1\":\"***\"");
        s = AUDIO_DATA_URI_PATTERN.matcher(s).replaceAll("data:audio/***");
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_DETAIL_LENGTH ? oneLine.substring(0, MAX_DETAIL_LENGTH) + "…" : oneLine;
    }

    /** 面向用户 / 异常的文案（跟随请求 locale）。 */
    protected String localized(String code, Object... args) {
        return messages.get(code, args);
    }

    /** 日志专用文案：跟随 JVM 系统语言（{@link AppMessages#getForLog}），不随请求 locale 漂移。 */
    protected String forLog(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
