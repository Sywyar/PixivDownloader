package top.sywyar.pixivdownload.i18n;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MessageSourceResourceBundleLocator;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

@Configuration
public class I18nConfig implements WebMvcConfigurer {

    private final AppLocaleResolver appLocaleResolver;

    public I18nConfig(AppLocaleResolver appLocaleResolver) {
        this.appLocaleResolver = appLocaleResolver;
    }

    @Bean
    public LocaleResolver localeResolver() {
        return appLocaleResolver;
    }

    @Bean
    public MessageSource messageSource(LocaleCatalog localeCatalog) {
        return new CatalogMessageSource(
                localeCatalog,
                "i18n/messages",
                "i18n/ValidationMessages"
        );
    }

    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource, LocaleCatalog localeCatalog) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        MessageInterpolator interpolator = new LocaleContextMessageInterpolator(
                new ResourceBundleMessageInterpolator(new MessageSourceResourceBundleLocator(messageSource)),
                localeCatalog
        );
        validator.setMessageInterpolator(interpolator);
        return validator;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LocaleCatalog.defaultCatalog().languageParameterName());
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
