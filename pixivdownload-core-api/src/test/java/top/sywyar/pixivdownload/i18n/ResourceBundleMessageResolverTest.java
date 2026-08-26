package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("资源包消息解析器（策略驱动）")
class ResourceBundleMessageResolverTest {

    private static final List<String> BASE_NAMES = List.of("i18n.test.messages");

    @Test
    @DisplayName("旧构造器走 legacy 策略：root 语言精确命中、回退语言 fallback 优先")
    void legacyConstructorKeepsOldContract() {
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.SIMPLIFIED_CHINESE), getClass().getClassLoader(),
                "i18n.test.messages");

        // zh（root 语言）→ 直接命中 root 文件
        assertThat(resolver.get(Locale.SIMPLIFIED_CHINESE, "owner.message")).isEqualTo("中文");
        // ja（非 root / fallback 语言）→ _en 回退
        assertThat(resolver.get(Locale.JAPANESE, "owner.message")).isEqualTo("English");
        // en → _en 精确
        assertThat(resolver.get(Locale.US, "owner.message")).isEqualTo("English");
    }

    @Test
    @DisplayName("legacy：source suffix 为空、fallback suffix 为 en；目标后缀与语言码一致")
    void legacySuffixChainHasEmptySourceAndEnFallback() {
        LocaleBundlePolicy legacy = LegacyLocaleBundlePolicy.INSTANCE;
        // zh → 只有 root（不插入英文回退）；带国家时先语言_国家
        assertThat(legacy.resourceSuffixChain(Locale.SIMPLIFIED_CHINESE))
                .containsExactly("zh_CN", "zh", "");
        // en → 语言级 + root
        assertThat(legacy.resourceSuffixChain(Locale.US)).containsExactly("en_US", "en", "");
        // ja-JP → ja_JP / ja / en / root；纯语言 ja → ja / en / root
        assertThat(legacy.resourceSuffixChain(Locale.forLanguageTag("ja-JP")))
                .containsExactly("ja_JP", "ja", "en", "");
        assertThat(legacy.resourceSuffixChain(Locale.JAPANESE))
                .containsExactly("ja", "en", "");
    }

    @Test
    @DisplayName("自定义策略：目标不存在时先 fallback、fallback 不存在时 source")
    void customPolicyFallsBackToFallbackThenSource() {
        // 链固定为 es → en → 空（root）
        LocaleBundlePolicy policy = new FixedPolicy(List.of("es", "en", ""));
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.US), getClass().getClassLoader(), policy,
                "i18n.test.messages");

        // 任何请求都按 es（缺失）→ en（命中）→ root
        assertThat(resolver.get(Locale.JAPANESE, "owner.message")).isEqualTo("English");
    }

    @Test
    @DisplayName("自定义策略：root 缺失时回退 source；目标 suffix 与语言 code 不同")
    void customPolicyTargetSuffixDiffersFromLanguageCode() {
        // 目标 suffix 为自定义码（与语言 code 无关）：fixture 链 xx → en → 空
        LocaleBundlePolicy policy = new FixedPolicy(List.of("xx", "en", ""));
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.US), getClass().getClassLoader(), policy,
                "i18n.test.messages");

        assertThat(resolver.get(Locale.FRENCH, "owner.message")).isEqualTo("English");

        // 链 空 → 无 en → 直接 root（source）
        LocaleBundlePolicy rootOnly = new FixedPolicy(List.of(""));
        MessageResolver rootResolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.US), getClass().getClassLoader(), rootOnly,
                "i18n.test.messages");
        assertThat(rootResolver.get(Locale.FRENCH, "owner.message")).isEqualTo("中文");
    }

    @Test
    @DisplayName("alias 请求经策略 normalize 归一化（identity 时原样传递）")
    void aliasRequestGoesThroughPolicyNormalize() {
        LocaleBundlePolicy policy = new FixedPolicy(List.of("en", ""));
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.US), getClass().getClassLoader(), policy,
                "i18n.test.messages");
        // normalize 为 identity：传入的 Locale 原样用于 suffix 链（链固定，不受影响）
        assertThat(resolver.get(Locale.US, "owner.message")).isEqualTo("English");
    }

    @Test
    @DisplayName("未命中回退到宿主 resolver（共享 key）")
    void fallsBackToHostResolverForSharedKeys() {
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.SIMPLIFIED_CHINESE),
                getClass().getClassLoader(), "i18n.test.messages");

        assertThat(resolver.get("shared.message", "arg")).isEqualTo("host:shared.message:arg");
    }

    @Test
    @DisplayName("日志始终使用英文资源包")
    void logMessagesAlwaysUseEnglishBundle() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            MessageResolver resolver = ResourceBundleMessageResolver.of(
                    new FixedLocaleFallback(Locale.SIMPLIFIED_CHINESE),
                    getClass().getClassLoader(), "i18n.test.messages");

            assertThat(resolver.getForLog("owner.message")).isEqualTo("English");
        } finally {
            Locale.setDefault(original);
        }
    }

    /** 固定 suffix 链的最小策略 fixture。 */
    private record FixedPolicy(List<String> suffixes) implements LocaleBundlePolicy {

        @Override
        public Locale normalize(Locale requested) {
            return requested == null ? Locale.getDefault() : requested;
        }

        @Override
        public List<String> resourceSuffixChain(Locale requested) {
            return suffixes;
        }
    }

    private record FixedLocaleFallback(Locale currentLocale) implements MessageResolver {

        @Override
        public Locale normalizeLocale(Locale locale) {
            if (locale != null && Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
                return Locale.SIMPLIFIED_CHINESE;
            }
            return Locale.US;
        }

        @Override
        public String get(String code, Object... args) {
            return getOrDefault(currentLocale, code, code, args);
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return getOrDefault(locale, code, code, args);
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return getOrDefault(currentLocale, code, defaultMessage, args);
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            String suffix = args == null || args.length == 0 ? "" : ":" + args[0];
            return "host:" + code + suffix;
        }

        @Override
        public String getForLog(String code, Object... args) {
            return getOrDefault(Locale.getDefault(), code, code, args);
        }
    }
}
