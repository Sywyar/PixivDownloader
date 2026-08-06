package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LocaleCatalog 语言目录（i18n/locales.json 驱动）")
class LocaleCatalogTest {

    private static final String FIXTURE = """
            {
              "schemaVersion": 1,
              "sourceLocale": "zh-CN",
              "defaultLocale": "en-US",
              "fallbackLocale": "en-US",
              "languageCookieName": "pixiv_lang",
              "languageParameterName": "lang",
              "locales": [
                {
                  "tag": "zh-CN",
                  "nativeName": "简体中文",
                  "resourceSuffix": "",
                  "status": "source",
                  "direction": "ltr",
                  "aliases": ["zh", "zh-Hans", "zh-SG"]
                },
                {
                  "tag": "en-US",
                  "nativeName": "English",
                  "resourceSuffix": "en",
                  "status": "supported",
                  "direction": "ltr",
                  "aliases": ["en"]
                },
                {
                  "tag": "ja-JP",
                  "nativeName": "日本語",
                  "resourceSuffix": "ja",
                  "status": "candidate",
                  "direction": "ltr",
                  "aliases": ["ja"]
                },
                {
                  "tag": "zh-HK",
                  "nativeName": "繁體中文（香港）",
                  "resourceSuffix": "zh-HK",
                  "status": "candidate",
                  "direction": "ltr",
                  "aliases": ["zh-Hant-HK"]
                },
                {
                  "tag": "es-ES",
                  "nativeName": "Español",
                  "resourceSuffix": "es",
                  "status": "disabled",
                  "direction": "ltr",
                  "aliases": ["es"]
                }
              ]
            }
            """;

    private static LocaleCatalog fixture() {
        return new LocaleCatalogLoader(null).parse(FIXTURE);
    }

    @Test
    @DisplayName("默认目录从 classpath 加载且 source/default/fallback 正确（默认语言为 en-US）")
    void defaultCatalogExposesSourceDefaultFallback() {
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        assertThat(catalog.schemaVersion()).isEqualTo(1);
        assertThat(catalog.sourceLocale().tag()).isEqualTo("zh-CN");
        assertThat(catalog.sourceLocale().status()).isEqualTo(LocaleStatus.SOURCE);
        assertThat(catalog.defaultLocale().tag()).isEqualTo("en-US");
        assertThat(catalog.fallbackLocale().tag()).isEqualTo("en-US");
        assertThat(catalog.fallbackLocale().status()).isEqualTo(LocaleStatus.SUPPORTED);
        assertThat(catalog.languageCookieName()).isEqualTo("pixiv_lang");
        assertThat(catalog.languageParameterName()).isEqualTo("lang");
    }

    @Test
    @DisplayName("精确规范化 tag 匹配（大小写与 _ / - 容错）")
    void matchesExactNormalizedTags() {
        LocaleCatalog catalog = fixture();
        assertThat(catalog.match("zh-CN")).contains(fixture().sourceLocale());
        assertThat(catalog.match("en-US")).contains(fixture().fallbackLocale());
        assertThat(catalog.match("ZH_CN")).contains(fixture().sourceLocale());
        assertThat(catalog.match("  en-us ")).contains(fixture().fallbackLocale());
        assertThat(catalog.match(Locale.SIMPLIFIED_CHINESE)).contains(fixture().sourceLocale());
    }

    @Test
    @DisplayName("alias 匹配（zh / zh-Hans / zh-SG → zh-CN）")
    void matchesAliases() {
        LocaleCatalog catalog = fixture();
        assertThat(catalog.match("zh")).contains(fixture().sourceLocale());
        assertThat(catalog.match("zh-Hans")).contains(fixture().sourceLocale());
        assertThat(catalog.match("zh-SG")).contains(fixture().sourceLocale());
        assertThat(catalog.match("ZH")).contains(fixture().sourceLocale());
        assertThat(catalog.match("en")).contains(fixture().fallbackLocale());
    }

    @Test
    @DisplayName("match(Locale) 与 match(String) 共享 alias 逻辑：zh-Hans / zh-SG / zh-Hant-HK / en 一致")
    void localeFormMatchesAliasesExactlyLikeStringForm() {
        LocaleCatalog catalog = fixture();
        for (String candidate : List.of("zh-Hans", "zh-SG", "zh-hans", "zh-Hant-HK", "en", "en-GB")) {
            assertThat(catalog.match(Locale.forLanguageTag(candidate)))
                    .as("match(Locale.forLanguageTag(\"" + candidate + "\"))")
                    .isEqualTo(catalog.match(candidate));
        }
        assertThat(catalog.match(Locale.forLanguageTag("zh-Hans"))).contains(fixture().sourceLocale());
        assertThat(catalog.match(Locale.forLanguageTag("zh-hans"))).contains(fixture().sourceLocale());
        assertThat(catalog.match(Locale.forLanguageTag("zh-Hant-HK")))
                .contains(fixture().allLocales().stream().filter(d -> d.tag().equals("zh-HK")).findFirst().orElseThrow());
        // 精确 tag 形态也不漂移
        assertThat(catalog.match(Locale.forLanguageTag("zh-CN"))).contains(fixture().sourceLocale());
        assertThat(catalog.match(Locale.SIMPLIFIED_CHINESE)).contains(fixture().sourceLocale());
        assertThat(catalog.match(Locale.forLanguageTag("en-US"))).contains(fixture().fallbackLocale());
        // 字符串形态容忍 _ / - 混用（Locale.forLanguageTag 无法承载下划线，String 路径覆盖）
        assertThat(catalog.match("ZH_HANS")).contains(fixture().sourceLocale());
    }

