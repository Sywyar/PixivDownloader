package top.sywyar.pixivdownload.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java 与 Node 共享 catalog fixture 的差异对等测试：
 * 同一批非法 catalog，Java 与 Node 必须同时拒绝（错误信息各自匹配同一正则）；
 * 合法 fixture 双方都必须通过。
 * fixture 路径：{@code scripts/i18n/test/fixtures/}（surefire 工作目录为模块 basedir）。
 */
@DisplayName("LocaleCatalog 共享 fixture：Java 与 Node 对等拒绝同一批非法目录")
class LocaleCatalogSharedFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** surefire 工作目录 = 模块 basedir（pixivdownload-app）；仓库根时直接用根路径。 */
    private static Path fixturesDir() {
        Path fromRoot = Path.of("scripts", "i18n", "test", "fixtures");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "scripts", "i18n", "test", "fixtures");
    }

    private static JsonNode load(String name) throws IOException {
        Path file = fixturesDir().resolve(name);
        assertThat(file).exists();
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("非法 catalog 15 例全部拒绝，且错误信息与共享正则匹配")
    void rejectsEverySharedInvalidFixture() throws IOException {
        JsonNode shared = load("catalog-invalid-shared.json");
        assertThat(shared.path("cases")).hasSizeGreaterThanOrEqualTo(15);
        for (JsonNode c : shared.path("cases")) {
            String id = c.path("id").asText();
            String json = MAPPER.writeValueAsString(c.path("json"));
            Pattern expected = Pattern.compile(c.path("expected").asText());
            assertThatThrownBy(() -> new LocaleCatalogLoader(null).parse(json))
                    .as("Java 必须拒绝: " + id)
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(t -> assertThat(expected.matcher(t.getMessage()).find())
                            .as("错误信息不匹配: " + id + " -> " + t.getMessage())
                            .isTrue());
        }
    }

    @Test
    @DisplayName("合法 fixture 必须通过且 alias 规范化匹配一致")
    void acceptsValidSharedFixture() throws IOException {
        JsonNode valid = load("catalog-valid.json");
        LocaleCatalog catalog = new LocaleCatalogLoader(null).parse(MAPPER.writeValueAsString(valid));
        assertThat(catalog.allLocales()).hasSize(5);
        assertThat(catalog.sourceLocale().tag()).isEqualTo("zh-CN");
        assertThat(catalog.fallbackLocale().tag()).isEqualTo("en-US");
        assertThat(catalog.match("zh-Hant-HK")).get()
                .extracting(LocaleDescriptor::tag).isEqualTo("zh-HK");
        assertThat(catalog.match("ZH_HANS")).get()
                .extracting(LocaleDescriptor::tag).isEqualTo("zh-CN");
        assertThat(catalog.match("es")).get()
                .extracting(LocaleDescriptor::tag).isEqualTo("es-ES");
    }
}
