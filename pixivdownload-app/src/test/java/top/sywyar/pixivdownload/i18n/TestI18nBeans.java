package top.sywyar.pixivdownload.i18n;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MessageSourceResourceBundleLocator;

import java.nio.charset.StandardCharsets;

public final class TestI18nBeans {

    private TestI18nBeans() {
    }

    public static MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(
                "classpath:i18n/messages",
                "classpath:i18n/ValidationMessages",
                "classpath:i18n/mail/messages",
                "classpath:i18n/push/messages"
        );
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(true);
        return messageSource;
    }

    public static AppMessages appMessages() {
        return new AppMessages(messageSource());
    }

    public static AppMessages appMessages(MessageSource messageSource) {
        return new AppMessages(messageSource);
    }

    public static LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        MessageInterpolator interpolator = new LocaleContextMessageInterpolator(
                new ResourceBundleMessageInterpolator(new MessageSourceResourceBundleLocator(messageSource))
        );
        validator.setMessageInterpolator(interpolator);
        validator.afterPropertiesSet();
        return validator;
    }
}