    @Test
    @DisplayName("match(Locale)：同语言多个正式 tag 且无 alias 时语言级匹配歧义 → 无匹配；有 alias 时走 alias")
    void localeFormLanguageLevelMatchRequiresUniqueness() {
        LocaleCatalog catalog = fixture();
        // zh-CN 与 zh-HK 都是 zh 语言，且都没有泛化 zh-TW alias → 语言级歧义 → 无匹配
        assertThat(catalog.match(Locale.forLanguageTag("zh-TW"))).isEmpty();
        assertThat(catalog.match("zh-TW")).isEmpty();
        // zh-Hans 是 zh-CN 的显式 alias → 非歧义命中
        assertThat(catalog.match(Locale.forLanguageTag("zh-Hans"))).contains(fixture().sourceLocale());
        // zh-Hant-HK 是 zh-HK 的显式 alias → 非歧义命中
        assertThat(catalog.match(Locale.forLanguageTag("zh-Hant-HK")))
                .contains(fixture().allLocales().stream().filter(d -> d.tag().equals("zh-HK")).findFirst().orElseThrow());
        // en 语言唯一 → en-GB 唯一命中 en-US（String 与 Locale 一致）
        assertThat(catalog.match(Locale.forLanguageTag("en-GB"))).contains(fixture().fallbackLocale());
        assertThat(catalog.match(Locale.forLanguageTag("en-GB"))).isEqualTo(catalog.match("en-GB"));
        // null / 空语言 Locale → 无匹配
        assertThat(catalog.match((Locale) null)).isEmpty();
        assertThat(catalog.match(Locale.forLanguageTag(""))).isEmpty();
    }

    @Test
    @DisplayName("语言级匹配仅在结果唯一时允许；歧义时不错误匹配")
    void languageLevelMatchRequiresUniqueness() {
        LocaleCatalog catalog = fixture();
        // zh-HK 与 zh-CN 同为 zh 语言 → "zh-TW" 语言级匹配歧义 → 无匹配
        assertThat(catalog.match("zh-TW")).isEmpty();
        // en 语言只有一个 → "en-GB" 唯一命中 en-US
        assertThat(catalog.match("en-GB")).contains(fixture().fallbackLocale());
        // ja 语言只有一个候选 locale → "ja-JP" 精确匹配
        assertThat(catalog.match("ja-JP")).contains(
                fixture().allLocales().stream().filter(d -> d.tag().equals("ja-JP")).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("无匹配时 resolve 落到默认语言 en-US")
    void resolveFallsBackToDefaultLocale() {
        LocaleCatalog catalog = fixture();
        assertThat(catalog.resolve("fr-FR").tag()).isEqualTo("en-US");
        assertThat(catalog.resolve((String) null).tag()).isEqualTo("en-US");
        assertThat(catalog.resolve(Locale.FRENCH).tag()).isEqualTo("en-US");
        assertThat(catalog.match("")).isEmpty();
    }

    @Test
    @DisplayName("visibleLocales 只含 source/supported，不含 candidate/disabled")
    void visibleLocalesExcludeCandidateAndDisabled() {
        LocaleCatalog catalog = fixture();
        assertThat(catalog.visibleLocales())
                .extracting(LocaleDescriptor::tag)
                .containsExactly("zh-CN", "en-US");
        assertThat(catalog.allLocales()).hasSize(5);
    }

    @Test
    @DisplayName("回退链：目标语言 → fallback → source（去重）")
    void fallbackChainFollowsContract() {
        LocaleCatalog catalog = fixture();
        LocaleDescriptor zh = catalog.sourceLocale();
        LocaleDescriptor en = catalog.fallbackLocale();
        LocaleDescriptor ja = catalog.allLocales().stream()
                .filter(d -> d.tag().equals("ja-JP")).findFirst().orElseThrow();

        assertThat(catalog.fallbackChain(ja)).extracting(LocaleDescriptor::tag)
                .containsExactly("ja-JP", "en-US", "zh-CN");
        assertThat(catalog.fallbackChain(en)).extracting(LocaleDescriptor::tag)
                .containsExactly("en-US", "zh-CN");
        assertThat(catalog.fallbackChain(zh)).extracting(LocaleDescriptor::tag)
                .containsExactly("zh-CN", "en-US");
    }

    @Test
    @DisplayName("Accept-Language 解析基于 catalog 可见语言集，无匹配落到默认")
    void acceptLanguageResolutionIsCatalogDriven() {
        LocaleCatalog catalog = fixture();
        assertThat(Locale.lookup(Locale.LanguageRange.parse("ja-JP,ja;q=0.8"),
                catalog.visibleLocales().stream().map(LocaleDescriptor::toLocale).toList()))
                .isNull(); // 候选语言不在可见集内 → 不参与 Accept-Language 匹配
        assertThat(Locale.lookup(Locale.LanguageRange.parse("en-US,zh-CN;q=0.5"),
                catalog.visibleLocales().stream().map(LocaleDescriptor::toLocale).toList()))
                .isEqualTo(Locale.US);
    }
}
