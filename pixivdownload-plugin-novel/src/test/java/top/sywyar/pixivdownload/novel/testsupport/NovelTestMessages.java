package top.sywyar.pixivdownload.novel.testsupport;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class NovelTestMessages {

    private NovelTestMessages() {
    }

    private static MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:i18n/novel/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(true);
        return messageSource;
    }

    public static MessageResolver messageResolver() {
        return messageResolver(messageSource());
    }

    public static MessageResolver messageResolver(MessageSource messageSource) {
        return new MessageSourceResolver(messageSource);
    }

    private record MessageSourceResolver(MessageSource messageSource) implements MessageResolver {

        private static final List<Locale> SUPPORTED_LOCALES = List.of(
                Locale.US,
                Locale.SIMPLIFIED_CHINESE
        );

        @Override
        public String get(String code, Object... args) {
            return getOrDefault(LocaleContextHolder.getLocale(), code, code, args);
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return getOrDefault(locale, code, code, args);
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return getOrDefault(LocaleContextHolder.getLocale(), code, defaultMessage, args);
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return messageSource.getMessage(code, args, defaultMessage, normalize(locale));
        }

        @Override
        public String getForLog(String code, Object... args) {
            return getOrDefault(Locale.getDefault(), code, code, args);
        }

        private static Locale normalize(Locale locale) {
            if (locale != null && !locale.getLanguage().isBlank()) {
                for (Locale supportedLocale : SUPPORTED_LOCALES) {
                    if (supportedLocale.toLanguageTag().equalsIgnoreCase(locale.toLanguageTag())) {
                        return supportedLocale;
                    }
                }
                for (Locale supportedLocale : SUPPORTED_LOCALES) {
                    if (supportedLocale.getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                        return supportedLocale;
                    }
                }
            }
            return Locale.US;
        }
    }
}
