package top.sywyar.pixivdownload.i18n;

import jakarta.validation.MessageInterpolator;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

public class LocaleContextMessageInterpolator implements MessageInterpolator {

    private final MessageInterpolator delegate;

    public LocaleContextMessageInterpolator(MessageInterpolator delegate) {
        this.delegate = delegate;
    }

    @Override
    public String interpolate(String template, Context context) {
        return delegate.interpolate(template, context, AppLocale.normalize(LocaleContextHolder.getLocale()));
    }

    @Override
    public String interpolate(String template, Context context, Locale locale) {
        return delegate.interpolate(template, context, AppLocale.normalize(locale));
    }
}
