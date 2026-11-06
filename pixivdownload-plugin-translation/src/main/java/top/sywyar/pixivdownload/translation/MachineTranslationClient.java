package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import top.sywyar.pixivdownload.translation.machine.TranslationBatch;
import top.sywyar.pixivdownload.translation.machine.TranslationRequestGate;
import lombok.extern.slf4j.Slf4j;

/** 普通翻译 API；不解析或执行服务端返回的标记、地址与错误详情。 */
@Slf4j
public class MachineTranslationClient implements AutoCloseable {
    static final int RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_LANGUAGES = 512;
    private final TranslationConfig config;
    private final Map<String, RestTemplate> clients;
    private final Map<String, TranslationRequestGate> gates;
    private final ObjectMapper mapper;

    // 有道文本翻译 API 的公开语种表；接口文档未列出语种目录查询端点。
    // https://ai.youdao.com/DOCSIRMA/html/trans/api/wbfy/index.html
    private static final List<String> YOUDAO_LANGUAGES = Arrays.asList((
            "ar de en es fr hi id it ja ko nl pt ru th vi zh-CHS zh-CHT af am az be bg bn bs ca ceb co cs cy "
            + "da el eo et eu fa fi fj fy ga gd gl gu ha haw he hr ht hu hy ig is jw ka kk km kn ku ky la lb lo "
            + "lt lv mg mi mk ml mn mr ms mt mww my ne no ny otq pa pl ps ro sd si sk sl sm sn so sq sr-Cyrl "
            + "sr-Latn st su sv sw ta te tg tl tlh to tr ty uk ur uz xh yi yo yua yue zu").split(" "));

    public record Language(String code, String name) {}

    /** 仅携带受控诊断，不保留可能包含凭据或原文的响应及异常链。 */
    static final class Failure extends IllegalStateException {
        final String reason;
        final int httpStatus;
        final String providerCode;
        long retryAfterMs;
        Failure(String key, String reason, int httpStatus, String providerCode) {
            super(key);
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.providerCode = providerCode != null && providerCode.matches("[0-9]{1,6}") ? providerCode : "none";
        }
    }

