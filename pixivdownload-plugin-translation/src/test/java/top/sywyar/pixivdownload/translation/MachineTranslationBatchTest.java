package top.sywyar.pixivdownload.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.translation.machine.TranslationBatch;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MachineTranslationBatchTest {
    @Test
    @DisplayName("短行按批次总字符与条数发送，标记和 Unicode 保持原样")
    void batchesShortLinesWithoutLosingMarkup() {
        String source = " a😀 \r\n[newpage]\n[chapter:Title]\n[[jumpuri:Link > https://example.test]]\nword ".repeat(20);
        List<List<String>> calls = new ArrayList<>();
        String result = TranslationBatch.translate(source, 10, 18, 3, texts -> {
            calls.add(texts);
            assertThat(texts).hasSizeLessThanOrEqualTo(3);
            assertThat(texts.stream().mapToInt(t -> t.codePointCount(0, t.length())).sum()).isLessThanOrEqualTo(18);
            return texts;
        });
        assertThat(result).isEqualTo(source);
        assertThat(calls.size()).isLessThan(80);
        assertThat(calls.stream().flatMap(List::stream)).noneMatch(t -> t.contains("https:") || t.contains("newpage"));
    }

    @Test
    @DisplayName("长词按码点切分，普通词边界与空白不丢失")
    void longWordsAndWhitespace() {
        String raw = "word1 word2. " + "😀".repeat(24) + "  end\t";
        List<String> chunks = new ArrayList<>();
        assertThat(TranslationBatch.translate(raw, 8, 25, 10, texts -> {
            chunks.addAll(texts);
            return texts;
        })).isEqualTo(raw);
        assertThat(chunks).contains("word1", "word2.");
        assertThat(chunks).allMatch(s -> s.codePointCount(0, s.length()) <= 8);
    }

    @Test
    @DisplayName("有道使用批量端点与全部文本签名，批量结果按原顺序拼回")
    void youdaoBatchProtocol() {
        var config = config();
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://openapi.youdao.com/v2/api")).andExpect(request -> {
            var form = form(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(form.get("q")).containsExactly("Hello", "World");
            assertThat(form.get("to")).containsExactly("zh-CHS");
            assertThat(form.get("sign")).containsExactly(MachineTranslationClient.sign("fixture-app", "HelloWorld",
                    form.get("salt").get(0), form.get("curtime").get(0), "fixture-secret"));
        }).andRespond(withSuccess("{\"errorCode\":\"0\",\"translateResults\":[{\"translation\":\"你好\"},{\"translation\":\"世界\"}]}", MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.translate("youdao", "Hello\n[newpage]\nWorld", "zh-CN", 0)).isEqualTo("你好\n[newpage]\n世界");
        server.verify();
    }

    @Test
    @DisplayName("LibreTranslate 按独立字符预算分批并以 JSON 数组发送")
    void libreBatchAndConfiguredLimits() {
        var config = config();
        config.getLibretranslate().setRequestChars(4);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:5000/translate"))
                .andExpect(content().json("{\"q\":[\"aa\",\"bb\"],\"source\":\"auto\",\"target\":\"zh\",\"format\":\"text\"}"))
                .andRespond(withSuccess("{\"translatedText\":[\"甲\",\"乙\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/translate"))
                .andExpect(content().json("{\"q\":\"cc\",\"source\":\"auto\",\"target\":\"zh\",\"format\":\"text\"}"))
                .andRespond(withSuccess("{\"translatedText\":\"丙\"}", MediaType.APPLICATION_JSON));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.translate("libretranslate", "aa\nbb\ncc", "zh-CN", 0)).isEqualTo("甲\n乙\n丙");
        server.verify();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("有道限流后仅重试当前批次并重新签名，保留已完成片段")
    void retriesOnlyRejectedBatch(org.springframework.boot.test.system.CapturedOutput output) {
        var config = config();
        config.getYoudao().setBatchSize(1);
        config.getYoudao().setMaxRetries(1);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var salts = new ArrayList<String>();
        server.expect(requestTo("https://openapi.youdao.com/api"))
                .andRespond(withSuccess("{\"errorCode\":\"0\",\"translation\":[\"甲\"]}", MediaType.APPLICATION_JSON));
        for (String response : List.of("{\"errorCode\":\"411\"}", "{\"errorCode\":\"0\",\"translation\":[\"乙\"]}")) {
            server.expect(requestTo("https://openapi.youdao.com/api")).andExpect(request -> {
                var form = form(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(form.get("q")).containsExactly("bb");
                salts.add(form.get("salt").get(0));
            }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThat(client.translate("youdao", "aa\nbb", "zh-CN", 0)).isEqualTo("甲\n乙");
        assertThat(salts).hasSize(2).doesNotHaveDuplicates();
        assertThat(output.getAll()).contains("Translation retry scheduled:", "Translation requests finished:",
                "providerCode=411", "requests=3", "batches=2", "retries=1", "outcome=OK");
        assertThat(output.getAll()).doesNotContain("fixture-secret", "fixture-app", "q=bb");
        server.verify();
    }

    @Test
    @DisplayName("不重试鉴权错误或不完整批量结果，超预算 Retry-After 直接报告限流")
    void rejectsUnsafeRetriesAndPartialResults() {
        var config = config();
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://localhost:5000/translate")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private-key"));
        server.expect(requestTo("http://localhost:5000/translate"))
                .andRespond(withSuccess("{\"translatedText\":[\"only-one\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/translate"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"));
        var client = new MachineTranslationClient(config, rest, rest, new ObjectMapper());
        assertThatThrownBy(() -> client.translate("libretranslate", "a\nb", "en", 0)).hasMessage("translation.credentials-error").hasNoCause();
        assertThatThrownBy(() -> client.translate("libretranslate", "a\nb", "en", 0)).hasMessage("translation.invalid-response");
        assertThatThrownBy(() -> client.translate("libretranslate", "a\nb", "en", 0)).hasMessage("translation.rate-limited");
        assertThatThrownBy(() -> client.translate("libretranslate", "another task", "en", 0))
                .hasMessage("translation.rate-limited").satisfies(error ->
                        assertThat(((MachineTranslationClient.Failure) error).reason).isEqualTo("cooldown"));
        server.verify();
    }

    @Test
    @DisplayName("较大单批配置获得相应的有界响应预算")
    void responseBudgetFollowsActualInput() {
        var config = config();
        config.getLibretranslate().setRequestChars(700_000);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        String translated = "译".repeat(700_000);
        server.expect(requestTo("http://localhost:5000/translate"))
                .andRespond(withSuccess("{\"translatedText\":\"" + translated + "\"}", MediaType.APPLICATION_JSON));
        assertThat(new MachineTranslationClient(config, rest, rest, new ObjectMapper())
                .translate("libretranslate", "a".repeat(700_000), "zh-CN", 0)).isEqualTo(translated);
        server.verify();
    }

    @Test
    @DisplayName("Retry-After 支持秒数和日期，巨大数值不会溢出成提前重试")
    void retryAfterParsing() {
        assertThat(MachineTranslationClient.retryAfter("600")).isEqualTo(600_000);
        assertThat(MachineTranslationClient.retryAfter("999999999999999999999999")).isEqualTo(Long.MAX_VALUE);
        String date = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(MachineTranslationClient.retryAfter(date)).isBetween(298_000L, 301_000L);
        assertThat(MachineTranslationClient.retryAfter("invalid")).isZero();
    }

    @Test
    @DisplayName("引擎约束分别验证，非法配置在启动阶段拒绝")
    void validatesEngineSettings() {
        var config = config();
        config.getLibretranslate().setRequestChars(20_000);
        config.validate();
        config.getYoudao().setRequestChars(5001);
        assertThatThrownBy(config::validate).hasMessage("translation.invalid-settings");
        config.getYoudao().setRequestChars(5000);
        config.getLibretranslate().setBatchSize(0);
        assertThatThrownBy(config::validate).hasMessage("translation.invalid-settings");
    }

    private static TranslationConfig config() {
        var config = new TranslationConfig();
        config.setYoudaoAppKey("fixture-app");
        config.setYoudaoAppSecret("fixture-secret");
        config.setLibretranslateUrl("http://localhost:5000");
        config.getYoudao().setRequestIntervalMs(0);
        return config;
    }

    private static Map<String, List<String>> form(String body) {
        return java.util.Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.groupingBy(pair -> pair[0], Collectors.mapping(
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8), Collectors.toList())));
    }
}
