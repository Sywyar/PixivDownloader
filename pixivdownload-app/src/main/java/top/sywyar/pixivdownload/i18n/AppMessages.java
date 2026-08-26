package top.sywyar.pixivdownload.i18n;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AppMessages implements MessageResolver {

    private final MessageSource messageSource;
    private final LocaleCatalog catalog;

    public AppMessages(MessageSource messageSource) {
        this(messageSource, LocaleCatalog.defaultCatalog());
    }

    @Autowired
    public AppMessages(MessageSource messageSource, LocaleCatalog catalog) {
        this.messageSource = messageSource;
        this.catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
    }

    @Override
    public Locale currentLocale() {
        return catalog.resolve(LocaleContextHolder.getLocale()).toLocale();
    }

    @Override
    public Locale normalizeLocale(Locale locale) {
        return locale == null ? currentLocale() : catalog.resolve(locale).toLocale();
    }

    public String get(String code, Object... args) {
        return getOrDefault(currentLocale(), code, code, args);
    }

    public String get(Locale locale, String code, Object... args) {
        return getOrDefault(locale, code, code, args);
    }

    public String get(MessageSourceResolvable resolvable) {
        return get(LocaleContextHolder.getLocale(), resolvable);
    }

    public String get(Locale locale, MessageSourceResolvable resolvable) {
        return messageSource.getMessage(resolvable, catalog.resolve(locale).toLocale());
    }

    public String getOrDefault(String code, String defaultMessage, Object... args) {
        return getOrDefault(currentLocale(), code, defaultMessage, args);
    }

    public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
        return messageSource.getMessage(
                code,
                args,
                defaultMessage,
                normalizeLocale(locale)
        );
    }

    /**
     * 解析固定英文的日志文案，不随请求或系统 locale 漂移。
     */
    public String getForLog(String code, Object... args) {
        return getOrDefault(catalog.fallbackLocale().toLocale(), code, code, args);
    }

    public String getForLog(MessageSourceResolvable resolvable) {
        return get(catalog.fallbackLocale().toLocale(), resolvable);
    }
}
