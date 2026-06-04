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
    /** OpenAI 兼容存活探测路径：GET {@code {base-url}/models}。 */
    private static final String MODELS_PATH = "/models";
    /** 内联 voice-design 固定 voice id。 */
    private static final String DEFAULT_VOICE = "default";
    /** 非 2xx 错误体摘要上限，避免超长正文进异常 / 日志。 */
    private static final int MAX_DETAIL_LENGTH = 500;

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");

    private final NarrationTtsConfig config;
    private final RestTemplate directRestTemplate;
    private final RestTemplate proxyRestTemplate;
    private final RestTemplate directProbeRestTemplate;
    private final RestTemplate proxyProbeRestTemplate;
    private final AppMessages messages;

    public VoxCpmNarrationEngine(NarrationTtsConfig config,
                                 @Qualifier("narrationTtsRestTemplate") RestTemplate directRestTemplate,
                                 @Qualifier("narrationTtsProxyRestTemplate") RestTemplate proxyRestTemplate,
                                 @Qualifier("narrationTtsProbeRestTemplate") RestTemplate directProbeRestTemplate,
                                 @Qualifier("narrationTtsProbeProxyRestTemplate") RestTemplate proxyProbeRestTemplate,
                                 AppMessages messages) {
        this.config = config;
        this.directRestTemplate = directRestTemplate;
        this.proxyRestTemplate = proxyRestTemplate;
        this.directProbeRestTemplate = directProbeRestTemplate;
        this.proxyProbeRestTemplate = proxyProbeRestTemplate;
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
        NarrationTtsConfig.Voxcpm vox = config.getVoxcpm();
        if (vox == null || vox.getBaseUrl() == null || vox.getBaseUrl().isBlank()) {
            return false;
        }
        String apiKey = vox.getApiKey();
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
        RestTemplate restTemplate = vox.isUseProxy() ? proxyProbeRestTemplate : directProbeRestTemplate;
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
    public NarrationAudio synthesize(NarrationVoiceRequest req) {
        NarrationTtsConfig.Voxcpm vox = config.getVoxcpm();
        if (vox == null || vox.getBaseUrl() == null || vox.getBaseUrl().isBlank()) {
            throw new NarrationVoiceException(localized("narration.tts.error.unavailable"), null);
        }
        // 可控克隆：仅当配了参考音且未全局关闭克隆时启用——括号里只放 delivery、timbre 取自参考音。
        boolean clone = req != null && req.hasReferenceVoice() && vox.isEnableClone();
        String input = buildInput(req, clone);
        if (input.isEmpty()) {
            throw new NarrationVoiceException(localized("narration.tts.error.empty-text"), null);
        }

        String format = normalizeFormat(vox.getResponseFormat());
        String apiKey = vox.getApiKey();
        boolean useProxy = vox.isUseProxy();
        VoxCpmSpeechRequest body = clone
                ? new VoxCpmSpeechRequest(vox.getModel(), input, DEFAULT_VOICE, format,
                        toDataUri(req.referenceVoice()), normalizeRefText(req.referenceVoice().text()),
                        positiveOrNull(vox.getMaxNewTokens()))
                : VoxCpmSpeechRequest.voiceDesign(vox.getModel(), input, DEFAULT_VOICE, format);
        HttpEntity<byte[]> entity = new HttpEntity<>(serialize(body, apiKey), buildHeaders(apiKey));
        RestTemplate restTemplate = useProxy ? proxyRestTemplate : directRestTemplate;
        String url = speechUrl(vox.getBaseUrl());

        log.debug(forLog("narration.tts.log.synthesize.start", vox.getModel(), format, useProxy, input.length()));
        long startNs = System.nanoTime();
        try {
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            byte[] audio = response.getBody();
            long elapsedMs = elapsedMs(startNs);
            if (audio == null || audio.length == 0) {
                log.warn(forLog("narration.tts.log.synthesize.empty-audio", elapsedMs));
                throw new NarrationVoiceException(localized("narration.tts.error.empty-audio"), null);
            }
            String contentType = resolveContentType(response, format);
            log.info(forLog("narration.tts.log.synthesize.done", audio.length, contentType, elapsedMs));
            return new NarrationAudio(audio, contentType);
        } catch (RestClientResponseException e) {
            // 已连通但返回非 2xx：附状态码与（脱敏 / 截断后的）响应正文摘要。
            long elapsedMs = elapsedMs(startNs);
            String redactedBody = redact(e.getResponseBodyAsString(StandardCharsets.UTF_8), apiKey);
            log.warn(forLog("narration.tts.log.synthesize.http-error",
                    e.getRawStatusCode(), redactedBody, elapsedMs));
            String detail = "HTTP " + e.getRawStatusCode()
                    + (redactedBody.isBlank() ? "" : ": " + redactedBody);
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(startNs);
            String detail = redact(safeMessage(e), apiKey);
            log.warn(forLog("narration.tts.log.synthesize.failed", detail, elapsedMs));
            throw new NarrationVoiceException(localized("narration.tts.error.request-failed", detail), e);
        }
    }

    /**
     * 拼接 VoxCPM 的 {@code (style)正文} 输入：
     * <ul>
     *   <li>{@code cloneMode=false}（内联 voice-design）→ 括号放 {@link NarrationVoiceRequest#controlInstruction()}
     *       （基底画像 + delivery 合并串）；</li>
     *   <li>{@code cloneMode=true}（可控克隆）→ 括号<b>只</b>放 {@link NarrationVoiceRequest#delivery()}（情绪），
     *       timbre 由参考音提供，绝不再塞基底画像以免与参考音打架。</li>
     * </ul>
     * style 为空时仅正文。
     */
    static String buildInput(NarrationVoiceRequest req, boolean cloneMode) {
        String text = req == null || req.text() == null ? "" : req.text().trim();
        if (text.isEmpty()) {
            return "";
        }
        String raw = cloneMode ? req.delivery() : req.controlInstruction();
        String style = raw == null ? "" : raw.trim();
        return style.isEmpty() ? text : "(" + style + ")" + text;
    }

    /** 参考音转 {@code data:audio/...;base64,...} URI（不依赖服务端文件开关）。 */
    private static String toDataUri(NarrationReferenceVoice ref) {
        String mime = ref.mime() == null || ref.mime().isBlank() ? "audio/wav" : ref.mime().trim();
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(ref.audio());
    }

    /** 转录文本归一：空 / 空白 → {@code null}（{@code NON_NULL} 序列化下不出现）。 */
    private static String normalizeRefText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** token 上限：{@code <=0} → {@code null}（不下发上限）。 */
    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
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
        return trimTrailingSlash(baseUrl) + SPEECH_PATH;
    }

    /** 在 base-url 后拼接 {@code /models}（存活探测），自动处理结尾斜杠。 */
    private static String modelsUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + MODELS_PATH;
    }

    private static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
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

    /** 日志专用文案：跟随 JVM 系统语言（{@link AppMessages#getForLog}），不随请求 locale 漂移。 */
    private String forLog(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
