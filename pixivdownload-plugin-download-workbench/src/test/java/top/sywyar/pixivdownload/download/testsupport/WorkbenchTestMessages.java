package top.sywyar.pixivdownload.download.testsupport;

import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

import java.util.Locale;

public final class WorkbenchTestMessages {

    private WorkbenchTestMessages() {
    }

    public static MessageResolver messages() {
        MessageResolver delegate = ResourceBundleMessageResolver.of(
                null,
                WorkbenchTestMessages.class.getClassLoader(),
                new FixedLegacyPolicy(),
                "i18n.workbench.messages",
                "i18n.workbench.notification-templates");
        return new RequestLocaleMessageResolver(delegate);
    }

    /** 测试夹具：等价于旧 root=zh-CN + _en=en-US 约定的最小策略。 */
    private static final class FixedLegacyPolicy implements top.sywyar.pixivdownload.i18n.LocaleBundlePolicy {

        @Override
        public Locale normalize(Locale requested) {
            return requested == null ? Locale.getDefault() : requested;
        }

        @Override
        public java.util.List<String> resourceSuffixChain(Locale requested) {
            if (requested != null && "zh".equalsIgnoreCase(requested.getLanguage())) {
                return java.util.List.of("");
            }
            if (requested != null && !requested.getLanguage().isBlank()
                    && !"en".equalsIgnoreCase(requested.getLanguage())) {
                return java.util.List.of(requested.getLanguage(), "en", "");
            }
            return java.util.List.of("en", "");
        }
    }

    private record RequestLocaleMessageResolver(MessageResolver delegate) implements MessageResolver {

        @Override
        public Locale currentLocale() {
            return normalizeLocale(LocaleContextHolder.getLocale());
        }

        @Override
        public Locale normalizeLocale(Locale locale) {
            return locale != null && Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())
                    ? Locale.SIMPLIFIED_CHINESE
                    : Locale.US;
        }

        @Override
        public String get(String code, Object... args) {
            return get(currentLocale(), code, args);
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return delegate.get(normalizeLocale(locale), code, args);
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return getOrDefault(currentLocale(), code, defaultMessage, args);
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return delegate.getOrDefault(normalizeLocale(locale), code, defaultMessage, args);
        }

        @Override
        public String getForLog(String code, Object... args) {
            return getOrDefault(Locale.getDefault(), code, code, args);
        }
    }
}