    public List<Language> languages(String engine) {
        if (engine.equals("ai")) return List.of();
        if (engine.equals("youdao")) return YOUDAO_LANGUAGES.stream().map(code -> {
            String tag = language(code.equals("jw") ? "jv" : code);
            return new Language(tag, Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH));
        }).toList();
        if (!engine.equals("libretranslate")) throw new IllegalArgumentException("translation.unknown-engine");
        if (!available(engine)) throw new IllegalStateException("translation.unavailable");
        JsonNode json = exchange("libretranslate", endpoint(config.getLibretranslateUrl()).resolve("languages"), HttpMethod.GET, null, null, RESPONSE_BYTES);
        if (json == null || !json.isArray()) throw new Failure("translation.invalid-response", "invalid_languages", 200, null);
        Map<String, Language> found = new LinkedHashMap<>();
        // targets 是实际可翻译的方向，不能把仅能作为源语言的模型列作目标。
        for (JsonNode entry : json) {
            for (JsonNode target : entry.path("targets")) {
                if (!target.isTextual() || target.asText().length() > 100) continue;
                String code = language(target.asText());
                if (!code.isBlank()) found.putIfAbsent(code, new Language(code, Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)));
                if (found.size() > MAX_LANGUAGES) throw new Failure("translation.response-too-large", "too_many_languages", 200, null);
            }
        }
        return List.copyOf(found.values());
    }

    public MachineTranslationClient(TranslationConfig config, RestTemplate youdao, RestTemplate libretranslate, ObjectMapper mapper) {
        config.validate();
        this.config = config;
        this.clients = Map.of("youdao", youdao, "libretranslate", libretranslate);
        this.mapper = mapper;
        this.gates = Map.of("youdao", gate(config.getYoudao()), "libretranslate", gate(config.getLibretranslate()));
    }

    private static TranslationRequestGate gate(TranslationConfig.MachineSettings settings) {
        return new TranslationRequestGate(settings.getRequestConcurrency(), settings.getRequestIntervalMs());
    }

    @Override public void close() { gates.values().forEach(TranslationRequestGate::close); }

    public boolean available(String engine) {
        return switch (engine) {
            case "youdao" -> !config.getYoudaoAppKey().isBlank() && !config.getYoudaoAppSecret().isBlank();
            case "libretranslate" -> !config.getLibretranslateUrl().isBlank();
            default -> false;
        };
    }

    public String translate(String engine, String source, String language, int segmentSize) {
        return translate(engine, source, language, segmentSize, UUID.randomUUID().toString(), "text");
    }

    public String translate(String engine, String source, String language, int segmentSize, String run, String field) {
        return translate(engine, source, language, segmentSize, run, field, null);
    }
    String translate(String engine, String source, String language, int segmentSize, String run, String field,
                     TranslationCheckpoint checkpoint) {
        if (source == null || source.isBlank()) return source;
        if (!available(engine)) throw new IllegalStateException("translation.unavailable");
        if (engine.equals("youdao") && !YOUDAO_LANGUAGES.contains(providerLanguage(engine, language)))
            throw new IllegalArgumentException("translation.language-unsupported");
        var settings = config.machine(engine);
        int size = segmentSize <= 0 ? settings.getRequestChars() : Math.min(settings.getRequestChars(), segmentSize);
        var stats = new RequestStats(run, field);
        boolean success = false;
        try {
            int[] index = {0};
            String result = TranslationBatch.translate(source, size, settings.getRequestChars(), settings.getBatchSize(),
                    texts -> checkpoint == null ? requestWithRetry(engine, texts, language, stats)
                            : checkpoint.batch(field, index[0]++, texts, () -> requestWithRetry(engine, texts, language, stats)));
            success = true;
            return result;
        } finally {
            log.info("Translation requests finished: runId={}, stage={}, engine={}, outcome={}, requests={}, batches={}, texts={}, retries={}, translatedChars={}",
                    run, field, engine, success ? "OK" : "FAILED", stats.requests, stats.batches, stats.texts, stats.retries, stats.chars);
        }
    }

    private List<String> requestWithRetry(String engine, List<String> texts, String language, RequestStats stats) {
        var settings = config.machine(engine);
        var gate = gates.get(engine);
        for (int attempt = 0; ; attempt++) {
            try {
                // LibreTranslate 的批量请求按文本条数消耗请求额度。
                if (!gate.acquire(engine.equals("libretranslate") ? texts.size() : 1))
                    throw new Failure("translation.rate-limited", "cooldown", 429, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Failure("translation.cancelled", "cancelled", 0, null);
            }
            try {
                stats.requests++;
                List<String> result = request(engine, texts, language);
                stats.batches++;
                stats.texts += texts.size();
                stats.chars += texts.stream().mapToLong(t -> t.codePointCount(0, t.length())).sum();
                if (System.nanoTime() - stats.lastProgress >= TimeUnit.SECONDS.toNanos(10)) {
                    log.info("Translation progress: runId={}, stage={}, engine={}, requests={}, batches={}, texts={}, retries={}, translatedChars={}",
                            stats.run, stats.field, engine, stats.requests, stats.batches, stats.texts, stats.retries, stats.chars);
                    stats.lastProgress = System.nanoTime();
                }
                return result;
            } catch (Failure failure) {
                boolean limited = failure.httpStatus == 429 || "411".equals(failure.providerCode) || "412".equals(failure.providerCode);
                if (!limited) throw failure;
                long delay = Math.max(failure.retryAfterMs,
                        Math.min(60_000L, Math.max(1000L, settings.getRequestIntervalMs()) * (1L << attempt)));
                gate.coolDown(delay);
                if (attempt >= settings.getMaxRetries() || delay > 60_000) throw failure;
                stats.retries++;
                if (stats.retries <= 5) log.warn("Translation retry scheduled: runId={}, stage={}, engine={}, attempt={}, delayMs={}, httpStatus={}, providerCode={}",
                        stats.run, stats.field, engine, attempt + 1, delay, failure.httpStatus, failure.providerCode);
            } finally {
                gate.release();
            }
        }
    }

    private List<String> request(String engine, List<String> texts, String language) {
        URI uri;
        byte[] body;
        MediaType contentType;
        boolean batch = texts.size() > 1;
        if (engine.equals("youdao")) {
            uri = URI.create(batch ? "https://openapi.youdao.com/v2/api" : "https://openapi.youdao.com/api");
            Map<String, String> form = new LinkedHashMap<>();
            String salt = UUID.randomUUID().toString(), time = Long.toString(Instant.now().getEpochSecond());
            form.put("from", "auto"); form.put("to", providerLanguage(engine, language));
            form.put("appKey", config.getYoudaoAppKey()); form.put("salt", salt); form.put("curtime", time);
            form.put("signType", "v3");
            if (!batch) form.put("strict", "true");
            else form.put("detectFilter", "false");
            form.put("sign", sign(config.getYoudaoAppKey(), String.join("", texts), salt, time, config.getYoudaoAppSecret()));
            String query = texts.stream().map(t -> "q=" + encode(t)).collect(java.util.stream.Collectors.joining("&"));
            body = (query + "&" + form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"))).getBytes(StandardCharsets.UTF_8);
            contentType = new MediaType("application", "x-www-form-urlencoded", StandardCharsets.UTF_8);
        } else if (engine.equals("libretranslate")) {
            uri = endpoint(config.getLibretranslateUrl());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("q", batch ? texts : texts.get(0));
            payload.put("source", "auto"); payload.put("target", providerLanguage(engine, language));
            payload.put("format", "text");
            if (!config.getLibretranslateApiKey().isBlank()) payload.put("api_key", config.getLibretranslateApiKey());
            try { body = mapper.writeValueAsBytes(payload); }
            catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException("translation.invalid-request"); }
            contentType = MediaType.APPLICATION_JSON;
        } else throw new IllegalArgumentException("translation.unknown-engine");
        // 按实际输入预留 UTF-8 / JSON 与译文膨胀空间；不按整篇小说分配响应预算。
        int responseLimit = Math.max(RESPONSE_BYTES, texts.stream().mapToInt(t -> t.codePointCount(0, t.length())).sum() * 16);
        JsonNode json = exchange(engine, uri, HttpMethod.POST, body, contentType, responseLimit);
        JsonNode values;
        if (engine.equals("youdao")) {
            String code = json == null ? "" : json.path("errorCode").asText();
            if (!"0".equals(code)) throw new Failure(youdaoErrorKey(code), "provider_rejected", 200, code);
            if (json.path("errorIndex").size() != 0) throw new Failure("translation.invalid-response", "partial_translation", 200, null);
            values = batch ? json.path("translateResults") : mapper.createArrayNode().add(json.path("translation").path(0));
        } else values = json == null ? null : json.get("translatedText");
        if (values == null) throw new Failure("translation.invalid-response", "invalid_translation", 200, null);
        if (!batch && values.isTextual()) values = mapper.createArrayNode().add(values);
        if (!values.isArray() || values.size() != texts.size())
            throw new Failure("translation.invalid-response", "invalid_translation_count", 200, null);
        List<String> result = new ArrayList<>(texts.size());
        for (JsonNode value : values) {
            JsonNode translated = engine.equals("youdao") && batch ? value.path("translation") : value;
            if (!translated.isTextual() || translated.asText().isBlank())
                throw new Failure("translation.invalid-response", "invalid_translation", 200, null);
            result.add(translated.asText());
        }
        return result;
    }

    private JsonNode exchange(String engine, URI uri, HttpMethod method, byte[] body, MediaType contentType, int responseLimit) {
        RestTemplate client = clients.get(engine);
        try {
            var request = client.getRequestFactory().createRequest(uri, method);
            request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
            if (body != null) {
                request.getHeaders().setContentType(contentType);
                request.getBody().write(body);
            }
            try (var response = request.execute()) {
                int status = response.getStatusCode().value();
                if (!response.getStatusCode().is2xxSuccessful()) {
                    var failure = new Failure(status == 429 ? "translation.rate-limited" : status == 401 || status == 403
                            ? "translation.credentials-error" : "translation.remote-error", "http_error", status, null);
                    failure.retryAfterMs = retryAfter(response.getHeaders().getFirst("Retry-After"));
                    throw failure;
                }
                if (response.getHeaders().getContentLength() > responseLimit) throw new Failure("translation.response-too-large", "response_too_large", status, null);
                byte[] bytes = response.getBody().readNBytes(responseLimit + 1);
                if (bytes.length > responseLimit) throw new Failure("translation.response-too-large", "response_too_large", status, null);
                return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new Failure("translation.invalid-response", "invalid_json", 200, null);
        } catch (java.io.IOException | org.springframework.web.client.RestClientException failure) {
            throw new Failure("translation.remote-error", failure instanceof java.net.SocketTimeoutException
                    ? "timeout" : "transport_error", 0, null);
        }
    }

    static long retryAfter(String value) {
        if (value == null || value.length() > 100) return 0;
        if (value.matches("[0-9]+")) {
            try { return TimeUnit.SECONDS.toMillis(Long.parseLong(value)); }
            catch (NumberFormatException overflow) { return Long.MAX_VALUE; }
        }
        try {
            return Math.max(0, java.time.Duration.between(Instant.now(),
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
        } catch (RuntimeException invalid) { return 0; }
    }

    private static final class RequestStats {
        final String run;
        final String field;
        long requests, batches, texts, retries, chars;
        long lastProgress = System.nanoTime();
        RequestStats(String run, String field) { this.run = run; this.field = field; }
    }
    private static String youdaoErrorKey(String code) {
        return switch (code) {
            case "102" -> "translation.language-unsupported";
            case "108", "111", "202", "203" -> "translation.credentials-error";
            case "110", "112", "205" -> "translation.service-not-enabled";
            case "206" -> "translation.clock-error";
            case "401" -> "translation.balance-error";
            case "411", "412" -> "translation.rate-limited";
            default -> "translation.remote-error";
        };
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static URI endpoint(String base) {
        try {
            URI uri = URI.create(base.trim().replaceAll("/+$", "") + "/translate");
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("translation.invalid-url"); }
    }
    static String sign(String key, String text, String salt, String time, String secret) {
        int length = text.codePointCount(0, text.length());
        String input = length <= 20 ? text : text.substring(0, text.offsetByCodePoints(0, 10)) + length
                + text.substring(text.offsetByCodePoints(0, length - 10));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((key + input + salt + time + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String language(String target) {
        if (target == null) return "";
        if (target.length() > 100) return "";
        String value = target.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        value = switch (value) {
            case "中文", "简体中文", "简体", "chinese", "zh", "zh-hans", "zh-chs" -> Locale.SIMPLIFIED_CHINESE.toLanguageTag();
            case "繁體中文", "繁体中文", "繁體", "zh-hant", "zh-cht", "zt" -> Locale.TRADITIONAL_CHINESE.toLanguageTag();
            case "english", "英语", "en" -> Locale.US.toLanguageTag();
            case "日本語", "日语", "japanese", "ja" -> Locale.JAPAN.toLanguageTag();
            case "한국어", "韩语", "korean", "ko" -> Locale.KOREA.toLanguageTag();
            default -> value;
        };
        if (!value.matches("(?i)[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) return "";
        return Locale.forLanguageTag(value).toLanguageTag();
    }
    static String providerLanguage(String engine, String language) {
        Locale target = Locale.forLanguageTag(language(language));
        if (Locale.CHINESE.getLanguage().equals(target.getLanguage())) {
            boolean traditional = "Hant".equals(target.getScript())
                    || java.util.List.of("TW", "HK", "MO").contains(target.getCountry());
            return engine.equals("youdao") ? (traditional ? "zh-CHT" : "zh-CHS") : (traditional ? "zt" : "zh");
        }
        if (engine.equals("youdao")) {
            if (target.getLanguage().equals("jv") || target.getLanguage().equals("jw")) return "jw";
            if (target.getLanguage().equals("sr")) return "Latn".equals(target.getScript()) ? "sr-Latn" : "sr-Cyrl";
            return target.getLanguage();
        }
        // 默认语言的历史存储身份带地区；其它服务返回的地区和文字体系须原样保留。
        if (List.of(Locale.US, Locale.JAPAN, Locale.KOREA).contains(target)) return target.getLanguage();
        return target.toLanguageTag();
    }
}
