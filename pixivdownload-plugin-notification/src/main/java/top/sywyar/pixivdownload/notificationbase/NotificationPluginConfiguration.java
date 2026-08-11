package top.sywyar.pixivdownload.notificationbase;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;

@Configuration
public class NotificationPluginConfiguration {

    @Bean
    public NotificationPlugin notificationPlugin() {
        return new NotificationPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public MapperFactoryBean<NotificationInboxMapper> notificationInboxMapper(
            SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<NotificationInboxMapper> factory = new MapperFactoryBean<>(NotificationInboxMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public NotificationInboxService notificationInboxService(NotificationInboxMapper mapper) {
        return new NotificationInboxService(mapper);
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public InboxNotificationSink inboxNotificationSink(NotificationTemplateCatalog templates,
                                                       NotificationInboxService inbox,
                                                       LocaleBundlePolicy localePolicy,
                                                       Environment environment) {
        return new InboxNotificationSink(
                templates,
                inbox,
                localePolicy.supportedLocales(),
                () -> environment.getProperty(NotificationPlugin.INBOX_ENABLED_KEY, Boolean.class, true));
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public NotificationInboxController notificationInboxController(NotificationInboxService inbox) {
        return new NotificationInboxController(inbox);
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public NotificationInboxTestController notificationInboxTestController(InboxNotificationSink sink) {
        return new NotificationInboxTestController(sink);
    }
}
