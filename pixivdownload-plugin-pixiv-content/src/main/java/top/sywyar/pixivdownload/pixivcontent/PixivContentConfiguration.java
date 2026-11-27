package top.sywyar.pixivdownload.pixivcontent;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.http.*;

@Configuration(proxyBeanMethods = false)
public class PixivContentConfiguration {
    @Bean(destroyMethod = "close")
    OutboundHttpClient pixivContentHttp(OutboundHttpClientFactory factory) {
        return factory.open(OutboundHttpClientProfile.credentialed(
                Duration.ofSeconds(10), Duration.ofSeconds(30), OutboundHttpRoute.inherit()));
    }

    @Bean
    PixivContentController pixivContentController(PixivAjaxClient ajax, ObjectMapper json,
            @Qualifier("pixivContentHttp") OutboundHttpClient http, WorkVisibilityService visibility,
            MessageResolver messages, LocaleBundlePolicy locales) {
        var remote = new ContentTransport(http);
        var queries = new ContentQueries(ajax, remote::ranking, json);
        return new PixivContentController(queries, remote, visibility, ResourceBundleMessageResolver.of(
                messages, getClass().getClassLoader(), locales, "i18n/web/pixiv-content"));
    }
}
