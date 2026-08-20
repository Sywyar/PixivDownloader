package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单个自定义插件仓库的工具包无关配置。 */
public record RepositoryConfigEntry(
        String id, String displayNameKey, String manifestUrl, boolean enabled, String proxyPolicy,
        boolean allowRedirects, boolean strictHttps, boolean allowNonPublicAddresses, boolean useProxy,
        long connectTimeoutMs, long readTimeoutMs, long maxManifestBytes, long maxPackageBytes,
        List<TrustedKeyConfigEntry> trustedKeys, Map<String, Object> extraFields) {

    /**
     * 校验并防御性复制一项仓库配置。
     *
     * @param id 稳定仓库 id
     * @param displayNameKey 本地化展示名 key
     * @param manifestUrl 仓库清单 URL
     * @param enabled 是否启用仓库
     * @param proxyPolicy 持久化的代理策略 id
     * @param allowRedirects 是否允许重定向
     * @param strictHttps 是否要求 HTTPS
     * @param allowNonPublicAddresses 是否允许非公网地址
     * @param useProxy 是否使用已配置代理
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs 读取超时毫秒数
     * @param maxManifestBytes 清单最大字节数
     * @param maxPackageBytes 插件包最大字节数
     * @param trustedKeys 仓库信任根
     * @param extraFields 往返时保留的未知字段
     */
    public RepositoryConfigEntry {
        id = id == null ? "" : id;
        displayNameKey = displayNameKey == null ? "" : displayNameKey;
        manifestUrl = manifestUrl == null ? "" : manifestUrl;
        proxyPolicy = proxyPolicy == null || proxyPolicy.isBlank() ? "direct-strict" : proxyPolicy;
        trustedKeys = trustedKeys == null ? List.of() : List.copyOf(trustedKeys);
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    /**
     * 创建不含显式信任根的仓库配置。
     *
     * @param id 稳定仓库 id
     * @param displayNameKey 本地化展示名 key
     * @param manifestUrl 仓库清单 URL
     * @param enabled 是否启用仓库
     * @param proxyPolicy 持久化的代理策略 id
     * @param allowRedirects 是否允许重定向
     * @param strictHttps 是否要求 HTTPS
     * @param allowNonPublicAddresses 是否允许非公网地址
     * @param useProxy 是否使用已配置代理
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs 读取超时毫秒数
     * @param maxManifestBytes 清单最大字节数
     * @param maxPackageBytes 插件包最大字节数
     * @param extraFields 往返时保留的未知字段
     */
    public RepositoryConfigEntry(String id, String displayNameKey, String manifestUrl, boolean enabled,
                                 String proxyPolicy, boolean allowRedirects, boolean strictHttps,
                                 boolean allowNonPublicAddresses, boolean useProxy, long connectTimeoutMs,
                                 long readTimeoutMs, long maxManifestBytes, long maxPackageBytes,
                                 Map<String, Object> extraFields) {
        this(id, displayNameKey, manifestUrl, enabled, proxyPolicy, allowRedirects, strictHttps,
                allowNonPublicAddresses, useProxy, connectTimeoutMs, readTimeoutMs, maxManifestBytes,
                maxPackageBytes, List.of(), extraFields);
    }

    /**
     * 创建带标准网络策略字段的仓库配置。
     *
     * @param id 稳定仓库 id
     * @param displayNameKey 本地化展示名 key
     * @param manifestUrl 仓库清单 URL
     * @param enabled 是否启用仓库
     * @param proxyPolicy 持久化的代理策略 id
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs 读取超时毫秒数
     * @param maxManifestBytes 清单最大字节数
     * @param maxPackageBytes 插件包最大字节数
     * @return 仓库配置
     */
    public static RepositoryConfigEntry create(String id, String displayNameKey, String manifestUrl, boolean enabled,
                                               String proxyPolicy, long connectTimeoutMs, long readTimeoutMs,
                                               long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, proxyPolicy,
                false, true, false, false, connectTimeoutMs, readTimeoutMs, maxManifestBytes, maxPackageBytes,
                List.of(), new LinkedHashMap<>());
    }

    /**
     * 创建带显式自定义网络策略字段的仓库配置。
     *
     * @param id 稳定仓库 id
     * @param displayNameKey 本地化展示名 key
     * @param manifestUrl 仓库清单 URL
     * @param enabled 是否启用仓库
     * @param allowRedirects 是否允许重定向
     * @param strictHttps 是否要求 HTTPS
     * @param allowNonPublicAddresses 是否允许非公网地址
     * @param useProxy 是否使用已配置代理
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs 读取超时毫秒数
     * @param maxManifestBytes 清单最大字节数
     * @param maxPackageBytes 插件包最大字节数
     * @return 仓库配置
     */
    public static RepositoryConfigEntry createCustom(String id, String displayNameKey, String manifestUrl,
                                                     boolean enabled, boolean allowRedirects, boolean strictHttps,
                                                     boolean allowNonPublicAddresses, boolean useProxy,
                                                     long connectTimeoutMs, long readTimeoutMs,
                                                     long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, "custom", allowRedirects,
                strictHttps, allowNonPublicAddresses, useProxy, connectTimeoutMs, readTimeoutMs,
                maxManifestBytes, maxPackageBytes, List.of(), new LinkedHashMap<>());
    }
}
