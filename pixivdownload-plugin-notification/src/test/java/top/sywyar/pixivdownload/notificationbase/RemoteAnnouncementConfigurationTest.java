package top.sywyar.pixivdownload.notificationbase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("远程公告资源所有权")
class RemoteAnnouncementConfigurationTest extends RemoteAnnouncementImporterTestSupport {

    @Test
    @DisplayName("插件配置使用固定超时、禁止重定向和 Cookie 的单连接客户端")
    void configurationOwnsStrictHttpProfile() throws Exception {
        AtomicReference<OutboundHttpClientProfile> captured = new AtomicReference<>();
        StubClient client = new StubClient(SigningFixture.create());
        OutboundHttpClientFactory factory = profile -> {
            captured.set(profile);
            return client;
        };

        OutboundHttpClient opened = new NotificationPluginConfiguration()
                .notificationAnnouncementHttpClient(factory);

        assertThat(opened).isSameAs(client);
        assertThat(captured.get()).satisfies(profile -> {
            assertThat(profile.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(profile.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(profile.route()).isEqualTo(OutboundHttpRoute.inherit());
            assertThat(profile.redirectPolicy()).isEqualTo(OutboundHttpRedirectPolicy.NEVER);
            assertThat(profile.cookiePolicy()).isEqualTo(OutboundHttpCookiePolicy.DISABLED);
            assertThat(profile.maxConnections()).isEqualTo(1);
            assertThat(profile.maxConnectionsPerRoute()).isEqualTo(1);
        });
        Scheduled scheduled = RemoteAnnouncementImporter.class
                .getMethod("tick")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelay()).isZero();
        assertThat(scheduled.fixedDelay()).isEqualTo(RemoteAnnouncementImporter.POLL_TICK_MILLIS);
        assertThat(scheduled.scheduler()).isEqualTo("notificationAnnouncementTaskScheduler");
        opened.close();
        assertThat(client.closed).isTrue();
    }

    @Test
    @DisplayName("子上下文关闭时停止公告调度并关闭 HTTP 客户端")
    void childContextCloseStopsPollingAndClosesClient() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(LifecycleConfiguration.class);
        context.refresh();
        StubClient client = context.getBean(StubClient.class);
        ThreadPoolTaskScheduler scheduler = context.getBean(
                "notificationAnnouncementTaskScheduler", ThreadPoolTaskScheduler.class);

        try {
            assertThat(client.requested.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.contentRequested.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.requests).hasValue(4);
        } finally {
            context.close();
        }

        assertThat(client.closed).isTrue();
        assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }
}
