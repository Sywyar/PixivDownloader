package top.sywyar.pixivdownload.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CloudTranslationProtocolTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({"1000, 0, 1000", "1001, 0, 1000", "1500, 1500, 1500"})
    void baiduRespectsStandardAccountDefaultAndExplicitBudget(int length, int configured, int firstLength) {
        var env = new MockEnvironment().withProperty("translation.providers.baidu.key-id", "id")
                .withProperty("translation.providers.baidu.secret", "secret")
                .withProperty("translation.providers.baidu.request-interval-ms", "0");
        if (configured > 0) env.setProperty("translation.providers.baidu.request-chars", Integer.toString(configured));
        var config = new TranslationPluginConfiguration().translationConfig(env);
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        String source = "猫".repeat(length - 1) + "😀";
        int end = source.offsetByCodePoints(0, firstLength);
        for (String part : new String[]{source.substring(0, end), source.substring(end)}) {
            if (part.isEmpty()) continue;
            server.expect(method(HttpMethod.POST)).andExpect(content().formDataContains(Map.of("q", part)))
                    .andRespond(withSuccess(mapper.writeValueAsString(Map.of("trans_result", java.util.List.of(Map.of("dst", part)))),
                            MediaType.APPLICATION_JSON));
        }
        try (var client = new MachineTranslationClient(config, Map.of("baidu", http), mapper)) {
            assertThat(client.translate("baidu", source, "en", 0)).isEqualTo(source);
        }
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}",
            "{\"message\":\"User Rate Limit Exceeded\"}"
    })
    void retriesGoogleMinuteQuota(String error) {
        var config = new TranslationConfig();
        config.cloud(CloudProvider.GOOGLE).setApiKey("fixture");
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\":" + error + "}").contentType(MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.POST)).andRespond(withSuccess(
                "{\"data\":{\"translations\":[{\"translatedText\":\"春\"}]}}", MediaType.APPLICATION_JSON));
        try (var client = new MachineTranslationClient(config, Map.of("google", http), mapper)) {
            assertThat(client.translate("google", "spring", "zh", 0)).isEqualTo("春");
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GOOGLE | 403 | {\"error\":{\"message\":\"Daily Limit Exceeded\"}} | translation.daily-quota-exceeded",
            "GOOGLE | 403 | {\"error\":{\"errors\":[{\"reason\":\"dailyLimitExceeded\"}]}} | translation.daily-quota-exceeded",
            "GOOGLE | 403 | {\"error\":{\"details\":[{\"reason\":\"API_KEY_SERVICE_BLOCKED\"}]}} | translation.credentials-error",
            "GOOGLE | 403 | {\"error\":{\"message\":\"private diagnostic\"}} | translation.access-denied",
            "GOOGLE | 403 | <html>Access denied</html> | translation.access-denied",
            "NIUTRANS | 200 | {\"error_code\":\"13001\"} | translation.niutrans-quota-or-permission",
            "NIUTRANS | 200 | {\"error_code\":\"13002\"} | translation.credentials-error"
    })
    void reportsProviderFailureWithoutRetry(CloudProvider provider, int status, String body, String key) {
        var config = new TranslationConfig();
        config.cloud(provider).setApiKey("fixture");
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body(body));
        try (var client = new MachineTranslationClient(config, Map.of(provider.id, http), mapper)) {
            assertThatThrownBy(() -> client.translate(provider.id, "spring", "zh", 0))
                    .isInstanceOfSatisfying(MachineTranslationClient.Failure.class, failure -> {
                        assertThat(NovelTranslationService.errorKey(failure)).isEqualTo(key);
                        assertThat(failure.httpStatus).isEqualTo(status);
                    }).hasNoCause();
        }
        server.verify();
    }

    @Test
    void googleForbiddenBodyWithoutContentLengthStillHasAReadLimit() {
        var config = new TranslationConfig();
        config.cloud(CloudProvider.GOOGLE).setApiKey("fixture");
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(method(HttpMethod.POST)).andRespond(request -> new MockClientHttpResponse(
                new byte[MachineTranslationClient.RESPONSE_BYTES + 1], HttpStatus.FORBIDDEN));
        try (var client = new MachineTranslationClient(config, Map.of("google", http), mapper)) {
            assertThatThrownBy(() -> client.translate("google", "spring", "zh", 0))
                    .hasMessage("translation.response-too-large").hasNoCause();
        }
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void translatesThroughSelectedProvider(CloudProvider provider) {
        String prefix = "translation.providers." + provider.id + ".";
        var env = new MockEnvironment().withProperty("translation.engine", provider.id)
                .withProperty(prefix + "api-key", "fixture:fx").withProperty(prefix + "key-id", "test-id")
                .withProperty(prefix + "secret", "test-secret").withProperty(prefix + "region", "eastasia")
                .withProperty(prefix + "request-interval-ms", "0");
        var config = new TranslationPluginConfiguration().translationConfig(env);
        assertThat(config.machine(provider.id).getBatchSize()).isEqualTo(provider.batch);
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        String host = provider == CloudProvider.DEEPL ? "api-free.deepl.com" : provider.uri.getHost();
        String original = "花 & +😀";
        String output = "春";
        String response = switch (provider) {
            case DEEPL -> "{\"translations\":[{\"text\":\"春\"}]}";
            case GOOGLE -> "{\"data\":{\"translations\":[{\"translatedText\":\"春\"}]}}";
            case AZURE -> "[{\"translations\":[{\"text\":\"春\"}]}]";
            case BAIDU -> "{\"trans_result\":[{\"dst\":\"春\"}]}";
            case TENCENT -> "{\"Response\":{\"TargetText\":\"春\"}}";
            case VOLCENGINE -> "{\"TranslationList\":[{\"Translation\":\"春\"}]}";
            case CAIYUN -> "{\"target\":[\"春\"]}";
            case NIUTRANS -> "{\"tgt_text\":\"春\"}";
            case ALIYUN -> "{\"Code\":200,\"Data\":{\"Translated\":\"春\"}}";
        };
        server.expect(method(HttpMethod.POST)).andExpect(request -> {
            assertThat(request.getURI().getHost()).isEqualTo(host);
            assertThat(request.getURI().toString()).doesNotContain("test-secret", "fixture");
            var headers = request.getHeaders();
            String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
            var json = MediaType.APPLICATION_JSON.equals(headers.getContentType()) ? mapper.readTree(body) : null;
            switch (provider) {
                case DEEPL -> {
                    assertThat(headers.getFirst("Authorization")).isEqualTo("DeepL-Auth-Key fixture:fx");
                    assertThat(json.path("target_lang").asString()).isEqualTo("ZH-HANT");
                    assertThat(json.at("/text/0").asString()).isEqualTo(original);
                }
                case GOOGLE -> {
                    assertThat(headers.getFirst("X-Goog-Api-Key")).isEqualTo("fixture:fx");
                    assertThat(json.at("/q/0").asString()).isEqualTo(original);
                    assertThat(json.path("target").asString()).isEqualTo("zh-TW");
                }
                case AZURE -> {
                    assertThat(request.getURI().getQuery()).contains("to=zh-Hant");
                    assertThat(headers.getFirst("Ocp-Apim-Subscription-Region")).isEqualTo("eastasia");
                    assertThat(headers.getFirst("Ocp-Apim-Subscription-Key")).isEqualTo("fixture:fx");
                    assertThat(json.at("/0/Text").asString()).isEqualTo(original);
                }
                case CAIYUN -> {
                    assertThat(headers.getFirst("X-Authorization")).isEqualTo("token fixture:fx");
                    assertThat(json.path("trans_type").asString()).isEqualTo("auto2zh-Hant");
                    assertThat(json.at("/source/0").asString()).isEqualTo(original);
                    assertThat(json.path("detect").asBoolean()).isTrue();
                }
                case BAIDU, NIUTRANS, ALIYUN -> {
                    Map<String,String> fields = java.util.Arrays.stream(body.split("&")).map(v -> v.split("=", 2))
                            .collect(java.util.stream.Collectors.toMap(v -> v[0], v -> URLDecoder.decode(v[1], StandardCharsets.UTF_8)));
                    if (provider == CloudProvider.BAIDU) {
                        assertThat(fields).containsEntry("q", original).containsEntry("to", "cht");
                        String signed = "test-id" + original + fields.get("salt") + "test-secret";
                        assertThat(fields.get("sign")).isEqualTo(org.springframework.util.DigestUtils.md5DigestAsHex(signed.getBytes(StandardCharsets.UTF_8)));
                    } else if (provider == CloudProvider.NIUTRANS) {
                        assertThat(fields).containsEntry("src_text", original).containsEntry("to", "cht").containsEntry("apikey", "fixture:fx");
                    } else {
                        assertThat(fields).containsEntry("SourceText", original).containsEntry("TargetLanguage", "zh-tw");
                        assertThat(headers.getFirst("x-acs-action")).isEqualTo("TranslateGeneral");
                    }
                }
                case TENCENT -> {
                    assertThat(headers.getFirst("X-TC-Action")).isEqualTo("TextTranslate");
                    assertThat(headers.getFirst("X-TC-Region")).isEqualTo("eastasia");
                    assertThat(json.path("SourceText").asString()).isEqualTo(original);
                    assertThat(json.path("Target").asString()).isEqualTo("zh-TW");
                }
                case VOLCENGINE -> {
                    assertThat(request.getURI().getQuery()).isEqualTo("Action=TranslateText&Version=2020-06-01");
                    assertThat(json.at("/TextList/0").asString()).isEqualTo(original);
                    assertThat(json.path("TargetLanguage").asString()).isEqualTo("zh-Hant");
                }
            }
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        try (var client = new MachineTranslationClient(config, Map.of(provider.id, http), mapper)) {
            assertThat(client.available(provider.id)).isTrue();
            if (provider != CloudProvider.DEEPL) assertThat(client.languages(provider.id))
                    .extracting(MachineTranslationClient.Language::code).contains("zh-CN", "zh-TW", "en-US", "ja-JP").doesNotHaveDuplicates();
            assertThat(client.translate(provider.id, original, "zh-TW", 0)).isEqualTo(output);
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "TENCENT,484efebde12a8af34de26f24b34e7aed4b256832a15836bc587a9868e0515e10",
            "VOLCENGINE,ee3479a2321947b4a8c4da10cb5caef036915f136795055deafd7b19cb1a86d9",
            "ALIYUN,6a4882c9ea2920f64acef3b8fe005fb2f8c93c117dad0e5d5ee47f24150b3fc3"
    })
    void signsExactBodyAndCanonicalHeaders(CloudProvider provider, String signature) {
        var settings = new TranslationConfig().cloud(provider);
        settings.setKeyId("id");
        settings.setSecret("secret");
        String type = provider == CloudProvider.ALIYUN ? "application/x-www-form-urlencoded" : "application/json";
        var headers = TranslationSignatures.sign(provider, settings, provider.uri, type,
                "{}".getBytes(StandardCharsets.UTF_8), Instant.parse("2024-01-01T00:00:00Z"), "fixture-nonce");
        assertThat(headers.get("Authorization")).endsWith("Signature=" + signature);
    }

    @Test
    void aliyunBoundsUtf16WithoutSplittingSurrogates() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var config = new TranslationConfig();
        var settings = config.cloud(CloudProvider.ALIYUN);
        settings.setKeyId("id");
        settings.setSecret("secret");
        String first = "a" + "😀".repeat(2499);
        for (String part : new String[]{first, "😀"}) {
            server.expect(method(HttpMethod.POST)).andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                assertThat(URLDecoder.decode(body, StandardCharsets.UTF_8).split("&")).contains("SourceText=" + part);
            }).andRespond(withSuccess("{\"Data\":{\"Translated\":\"ok\"},\"Code\":200}", MediaType.APPLICATION_JSON));
        }
        try (var client = new MachineTranslationClient(config, Map.of("aliyun", http), mapper)) {
            assertThat(client.translate("aliyun", first + "😀", "en", 0)).isEqualTo("okok");
        }
        server.verify();
    }

    @Test
    void rejectsIncompleteBatchesAndProviderErrors() {
        var batch = mapper.readTree("{\"target\":[\"二\",\"一\"]}");
        assertThat(CloudTranslationProtocol.translations(CloudProvider.CAIYUN, batch, 2)).containsExactly("二", "一");
        assertThatThrownBy(() -> CloudTranslationProtocol.translations(CloudProvider.CAIYUN, batch, 3))
                .hasMessage("translation.invalid-response");
        var error = mapper.readTree("{\"Response\":{\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\",\"Message\":\"private\"}}}");
        assertThatThrownBy(() -> CloudTranslationProtocol.translations(CloudProvider.TENCENT, error, 1))
                .hasMessage("translation.credentials-error").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ja"})
    void deepLProjectsOnlyTargetsAndUsesProviderGlossary(String sourceLanguage) {
        var config = new TranslationConfig();
        var settings = config.cloud(CloudProvider.DEEPL);
        settings.setApiKey("pro-key");
        settings.setGlossaryId("test-glossary");
        settings.setSourceLanguage(sourceLanguage);
        settings.setFormality("prefer_more");
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://api.deepl.com/v3/languages?resource=translate_text"))
                .andExpect(header("Authorization", "DeepL-Auth-Key pro-key")).andRespond(withSuccess(
                        "[{\"lang\":\"EN\",\"usable_as_target\":false},{\"lang\":\"EN-GB\",\"usable_as_target\":true}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.deepl.com/v2/translate")).andExpect(content().json(
                "{\"text\":[\"first\",\"second\"],\"target_lang\":\"EN-GB\",\"glossary_id\":\"test-glossary\",\"formality\":\"prefer_more\"}"))
                .andExpect(request -> {
                    var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
                    if (sourceLanguage.isEmpty()) assertThat(body.has("source_lang")).isFalse();
                    else assertThat(body.path("source_lang").asString()).isEqualTo("JA");
                })
                .andRespond(withSuccess("{\"translations\":[{\"text\":\"one\"},{\"text\":\"two\"}]}", MediaType.APPLICATION_JSON));
        try (var client = new MachineTranslationClient(config, Map.of("deepl", http), mapper)) {
            assertThat(client.languages("deepl")).extracting(MachineTranslationClient.Language::code).containsExactly("en-GB");
            assertThat(client.translate("deepl", "first\n[newpage]second", "en-GB", 0)).isEqualTo("one\n[newpage]two");
        }
        server.verify();
    }
}
