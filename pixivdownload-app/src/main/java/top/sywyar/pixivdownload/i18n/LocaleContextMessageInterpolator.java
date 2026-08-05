package top.sywyar.pixivdownload.i18n;

import jakarta.validation.MessageInterpolator;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

public class LocaleContextMessageInterpolator implements MessageInterpolator {

    private final MessageInterpolator delegate;
    private final LocaleCatalog catalog;

    public LocaleContextMessageInterpolator(MessageInterpolator delegate) {
        this(delegate, null);
    }

    public LocaleContextMessageInterpolator(MessageInterpolator delegate, LocaleCatalog catalog) {
        this.delegate = delegate;
        this.catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
    }

    @Override
    public String interpolate(String template, Context context) {
        return delegate.interpolate(template, context, catalog.resolve(LocaleContextHolder.getLocale()).toLocale());
    }

    @Override
    public String interpolate(String template, Context context, Locale locale) {
        return delegate.interpolate(template, context, catalog.resolve(locale).toLocale());
    }
}
