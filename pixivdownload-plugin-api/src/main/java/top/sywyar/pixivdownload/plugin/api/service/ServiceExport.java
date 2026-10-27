package top.sywyar.pixivdownload.plugin.api.service;

import java.util.Objects;

/**
 * 显式导出本插件子 context 中的具名 Bean；宿主不会自动暴露其它 Bean。
 *
 * @param key 服务契约
 * @param beanName 本插件的本地 Bean 名称
 * @param <T> 服务类型
 */
public record ServiceExport<T>(ServiceKey<T> key, String beanName) {
    /**
     * 创建具名导出。
     * @param key 服务键
     * @param beanName 非空且至多 256 字符的本地 Bean 名称
     */
    public ServiceExport {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(beanName, "beanName");
        if (beanName.isBlank() || beanName.length() > 256) {
            throw new IllegalArgumentException("SERVICE_EXPORT_INVALID");
        }
    }
}
