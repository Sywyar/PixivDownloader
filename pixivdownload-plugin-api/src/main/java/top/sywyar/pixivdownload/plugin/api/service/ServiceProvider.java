package top.sywyar.pixivdownload.plugin.api.service;

import java.util.Objects;

/**
 * 集合中的受管成员。服务代理固定双方发布身份，撤回后拒绝新调用。
 * @param publication 可信提供者身份
 * @param service 同步纯值代理，不是插件 Bean
 * @param <T> 服务类型
 */
public record ServiceProvider<T>(ServicePublication publication, T service) {
    /**
     * 创建成员快照。
     * @param publication 可信发布身份
     * @param service 精确发布代理
     */
    public ServiceProvider {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(service, "service");
    }
}
