package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("普通翻译 API 的协议与正文保真")
class MachineTranslationClientTest {
    @Test
    @DisplayName("有道目标目录使用标准语言身份，中文和塞尔维亚文字体按厂商代码发送")
    void youdaoLanguagesAndProviderCodes() {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setYoudaoAppKey("fixture-app");
        config.setYoudaoAppSecret("fixture-secret");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.languages("youdao")).extracting(MachineTranslationClient.Language::code)
                .contains("zh-CN", "zh-TW", "ja-JP", "sr-Latn", "sr-Cyrl", "jv").doesNotHaveDuplicates().doesNotContain("auto");
        for (String[] pair : new String[][]{{"zh-CN", "zh-CHS"}, {"zh-TW", "zh-CHT"}, {"sr-Latn", "sr-Latn"}, {"jv", "jw"}}) {
            server.expect(requestTo("https://openapi.youdao.com/api"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("&to=" + pair[1] + "&")))
                    .andRespond(withSuccess("{\"errorCode\":\"0\",\"translation\":[\"translated\"]}", MediaType.APPLICATION_JSON));
        }
        for (String target : new String[]{"zh-CN", "zh-TW", "sr-Latn", "jv"})
            assertThat(client.translate("youdao", "hello", target, 0)).isEqualTo("translated");
        assertThatThrownBy(() -> client.translate("youdao", "hello", "zz", 0)).hasMessage("translation.language-unsupported");
        server.verify();
    }

    @Test
    @DisplayName("LibreTranslate 从配置地址读取实际目标模型，不发送正文或密钥")
    void libreLanguagesUseInstalledTargets() {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setLibretranslateUrl("http://localhost:5000/service/");
        config.setLibretranslateApiKey("fixture-private-key");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:5000/service/languages"))
                .andExpect(method(org.springframework.http.HttpMethod.GET)).andExpect(content().string(""))
                .andRespond(withSuccess("[{\"code\":\"ja\",\"targets\":[\"en\",\"zh\",\"zt\"]},"
                        + "{\"code\":\"en\",\"targets\":[\"zh\"]}]", MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.languages("libretranslate")).extracting(MachineTranslationClient.Language::code)
                .containsExactly("en-US", "zh-CN", "zh-TW");
        server.verify();
        assertThat(MachineTranslationClient.providerLanguage("libretranslate", "pt-BR")).isEqualTo("pt-BR");
        assertThat(MachineTranslationClient.providerLanguage("libretranslate", "sr-Latn")).isEqualTo("sr-Latn");
    }

    @Test
    @DisplayName("语言目录拒绝超量目标、非法 JSON 与 HTTP 错误，保持空模型的空结果")
    void languageCatalogBoundaries() throws Exception {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setLibretranslateUrl("http://localhost:5000");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var mapper = new ObjectMapper();
        var targets = new java.util.ArrayList<String>();
        for (int i = 0; i <= MachineTranslationClient.MAX_LANGUAGES; i++)
            targets.add("a" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        server.expect(requestTo("http://localhost:5000/languages")).andRespond(withSuccess(
                mapper.writeValueAsString(java.util.List.of(java.util.Map.of("targets", targets.subList(0, MachineTranslationClient.MAX_LANGUAGES)))), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/languages")).andRespond(withSuccess(
                mapper.writeValueAsString(java.util.List.of(java.util.Map.of("targets", targets))), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/languages")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/languages")).andRespond(withSuccess("not-json private-novel", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/languages")).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body("fixture-secret"));
        var client = new MachineTranslationClient(config, rest, rest, mapper);
        assertThat(client.languages("libretranslate")).hasSize(MachineTranslationClient.MAX_LANGUAGES);
        assertThatThrownBy(() -> client.languages("libretranslate")).hasMessage("translation.response-too-large");
        assertThat(client.languages("libretranslate")).isEmpty();
        assertThatThrownBy(() -> client.languages("libretranslate")).hasMessage("translation.invalid-response").hasNoCause();
        assertThatThrownBy(() -> client.languages("libretranslate")).hasMessage("translation.rate-limited").hasNoCause();
        server.verify();
    }

    @Test
    @DisplayName("有道服务错误保留安全错误码与本地化分类，不保留原文和凭据")
    void providerErrorsRemainDiagnosable() {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setYoudaoAppKey("fixture-app");
        config.setYoudaoAppSecret("fixture-secret");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        for (String code : new String[]{"110", "202", "401", "102", "411", "206", "secret-injected"})
            server.expect(requestTo("https://openapi.youdao.com/api")).andRespond(withSuccess(
                    "{\"errorCode\":\"" + code + "\",\"query\":\"private-novel\",\"errorMessage\":\"fixture-secret\"}", MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        for (String key : new String[]{"service-not-enabled", "credentials-error", "balance-error", "language-unsupported", "rate-limited", "clock-error", "remote-error"}) {
            assertThatThrownBy(() -> client.translate("youdao", "private-novel", "zh-CN", 0))
                    .isInstanceOfSatisfying(MachineTranslationClient.Failure.class, failure -> {
                        assertThat(failure.httpStatus).isEqualTo(200);
                        assertThat(failure.providerCode).matches("[0-9]+|none");
                    }).hasMessage("translation." + key).hasNoCause();
        }
        server.verify();
    }

    @Test
    @DisplayName("每段未超限时仍限制累计输出，拒绝远端持续膨胀正文")
    void boundsAccumulatedOutput() {
        String segment = "x".repeat(1024 * 1024);
        assertThatThrownBy(() -> top.sywyar.pixivdownload.translation.machine.TranslationBatch.translate("a".repeat(65), 1, 1, 1, texts -> java.util.List.of(segment)))
                .hasMessage("translation.response-too-large");
        assertThat(MachineTranslationClient.providerLanguage("youdao", "zh-HK")).isEqualTo("zh-CHT");
        assertThat(MachineTranslationClient.providerLanguage("libretranslate", "zh-SG")).isEqualTo("zh");
    }

    @Test
    @DisplayName("超长单行和补充平面字符分段后不丢失，标记和链接保持原样")
    void preservesMarkupAndUnicode() {
        String raw = "  " + "a😀".repeat(1400) + "\r\n[newpage]\n[chapter:Heading]\n"
                + "[[jumpuri:Label > https://example.test/?a=1&b=2]] [pixivimage:123-2] [[rb:字 > じ]]";
        AtomicInteger calls = new AtomicInteger();
        String result = top.sywyar.pixivdownload.translation.machine.TranslationBatch.translate(raw, 1000, 1000, 1, texts -> {
            String text = texts.get(0);
            calls.incrementAndGet();
            assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(1000);
            assertThat(text).doesNotContain("pixivimage", "https://", "[newpage]");
            assertThat(Character.isLowSurrogate(text.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
            return texts;
        });
        assertThat(result).isEqualTo(raw);
        assertThat(calls).hasValue(6);
    }

    @Test
    @DisplayName("有道签名按 Unicode 字符计数并使用 SHA256")
    void signsFullUnicodeInput() throws Exception {
        String text = "😀".repeat(21);
        String input = "😀".repeat(10) + "21" + "😀".repeat(10);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("app" + input + "salt" + "time" + "secret").getBytes(StandardCharsets.UTF_8)));
        assertThat(MachineTranslationClient.sign("app", text, "salt", "time", "secret")).isEqualTo(expected);
    }

    @Test
    @DisplayName("LibreTranslate 使用 JSON 和 UTF8 响应，服务地址不携带凭据或查询参数")
    void libreTranslateUsesConfiguredService() {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setLibretranslateUrl("http://127.0.0.1:5000");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://127.0.0.1:5000/translate"))
                .andExpect(content().json("{\"q\":\"hello\",\"source\":\"auto\",\"target\":\"zh\",\"format\":\"text\"}"))
                .andRespond(withSuccess("{\"translatedText\":\"你好\"}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.translate("libretranslate", "hello", "zh-CN", 0)).isEqualTo("你好");
        server.verify();
        for (String address : new String[]{"file:///tmp/data", "http://user:secret@localhost", "https://example.test?token=secret", "https://example.test#x"})
            assertThatThrownBy(() -> MachineTranslationClient.endpoint(address)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("超限响应在解析前拒绝，不回显服务端内容")
    void refusesOversizedResponse() {
        var config = new TranslationConfig();
        config.getYoudao().setRequestIntervalMs(0);
        config.getYoudao().setMaxRetries(0);
        config.setLibretranslateUrl("http://127.0.0.1:5000");
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://127.0.0.1:5000/translate"))
                .andRespond(withSuccess(new byte[MachineTranslationClient.RESPONSE_BYTES + 1], MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThatThrownBy(() -> client.translate("libretranslate", "hello", "en", 0))
                .hasMessage("translation.response-too-large");
        server.verify();
    }
}
