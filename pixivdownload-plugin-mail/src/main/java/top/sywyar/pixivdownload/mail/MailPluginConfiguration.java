package top.sywyar.pixivdownload.mail;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.mail.controller.MailTestController;
import top.sywyar.pixivdownload.mail.preset.MailPresetRegistry;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;
import top.sywyar.pixivdownload.notification.MailNotificationSink;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.util.function.Supplier;

@Configuration
public class MailPluginConfiguration {

    @Bean
    public MailPlugin mailPlugin() {
        return new MailPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailConfig mailConfig(Environment environment) {
        return bind(environment, "mail", MailConfig::new, MailConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MessageResolver mailPluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(messages, MailPlugin.class.getClassLoader(),
                "i18n.mail.messages", "i18n.web.mail");
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailPresetRegistry mailPresetRegistry() {
        return new MailPresetRegistry();
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailTemplateRegistry mailTemplateRegistry(@Qualifier("mailPluginMessages") MessageResolver messages) {
        return new MailTemplateRegistry(messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailService mailService(MailConfig mailConfig,
                                   @Qualifier("mailPluginMessages") MessageResolver messages) {
        return new MailService(mailConfig, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailNotificationSink mailNotificationSink(MailTemplateRegistry templateRegistry,
                                                     MailService mailService) {
        return new MailNotificationSink(templateRegistry, mailService);
    }

    @Bean
    @ConditionalOnPluginEnabled(MailPlugin.ID)
    public MailTestController mailTestController(MailService mailService,
                                                 MailTemplateRegistry templateRegistry,
                                                 @Qualifier("mailPluginMessages") MessageResolver messages,
                                                 UserDisplayNameProvider displayNameProvider) {
        return new MailTestController(mailService, templateRegistry, messages, displayNameProvider);
    }

    private static <T> T bind(Environment environment, String prefix, Supplier<T> fallback, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }
}
