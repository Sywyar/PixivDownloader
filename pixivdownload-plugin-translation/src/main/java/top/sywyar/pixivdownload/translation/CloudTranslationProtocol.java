package top.sywyar.pixivdownload.translation;

import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class CloudTranslationProtocol {
    private CloudTranslationProtocol() {}

    record Request(URI uri, MediaType type, byte[] body, Map<String, String> headers) {
        @Override public String toString() { return "Translation request"; }
    }

    static Map<String, String> deepLAuth(TranslationConfig.CloudSettings s) {
        return Map.of("Authorization", "DeepL-Auth-Key " + s.getApiKey());
    }

    static Request request(CloudProvider provider, TranslationConfig.CloudSettings s, ObjectMapper mapper,
                           List<String> texts, String language) {
        URI uri = provider.endpoint(s);
        String target = provider.target(language);
        var headers = new HashMap<String, String>();
        MediaType type = MediaType.APPLICATION_JSON;
        byte[] body;
        switch (provider) {
            case DEEPL -> {
                headers.putAll(deepLAuth(s));
                var payload = new HashMap<String, Object>();
                payload.put("text", texts);
                payload.put("target_lang", target);
                if (!s.getGlossaryId().isBlank()) payload.put("glossary_id", s.getGlossaryId());
                if (!s.getSourceLanguage().isBlank()) payload.put("source_lang", s.getSourceLanguage().toUpperCase(java.util.Locale.ROOT));
                if (!s.getFormality().equals("default")) payload.put("formality", s.getFormality());
                body = mapper.writeValueAsBytes(payload);
            }
            case GOOGLE -> {
                headers.put("X-Goog-Api-Key", s.getApiKey());
                body = mapper.writeValueAsBytes(Map.of("q", texts, "target", target, "format", "text"));
            }
            case AZURE -> {
                uri = URI.create(uri + "?api-version=3.0&textType=plain&to=" + target);
                headers.put("Ocp-Apim-Subscription-Key", s.getApiKey());
                if (!s.getRegion().isBlank()) headers.put("Ocp-Apim-Subscription-Region", s.getRegion());
                body = mapper.writeValueAsBytes(texts.stream().map(text -> Map.of("Text", text)).toList());
            }
            case BAIDU -> {
                String text = texts.getFirst();
                String salt = UUID.randomUUID().toString();
                String sign = TranslationSignatures.digest("MD5", (s.getKeyId() + text + salt + s.getSecret())
                        .getBytes(StandardCharsets.UTF_8));
                type = MediaType.APPLICATION_FORM_URLENCODED;
                body = form(Map.of("q", text, "from", "auto", "to", target, "appid", s.getKeyId(), "salt", salt, "sign", sign));
            }
            case TENCENT -> body = mapper.writeValueAsBytes(Map.of("SourceText", texts.getFirst(), "Source", "auto",
                    "Target", target, "ProjectId", 0));
            case VOLCENGINE -> body = mapper.writeValueAsBytes(Map.of("TextList", texts, "TargetLanguage", target));
            case CAIYUN -> {
                headers.put("X-Authorization", "token " + s.getApiKey());
                body = mapper.writeValueAsBytes(Map.of("source", texts, "trans_type", "auto2" + target, "detect", true));
            }
            case NIUTRANS -> {
                type = MediaType.APPLICATION_FORM_URLENCODED;
                body = form(Map.of("src_text", texts.getFirst(), "from", "auto", "to", target, "apikey", s.getApiKey()));
            }
            case ALIYUN -> {
                type = MediaType.APPLICATION_FORM_URLENCODED;
                body = form(Map.of("SourceText", texts.getFirst(), "SourceLanguage", "auto", "TargetLanguage", target,
                        "FormatType", "text", "Scene", "general"));
            }
            default -> throw new IllegalArgumentException("translation.unknown-engine");
        }
        if (provider == CloudProvider.TENCENT || provider == CloudProvider.VOLCENGINE || provider == CloudProvider.ALIYUN) {
            headers.putAll(TranslationSignatures.sign(provider, s, uri, type.toString(), body, Instant.now(), UUID.randomUUID().toString()));
        }
        return new Request(uri, type, body, headers);
    }

    private static byte[] form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")).getBytes(StandardCharsets.UTF_8);
    }

    static List<String> translations(CloudProvider provider, JsonNode json, int expected) {
        if (json == null) throw invalid();
        String code = switch (provider) {
            case BAIDU, NIUTRANS -> json.path("error_code").asString("");
            case TENCENT -> json.at("/Response/Error/Code").asString("");
            case VOLCENGINE -> json.at("/ResponseMetadata/Error/Code").asString("");
            case ALIYUN -> json.path("Code").asString("");
            case CAIYUN -> json.path("rc").asString("");
            default -> "";
        };
        if (!code.isEmpty() && !(provider == CloudProvider.ALIYUN && code.equals("200"))
                && !(provider == CloudProvider.CAIYUN && code.equals("0"))) {
            throw rejection(provider, code);
        }
        return switch (provider) {
            case DEEPL -> rows(json.path("translations"), "/text", expected);
            case GOOGLE -> rows(json.at("/data/translations"), "/translatedText", expected);
            case AZURE -> rows(json, "/translations/0/text", expected);
            case BAIDU -> rows(json.path("trans_result"), "/dst", expected);
            case TENCENT -> List.of(text(json.at("/Response/TargetText")));
            case VOLCENGINE -> rows(json.path("TranslationList"), "/Translation", expected);
            case CAIYUN -> rows(json.path("target"), "", expected);
            case NIUTRANS -> List.of(text(json.path("tgt_text")));
            case ALIYUN -> List.of(text(json.at("/Data/Translated")));
        };
    }

    private static MachineTranslationClient.Failure rejection(CloudProvider provider, String code) {
        String key = switch (provider) {
            case BAIDU -> switch (code) {
                case "52003", "54001", "58000", "90107" -> "credentials-error";
                case "54003" -> "rate-limited";
                case "54004" -> "balance-error";
                case "58001" -> "language-unsupported";
                default -> "remote-error";
            };
            case TENCENT -> code.startsWith("AuthFailure") ? "credentials-error"
                    : code.startsWith("RequestLimitExceeded") || code.equals("LimitExceeded.LimitedAccessFrequency") ? "rate-limited"
                    : code.equals("FailedOperation.UnSupportedTranslation") ? "language-unsupported" : "remote-error";
            case NIUTRANS -> switch (code) {
                case "10001" -> "rate-limited";
                case "13001" -> "niutrans-quota-or-permission";
                case "13002" -> "credentials-error";
                case "13007" -> "language-unsupported";
                default -> "remote-error";
            };
            case ALIYUN -> switch (code) {
                case "10005" -> "language-unsupported";
                case "10009" -> "credentials-error";
                default -> "remote-error";
            };
            case VOLCENGINE -> switch (code) {
                case "-429" -> "rate-limited";
                case "-415" -> "language-unsupported";
                default -> "remote-error";
            };
            default -> "remote-error";
        };
        return new MachineTranslationClient.Failure("translation." + key, "provider_rejected", 200, code);
    }

    private static List<String> rows(JsonNode values, String path, int expected) {
        if (!values.isArray() || values.size() != expected) throw invalid();
        var result = new ArrayList<String>(expected);
        for (JsonNode value : values) result.add(text(value.at(path)));
        return result;
    }

    private static String text(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }

    private static MachineTranslationClient.Failure invalid() {
        return new MachineTranslationClient.Failure("translation.invalid-response", "invalid_translation", 200, null);
    }
}
