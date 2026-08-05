package top.sywyar.pixivdownload.i18n;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.MessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MessageSourceResourceBundleLocator;

public final class TestI18nBeans {

    private TestI18nBeans() {
    }

    public static MessageSource messageSource() {
        return messageSource(LocaleCatalog.defaultCatalog());
    }

    public static MessageSource messageSource(LocaleCatalog catalog) {
        return new CatalogMessageSource(
                catalog,
                "i18n/messages",
                "i18n/ValidationMessages",
                "i18n/mail/messages",
                "i18n/push/messages"
        );
    }

    public static AppMessages appMessages() {
        return appMessages(messageSource());
    }

    public static AppMessages appMessages(MessageSource messageSource) {
        return appMessages(messageSource, LocaleCatalog.defaultCatalog());
    }

    public static AppMessages appMessages(MessageSource messageSource, LocaleCatalog catalog) {
        return new AppMessages(messageSource, catalog);
    }

    public static LocalValidatorFactoryBean validator(MessageSource messageSource) {
        return validator(messageSource, LocaleCatalog.defaultCatalog());
    }

    public static LocalValidatorFactoryBean validator(MessageSource messageSource, LocaleCatalog catalog) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        MessageInterpolator interpolator = new LocaleContextMessageInterpolator(
                new ResourceBundleMessageInterpolator(new MessageSourceResourceBundleLocator(messageSource)),
                catalog
        );
        validator.setMessageInterpolator(interpolator);
        validator.afterPropertiesSet();
        return validator;
    }
}
