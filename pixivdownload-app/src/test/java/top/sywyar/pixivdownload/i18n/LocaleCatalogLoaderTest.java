package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LocaleCatalogLoader 目录校验（非法清单启动失败）")
class LocaleCatalogLoaderTest {

    private static final String VALID = """
            {
              "schemaVersion": 1,
              "sourceLocale": "zh-CN",
              "defaultLocale": "zh-CN",
              "fallbackLocale": "en-US",
              "languageCookieName": "pixiv_lang",
              "languageParameterName": "lang",
              "locales": [
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh"]},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]}
              ]
            }
            """;

    private static LocaleCatalog parse(String json) {
        return new LocaleCatalogLoader(null).parse(json);
    }

    private static String withLocales(String localesJson) {
        return """
                {
                  "schemaVersion": 1,
                  "sourceLocale": "zh-CN",
                  "defaultLocale": "zh-CN",
                  "fallbackLocale": "en-US",
                  "languageCookieName": "pixiv_lang",
                  "languageParameterName": "lang",
                  "locales": [%s]
                }
                """.formatted(localesJson);
    }

    @Test
    @DisplayName("合法清单成功读取")
    void validCatalogParses() {
        LocaleCatalog catalog = parse(VALID);
        assertThat(catalog.visibleLocales()).hasSize(2);
        assertThat(catalog.defaultLocale().tag()).isEqualTo("zh-CN");
    }

    @Test
    @DisplayName("schemaVersion 不可识别立即失败")
    void unknownSchemaVersionFails() {
        assertThatThrownBy(() -> parse(VALID.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("非法 JSON 立即失败")
    void malformedJsonFails() {
        assertThatThrownBy(() -> parse("not json at all"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺少 sourceLocale 指向的 locale 立即失败")
    void sourceLocaleMustExist() {
        assertThatThrownBy(() -> parse(withLocales("""
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceLocale");
    }

    @Test
    @DisplayName("缺少 defaultLocale / fallbackLocale 立即失败")
    void defaultAndFallbackMustExist() {
        assertThatThrownBy(() -> parse(withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""")
                .replace("\"defaultLocale\": \"zh-CN\"", "\"defaultLocale\": \"fr-FR\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultLocale");
    }

    @Test
    @DisplayName("恰好一个 source；fallback 必须 source/supported；default 必须可见")
    void statusRulesEnforced() {
        String twoSources = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "zh-HK", "nativeName": "繁體中文", "resourceSuffix": "zh-HK", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(twoSources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");

        String candidateFallback = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "candidate", "direction": "ltr", "aliases": []}""")
                .replace("\"fallbackLocale\": \"en-US\"", "\"fallbackLocale\": \"en-US\"");
        assertThatThrownBy(() -> parse(candidateFallback))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackLocale");
    }

    @Test
    @DisplayName("tag 重复 / alias 冲突 / resourceSuffix 冲突立即失败")
    void duplicatesAndConflictsFail() {
        String dupTag = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "x", "status": "supported", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(dupTag)).hasMessageContaining("duplicate locale tag");

        String dupSuffix = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(dupSuffix)).hasMessageContaining("resourceSuffix");

        String aliasConflict = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh"]},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["zh"]}""");
        assertThatThrownBy(() -> parse(aliasConflict)).hasMessageContaining("alias conflict");
    }

    @Test
    @DisplayName("未知状态 / 非法 direction / 空 nativeName 立即失败")
    void invalidFieldsFail() {
        String unknownStatus = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "weird", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(unknownStatus)).hasMessageContaining("status");

        String badDirection = withLocales("""
                {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "sideways", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(badDirection)).hasMessageContaining("direction");

        String emptyNative = withLocales("""
                {"tag": "zh-CN", "nativeName": " ", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
                {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}""");
        assertThatThrownBy(() -> parse(emptyNative)).hasMessageContaining("nativeName");
    }
}
