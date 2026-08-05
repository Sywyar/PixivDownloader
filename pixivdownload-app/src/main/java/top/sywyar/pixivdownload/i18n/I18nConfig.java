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
    private final LocaleCatalog localeCatalog;

    public I18nConfig(AppLocaleResolver appLocaleResolver, LocaleCatalog localeCatalog) {
        this.appLocaleResolver = appLocaleResolver;
        this.localeCatalog = localeCatalog;
    }

    @Bean
    public LocaleResolver localeResolver() {
        return appLocaleResolver;
    }

    /**
     * host 语言策略：所有 Spring 管理的第一方解析路径（插件子 context 也能经父 context
     * 注入本 bean）都从这里取策略；静态 {@code LocaleCatalog.defaultCatalog()} 只保留给
     * 非 Spring 场景（GUI 面板 / 日志解析器等）。
     */
    @Bean
    public LocaleBundlePolicy localeBundlePolicy() {
        return new CatalogLocaleBundlePolicy(localeCatalog);
    }

    @Bean
    public MessageSource messageSource() {
        return new CatalogMessageSource(
                localeCatalog,
                "i18n/messages",
                "i18n/ValidationMessages"
        );
    }

    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        MessageInterpolator interpolator = new LocaleContextMessageInterpolator(
                new ResourceBundleMessageInterpolator(new MessageSourceResourceBundleLocator(messageSource)),
                localeCatalog
        );
        validator.setMessageInterpolator(interpolator);
        return validator;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 使用注入的 catalog 实例构造拦截器（不读取静态默认目录）
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(localeCatalog.languageParameterName());
        registry.addInterceptor(interceptor);
    }
}
