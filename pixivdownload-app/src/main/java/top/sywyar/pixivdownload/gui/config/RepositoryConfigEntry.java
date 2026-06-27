package top.sywyar.pixivdownload.gui.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GUI 编辑器持有的<b>单个自定义插件仓库</b>配置项（对应 {@code plugin-catalog.repositories[*]}）。纯数据、无 Swing，
 * 可 headless 测试。由 {@link PluginRepositoryConfigEditor} 在 {@code config.yaml} 与本模型之间结构化往返。
 *
 * <p>超时 / 大小覆盖项语义与后端 {@code PluginCatalogProperties.RepositoryConfig} 一致：值为 {@code 0} 表示
 * <b>继承全局默认值</b>（GUI 中对应「留空」）；序列化时这些 {@code 0} 项会被省略以保持 {@code config.yaml} 简洁，
 * 读回缺失即 {@code 0}（继承）——语义等价。
 *
 * <p>{@link #extraFields} 保留<b>本编辑器未暴露的仓库字段</b>（如用户手写的未来字段）：读时收集、写时原样回写，
 * 使 GUI 保存不会丢弃未知配置。
 *
 * @param id              仓库稳定 id（必填、全局唯一、大小写归一后不得重复、不得为保留字 {@code official} / {@code configured}）
 * @param displayNameKey  展示名 i18n key（可空；缺省由后端按 {@code plugin.market.repository.<id>.name} 推导）
 * @param manifestUrl     清单地址（必填、必须为合法绝对 https URL）
 * @param enabled         是否启用
 * @param proxyPolicy     代理策略配置串（固定枚举：{@code direct-strict} / {@code proxy-trusted} / {@code custom}）
 * @param allowRedirects  custom 档是否允许至多一跳重定向
 * @param strictHttps     custom 档是否只允许 HTTPS
 * @param allowNonPublicAddresses custom 档是否允许非公网地址
 * @param useProxy        custom 档是否使用应用全局代理（{@code proxy.*}）
 * @param connectTimeoutMs 连接超时覆盖（毫秒，{@code 0} = 继承全局默认）
 * @param readTimeoutMs    读取超时覆盖（毫秒，{@code 0} = 继承全局默认）
 * @param maxManifestBytes 清单字节上限覆盖（{@code 0} = 继承全局默认）
 * @param maxPackageBytes  单包字节上限覆盖（{@code 0} = 继承全局默认）
 * @param extraFields      本编辑器未暴露的仓库字段（往返保留，原样键值；不可为 null）
 */
public record RepositoryConfigEntry(
        String id,
        String displayNameKey,
        String manifestUrl,
        boolean enabled,
        String proxyPolicy,
        boolean allowRedirects,
        boolean strictHttps,
        boolean allowNonPublicAddresses,
        boolean useProxy,
        long connectTimeoutMs,
        long readTimeoutMs,
        long maxManifestBytes,
        long maxPackageBytes,
        Map<String, Object> extraFields) {

    public RepositoryConfigEntry {
        id = id == null ? "" : id;
        displayNameKey = displayNameKey == null ? "" : displayNameKey;
        manifestUrl = manifestUrl == null ? "" : manifestUrl;
        proxyPolicy = proxyPolicy == null || proxyPolicy.isBlank() ? "direct-strict" : proxyPolicy;
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    /** 不带未知字段、采用默认代理策略的新建条目（启用、所有覆盖项继承全局默认）。 */
    public static RepositoryConfigEntry create(String id, String displayNameKey, String manifestUrl, boolean enabled,
                                               String proxyPolicy, long connectTimeoutMs, long readTimeoutMs,
                                               long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, proxyPolicy,
                false, true, false, false,
                connectTimeoutMs, readTimeoutMs, maxManifestBytes, maxPackageBytes, new LinkedHashMap<>());
    }

    /** 不带未知字段的新建条目，可显式指定 custom 网络开关。 */
    public static RepositoryConfigEntry createCustom(String id, String displayNameKey, String manifestUrl,
                                                     boolean enabled, boolean allowRedirects, boolean strictHttps,
                                                     boolean allowNonPublicAddresses, boolean useProxy,
                                                     long connectTimeoutMs, long readTimeoutMs,
                                                     long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled,
                "custom", allowRedirects, strictHttps, allowNonPublicAddresses, useProxy,
                connectTimeoutMs, readTimeoutMs, maxManifestBytes, maxPackageBytes, new LinkedHashMap<>());
    }
}
