package top.sywyar.pixivdownload.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 大语言模型（LLM）调用服务。统一走 <b>OpenAI Chat Completions 兼容协议</b>：
 * {@code POST {base-url}/chat/completions}，鉴权头 {@code Authorization: Bearer <api-key>}。
 * <p>
 * 两个入口：
 * <ul>
 *   <li>{@link #chat} —— 业务路径，使用当前 {@link AiConfig}；总开关关闭 / 缺关键配置 / 请求失败时抛
 *       {@link AiClientException}（调用方决定如何处理）</li>
 *   <li>{@link #chatTest} —— GUI 连通性测试路径，使用调用方传入的临时 {@link AiClientSettings}（不读取
 *       {@link AiConfig}，也不检查总开关），失败抛 {@link AiClientException}</li>
 * </ul>
 * 是否走 HTTP 代理由 {@link AiClientSettings#useProxy()} 决定：为 {@code true} 时使用注入的
 * {@code aiProxyRestTemplate}（路由经 {@link top.sywyar.pixivdownload.config.ProxyConfig} 的 host:port），
 * 否则直连。全程 UTF-8；API Key 绝不写入日志 / 失败摘要 / 响应。
 */
@Service
@Slf4j
public class OpenAiCompatibleAiClient implements AiChatClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    // 兜底脱敏正则：拦截响应正文 / 异常消息中可能回显的密钥碎片。即使来自部分 OpenAI 兼容服务在
    // "invalid API key" 错误体中把请求里的 key 原样回显，这里也能保证不写进日志 / AiException / GUI 文案。
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern KEY_FIELD_PATTERN = Pattern.compile(
            "(?i)(\"?(?:api[_-]?key|access[_-]?token|authorization|secret)\"?\\s*[:=]\\s*)\"[^\"]+\"");
    private static final Pattern SK_TOKEN_PATTERN = Pattern.compile("(?i)sk-[A-Za-z0-9_\\-]{10,}");

    private final AiConfig aiConfig;
    private final MessageResolver messages;
    private final RestTemplate aiRestTemplate;
    private final RestTemplate aiProxyRestTemplate;

    public OpenAiCompatibleAiClient(AiConfig aiConfig,
                                    MessageResolver messages,
                                    @Qualifier("aiRestTemplate") RestTemplate aiRestTemplate,
                                    @Qualifier("aiProxyRestTemplate") RestTemplate aiProxyRestTemplate) {
        this.aiConfig = aiConfig;
        this.messages = messages;
        this.aiRestTemplate = aiRestTemplate;
        this.aiProxyRestTemplate = aiProxyRestTemplate;
    }

    /**
     * 业务路径对话：使用当前 {@link AiConfig}。总开关关闭、未配置 base-url / model 或请求失败时抛
     * {@link AiClientException}。
     *
     * @param callType 调用类型标签（如 {@code translation} / {@code translation.lang-probe}），仅用于日志标识
     * @param messages 对话消息序列（至少一条）
     * @param options  可选调参；{@code null} 等价于 {@link AiChatOptions#defaults()}
     */
    public AiChatResult chat(String callType, List<AiChatMessage> messages,
                             AiChatOptions options) throws AiClientException {
        if (!aiConfig.isEnabled()) {
            throw new AiClientException(localized("ai.error.disabled"));
        }
        return deliver(callType, aiConfig.toClientSettings(), messages, options);
    }

    /**
     * GUI 连通性测试：使用调用方传入的临时设置（不读取 {@link AiConfig}、不检查总开关），失败抛
     * {@link AiClientException} 让 GUI 显示失败原因。失败摘要绝不含 API Key。
     *
     * @param callType 调用类型标签（如 {@code probe.connectivity}），仅用于日志标识
     */
    public AiChatResult chatTest(String callType, AiClientSettings settings,
                                 List<AiChatMessage> messages, AiChatOptions options) throws AiClientException {
        if (settings == null) {
            throw new AiClientException(localized("ai.error.settings-missing"));
        }
        return deliver(callType, settings, messages, options);
    }

    /**
     * 文本模型（LLM）是否已配置就绪：总开关开启且 base-url / model 均已填写。<b>纯配置检查、不触网、不读取密钥</b>，
     * 与 {@link #chat} 的前置校验（启用 + base-url + model）一致。供前端按可用性显隐依赖 LLM 的入口
     * （如「AI 翻译」按钮、「富感情朗读」选项），避免后端未配置时仍展示无法工作的入口。
     */
    public boolean isConfigured() {
        return aiConfig.isEnabled()
                && aiConfig.getBaseUrl() != null && !aiConfig.getBaseUrl().isBlank()
                && aiConfig.getModel() != null && !aiConfig.getModel().isBlank();
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private AiChatResult deliver(String callType, AiClientSettings settings,
                                 List<AiChatMessage> chatMessages, AiChatOptions options) throws AiClientException {
        if (settings.baseUrl() == null || settings.baseUrl().isBlank()) {
            throw new AiClientException(localized("ai.error.base-url-missing"));
        }
        if (settings.model() == null || settings.model().isBlank()) {
            throw new AiClientException(localized("ai.error.model-missing"));
        }
        if (chatMessages == null || chatMessages.isEmpty()) {
            throw new AiClientException(localized("ai.error.messages-missing"));
        }
        AiChatOptions opts = options == null ? AiChatOptions.defaults() : options;

        String url = chatCompletionsUrl(settings.baseUrl());
        byte[] requestBody = serialize(buildRequest(settings.model(), chatMessages, opts));
        HttpEntity<byte[]> entity = new HttpEntity<>(requestBody, buildHeaders(settings.apiKey()));
        RestTemplate restTemplate = settings.useProxy() ? aiProxyRestTemplate : aiRestTemplate;

        String type = callType == null || callType.isBlank() ? "unknown" : callType;
        String model = settings.model();
        String apiKey = settings.apiKey();
        long startedAtNs = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            AiChatResult result = parseResult(response.getBody());
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000;
            log.info(logMessage("ai.log.chat.success", type, model, elapsedMs,
                    tokenStr(result.promptTokens()), tokenStr(result.completionTokens())));
            return result;
        } catch (RestClientResponseException e) {
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000;
            // 已连通但返回非 2xx：附带状态码与（脱敏 / 截断后的）响应正文摘要。
            // 部分 OpenAI 兼容服务会把请求里的 API Key 原样回显在 "invalid api key" 类错误体中，
            // 这里必须先脱敏再写日志 / 抛 AiException / 回 GUI。
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String msg = "HTTP " + e.getRawStatusCode()
                    + (body == null || body.isBlank() ? "" : ": " + redact(body, apiKey));
            log.warn(logMessage("ai.log.chat.failed", type, model, elapsedMs, msg));
            throw new AiClientException(msg, e);
        } catch (RestClientException e) {
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000;
            String msg = safeMessage(e, apiKey);
            log.warn(logMessage("ai.log.chat.failed", type, model, elapsedMs, msg));
            throw new AiClientException(msg, e);
        }
    }

    /** token 计数可能为 null（部分模型不回报），统一成日志字符串。 */
    private static String tokenStr(Integer count) {
        return count == null ? "?" : String.valueOf(count);
    }

    private ChatRequest buildRequest(String model, List<AiChatMessage> chatMessages, AiChatOptions opts) {
        ResponseFormat responseFormat = opts.jsonObject() ? new ResponseFormat("json_object") : null;
        return new ChatRequest(model, chatMessages, opts.temperature(), opts.maxTokens(), responseFormat, false);
    }

    private static HttpHeaders buildHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }
        return headers;
    }

    private AiChatResult parseResult(byte[] body) throws AiClientException {
        if (body == null || body.length == 0) {
            throw new AiClientException(localized("ai.error.empty-response"));
        }
        ChatResponse response;
        try {
            response = MAPPER.readValue(body, ChatResponse.class);
        } catch (Exception e) {
            throw new AiClientException(localized("ai.error.empty-response"), e);
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiClientException(localized("ai.error.empty-response"));
        }
        ChatResponse.Choice choice = response.choices().get(0);
        String content = choice.message() == null ? null : choice.message().content();
        if (content == null) {
            throw new AiClientException(localized("ai.error.empty-response"));
        }
        ChatResponse.Usage usage = response.usage();
        return new AiChatResult(
                content,
                choice.finishReason(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens());
    }

    /** 在 base-url 后拼接 {@code /chat/completions}，自动处理结尾斜杠。 */
    private static String chatCompletionsUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + CHAT_COMPLETIONS_PATH;
    }

    private byte[] serialize(ChatRequest request) throws AiClientException {
        try {
            return MAPPER.writeValueAsBytes(request);
        } catch (Exception e) {
            throw new AiClientException(safeMessage(e, null), e);
        }
    }

    /** 截取异常的可读摘要供日志 / GUI 显示，并按需脱敏 {@code apiKey}。 */
    private static String safeMessage(Throwable t, String apiKey) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg == null) {
            msg = t.getClass().getSimpleName();
        }
        return redact(msg, apiKey);
    }

    /**
     * 对面向日志 / GUI / AiException 的文本做脱敏 + 截断：
     * 先把当前请求使用的 {@code apiKey} 字面量替换为 {@code ***}（防止部分服务在错误体中回显），
     * 再用兜底正则盖掉 Bearer token / JSON 字段 {@code "api_key": "..."} / {@code sk-...} 类碎片，
     * 最后压一行并截到 500 字符上限。
     */
    private static String redact(String msg, String apiKey) {
        if (msg == null) {
            return "";
        }
        String s = msg;
        if (apiKey != null && apiKey.length() >= 4) {
            s = s.replace(apiKey, "***");
            String trimmed = apiKey.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(apiKey)) {
                s = s.replace(trimmed, "***");
            }
        }
        s = BEARER_PATTERN.matcher(s).replaceAll("Bearer ***");
        s = KEY_FIELD_PATTERN.matcher(s).replaceAll("$1\"***\"");
        s = SK_TOKEN_PATTERN.matcher(s).replaceAll("***");
        return truncate(s);
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return "";
        }
        String oneLine = msg.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String localized(String code, Object... args) {
        return messages.get(code, args);
    }

    // ── OpenAI 兼容协议的线缆 DTO（仅本服务内部使用）────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(
            String model,
            List<AiChatMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            boolean stream
    ) {
    }

    private record ResponseFormat(String type) {
    }

    private record ChatResponse(List<Choice> choices, Usage usage) {
        private record Choice(AiChatMessage message, @JsonProperty("finish_reason") String finishReason) {
        }

        private record Usage(
                @JsonProperty("prompt_tokens") Integer promptTokens,
                @JsonProperty("completion_tokens") Integer completionTokens,
                @JsonProperty("total_tokens") Integer totalTokens
        ) {
        }
    }

}
