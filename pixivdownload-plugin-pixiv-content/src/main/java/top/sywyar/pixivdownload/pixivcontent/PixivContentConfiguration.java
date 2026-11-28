package top.sywyar.pixivdownload.pixivcontent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

/** Pixiv 请求全部经宿主 JSON 与媒体端口发出，插件不自建出站 HTTP 客户端。 */
@Configuration(proxyBeanMethods = false)
public class PixivContentConfiguration {
    @Bean
    PixivContentController pixivContentController(PixivAjaxClient ajax, PixivMediaClient media, ObjectMapper json,
            WorkVisibilityService visibility, MessageResolver messages, LocaleBundlePolicy locales) {
        var queries = new ContentQueries(ajax, json);
        return new PixivContentController(queries, new ContentTransport(media), visibility, ResourceBundleMessageResolver.of(
                messages, getClass().getClassLoader(), locales, "i18n/web/pixiv-content"));
    }
}
