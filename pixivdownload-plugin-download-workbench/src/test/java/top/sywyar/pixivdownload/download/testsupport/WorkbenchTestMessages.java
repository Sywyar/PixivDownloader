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
                "i18n.workbench.messages");
        return new RequestLocaleMessageResolver(delegate);
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
