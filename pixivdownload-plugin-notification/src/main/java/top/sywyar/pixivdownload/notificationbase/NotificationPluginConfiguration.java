package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotCatalog;

import java.time.Duration;
@Configuration
@EnableScheduling
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
    public NotificationInboxService notificationInboxService(NotificationInboxMapper mapper,
                                                              Environment environment,
                                                              WebUiSlotCatalog uiSlots,
                                                              NamespaceMessageResolver messages,
                                                              LocaleBundlePolicy localePolicy) {
        return new NotificationInboxService(
                mapper,
                () -> environment.getProperty(
                        NotificationPlugin.INBOX_MAX_MESSAGES_KEY,
                        Integer.class,
                        NotificationPlugin.DEFAULT_INBOX_MAX_MESSAGES),
                () -> environment.getProperty(
                        NotificationPlugin.INBOX_RETENTION_DAYS_KEY,
                        Integer.class,
                        NotificationPlugin.DEFAULT_INBOX_RETENTION_DAYS),
                uiSlots,
                messages,
                localePolicy);
    }

    @Bean
    @Order(110)
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public MaintenanceTask notificationInboxRetentionTask(NotificationInboxService inbox) {
        return new NotificationInboxRetentionTask(inbox);
    }

    @Bean(name = "notificationAnnouncementTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public ThreadPoolTaskScheduler notificationAnnouncementTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-announcement-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(name = "notificationAnnouncementHttpClient", destroyMethod = "close")
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public OutboundHttpClient notificationAnnouncementHttpClient(OutboundHttpClientFactory factory) {
        return factory.open(new OutboundHttpClientProfile(
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                OutboundHttpRoute.inherit(),
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED,
                1,
                1));
    }

    @Bean
    @ConditionalOnPluginEnabled(NotificationPlugin.ID)
    public RemoteAnnouncementImporter remoteAnnouncementImporter(
            @Qualifier("notificationAnnouncementHttpClient") OutboundHttpClient client,
            ObjectMapper objectMapper,
            NotificationInboxService inbox) {
        return new RemoteAnnouncementImporter(client, objectMapper, inbox);
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
