package top.sywyar.pixivdownload.plugin.api.http;

/** 用于创建插件自有出站 HTTP 客户端的稳定宿主能力。 */
@FunctionalInterface
public interface OutboundHttpClientFactory {

    /**
     * 按指定配置创建一个由插件负责使用和关闭的客户端。
     *
     * @param profile 传输资源与路由配置
     * @return 新建的出站 HTTP 客户端
     */
    OutboundHttpClient open(OutboundHttpClientProfile profile);
}
