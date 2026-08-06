package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogLocaleBundlePolicy：由 LocaleCatalog 生成的第一方策略")
class CatalogLocaleBundlePolicyTest {

    private static LocaleCatalog fixture() {
        return new LocaleCatalogLoader(null).parse("""
                {
                  "schemaVersion": 1,
                  "sourceLocale": "zh-CN",
                  "defaultLocale": "en-US",
                  "fallbackLocale": "en-US",
                  "languageCookieName": "pixiv_lang",
                  "languageParameterName": "lang",
                  "locales": [
                    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
                    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
                    {"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate", "direction": "ltr", "aliases": ["ja"]}
                  ]
                }
                """);
    }

    @Test
    @DisplayName("suffix 链直接表达目标 → fallback → source（空串 = root）")
    void suffixChainFollowsCatalogFallbackOrder() {
        LocaleBundlePolicy policy = new CatalogLocaleBundlePolicy(fixture());
        assertThat(policy.resourceSuffixChain(Locale.JAPANESE))
                .containsExactly("ja", "en", "");
        assertThat(policy.resourceSuffixChain(Locale.US))
                .containsExactly("en", "");
        assertThat(policy.resourceSuffixChain(Locale.SIMPLIFIED_CHINESE))
                .containsExactly("", "en");
    }

    @Test
    @DisplayName("normalize：alias / 大小写 / 未知语言统一落到 catalog 正式 tag")
    void normalizeResolvesAliasAndUnknownToCanonicalTags() {
        LocaleBundlePolicy policy = new CatalogLocaleBundlePolicy(fixture());
        assertThat(policy.normalize(Locale.forLanguageTag("zh-Hans")).toLanguageTag())
                .isEqualTo("zh-CN");
        assertThat(policy.normalize(Locale.forLanguageTag("ja")).toLanguageTag())
                .isEqualTo("ja-JP");
        assertThat(policy.normalize(Locale.FRENCH).toLanguageTag())
                .isEqualTo("en-US"); // 未知语言 → default
        // Locale 形态的 alias 归一化（match(Locale) 与 match(String) 一致；Java 无法用 forLanguageTag 承载下划线）
        assertThat(policy.normalize(Locale.forLanguageTag("zh-hans")).toLanguageTag())
                .isEqualTo(policy.normalize(Locale.forLanguageTag("zh-Hans")).toLanguageTag());
        assertThat(policy.normalize(Locale.forLanguageTag("zh-Hans")).toLanguageTag())
                .isEqualTo("zh-CN");
    }

    @Test
    @DisplayName("resolver 按 catalog 策略加载：candidate 缺失时先 fallback 再 source")
    void resolverUsesCatalogPolicyChain() {
        LocaleBundlePolicy policy = new CatalogLocaleBundlePolicy(fixture());
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new NullFallback(), getClass().getClassLoader(), policy,
                "i18n.test.catalog_messages");
        // 资源：root（zh 值）+ _en；_ja 不存在 → ja 请求先落 en
        assertThat(resolver.get(Locale.JAPANESE, "catalog.msg")).isEqualTo("English");
        assertThat(resolver.get(Locale.US, "catalog.msg")).isEqualTo("English");
        assertThat(resolver.get(Locale.SIMPLIFIED_CHINESE, "catalog.msg")).isEqualTo("中文");
    }

    private static final class NullFallback implements MessageResolver {
        @Override
        public String get(String code, Object... args) {
            return code;
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return code;
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return defaultMessage;
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return defaultMessage;
        }

        @Override
        public String getForLog(String code, Object... args) {
            return code;
        }
    }
}
