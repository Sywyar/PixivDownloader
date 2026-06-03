package top.sywyar.pixivdownload.tts.narration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * VoxCPM 朗读引擎：对接 VoxCPM（{@code vllm serve openbmb/VoxCPM2 --omni}）的 OpenAI 兼容音频接口
 * {@code POST {base-url}/audio/speech}。VoxCPM 是<b>外部服务</b>（需 GPU，用户自行 {@code vllm serve}），
 * 后端只作 HTTP 客户端，<b>绝不</b>内嵌 Python / GPU 模型。
 *
 * <p><b>一致性：内联 voice-design。</b> VoxCPM 没有独立的 control-instruction 字段，音色描述用其
 * {@code (描述)正文} voice-design 语法拼进 {@code input}：
 * <pre>input = (controlInstruction 非空 ? "(" + controlInstruction + ")" : "") + text</pre>
 * {@code voice} 固定 {@code default}。参考音克隆（{@code ref_audio}）本次不实现，留作一致性升级。
 *
 * <p>是否走 HTTP 代理由 {@code narration-tts.voxcpm.use-proxy} 决定（per-config，独立于全局
 * {@code proxy.enabled}）：为 {@code true} 用经 {@link top.sywyar.pixivdownload.config.ProxyConfig} 的
 * 代理 RestTemplate，否则直连。响应是二进制音频，按 byte[] 读取；失败抛 {@link NarrationVoiceException}，
 * 消息已脱敏、绝不含 API Key。
 */
@Component
@Slf4j
public class VoxCpmNarrationEngine implements NarrationVoiceEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEECH_PATH = "/audio/speech";
    /** 内联 voice-design 固定 voice id。 */
    private static final String DEFAULT_VOICE = "default";
    /** 非 2xx 错误体摘要上限，避免超长正文进异常 / 日志。 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");

    private final NarrationTtsConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;
    private final AppMessages messages;

    public VoxCpmNarrationEngine(NarrationTtsConfig config,
                                 @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                 @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                 AppMessages messages) {
        this.config = config;
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
        this.messages = messages;
    }

    @Override
    public String id() {
        return NarrationTtsConfig.ENGINE_VOXCPM;
    }

    @Override
    public boolean isAvailable() {
        NarrationTtsConfig.Voxcpm vox = config.getVoxcpm();
        return vox != null && vox.getBaseUrl() != null && !vox.getBaseUrl().isBlank();
    }

    @Override
    public NarrationAudio synthesize(NarrationVoiceRequest req) {
        NarrationTtsConfig.Voxcpm vox = config.getVoxcpm();
        if (vox == null || vox.getBaseUrl() == null || vox.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        String input = buildInput(req);
        if (input.isEmpty()) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String format = normalizeFormat(vox.getResponseFormat());
        String apiKey = vox.getApiKey();
        VoxCpmSpeechRequest body = new VoxCpmSpeechRequest(vox.getModel(), input, DEFAULT_VOICE, format);
        HttpEntity<byte[]> entity = new HttpEntity<>(serialize(body, apiKey), buildHeaders(apiKey));
        RestTemplate restTemplate = vox.isUseProxy() ? proxyRestTemplate : directRestTemplate;
        String url = speechUrl(vox.getBaseUrl());

        try {
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            byte[] audio = response.getBody();
            if (audio == null || audio.length == 0) {
                throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
            }
            return new NarrationAudio(audio, resolveContentType(response, format));
        } catch (RestClientResponseException e) {
            // 已连通但返回非 2xx：附状态码与（脱敏 / 截断后的）响应正文摘要。
            String respBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String detail = "HTTP " + e.getRawStatusCode()
                    + (respBody == null || respBody.isBlank() ? "" : ": " + redact(respBody, apiKey));
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        } catch (RestClientException e) {
            String detail = redact(safeMessage(e), apiKey);
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        }
    }

    /** 内联 voice-design：{@code (controlInstruction)text}；instruction 为空时仅正文。 */
    static String buildInput(NarrationVoiceRequest req) {
        String text = req == null || req.text() == null ? "" : req.text().trim();
        if (text.isEmpty()) {
            return "";
        }
        String ci = req.controlInstruction() == null ? "" : req.controlInstruction().trim();
        return ci.isEmpty() ? text : "(" + ci + ")" + text;
    }

    /** 输出格式归一为受支持的 {@code wav} / {@code pcm}，未知 / 空回退 {@code wav}。 */
    static String normalizeFormat(String responseFormat) {
        if (responseFormat == null) {
            return "wav";
        }
        String f = responseFormat.trim().toLowerCase(java.util.Locale.ROOT);
        return "pcm".equals(f) ? "pcm" : "wav";
    }

    private byte[] serialize(VoxCpmSpeechRequest body, String apiKey) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed",
                    redact(safeMessage(e), apiKey)), e);
        }
    }

    private static HttpHeaders buildHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
        return headers;
    }

    /** 在 base-url 后拼接 {@code /audio/speech}，自动处理结尾斜杠。 */
    private static String speechUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + SPEECH_PATH;
    }

    /** contentType 取响应头；缺失时按输出格式推断。 */
    private static String resolveContentType(ResponseEntity<byte[]> response, String format) {
        MediaType type = response.getHeaders().getContentType();
        if (type != null) {
            return type.toString();
        }
        return "pcm".equals(format) ? "audio/pcm" : "audio/wav";
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    /**
     * 对面向异常消息 / 日志的文本脱敏 + 截断：先把本次请求使用的 {@code apiKey} 字面量替换为 {@code ***}
     * （防止部分服务在错误体中回显），再盖掉 Bearer token 碎片，最后压一行并截断。
     */
    private static String redact(String text, String apiKey) {
        if (text == null) {
            return "";
        }
        String s = text;
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        if (!trimmedKey.isEmpty()) {
            s = s.replace(apiKey, "***");
            if (!trimmedKey.equals(apiKey)) {
                s = s.replace(trimmedKey, "***");
            }
        }
        s = BEARER_PATTERN.matcher(s).replaceAll("Bearer ***");
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_DETAIL_LENGTH ? oneLine.substring(0, MAX_DETAIL_LENGTH) + "…" : oneLine;
    }

    private String localized(String code, Object... args) {
        return messages.get(code, args);
    }
}
