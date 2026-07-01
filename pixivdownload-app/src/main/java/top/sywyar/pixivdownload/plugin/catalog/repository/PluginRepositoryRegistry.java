package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 插件仓库列表的<b>只读注册中心</b>：在启动期从服务端配置 {@link PluginCatalogProperties} + 内嵌官方默认仓库合成一份
 * <b>不可变</b>仓库列表，并提供按 {@code repositoryId} 解析、列举与默认仓库解析。<b>不联网、不持任何 HTTP 客户端</b>，纯
 * 配置→领域映射。
 *
 * <h2>合成顺序与默认解析</h2>
 * <ol>
 *   <li><b>内嵌官方仓库</b> {@code official}（{@link PluginRepository#official}）始终在列，其 {@code enabled} 由
 *       {@code plugin-catalog.official-repository-enabled}（默认 {@code true}）决定——官方仓库可禁用。</li>
 *   <li><b>兼容仓库</b> {@code configured}：当旧版单一 {@code plugin-catalog.manifest-url} 非空时折出一个启用的直连
 *       仓库（保留旧行为：未配置自定义仓库列表、只配过 {@code manifest-url} 的部署照常工作，且它优先作为默认仓库）。</li>
 *   <li><b>自定义仓库</b>：来自 {@code plugin-catalog.repositories[*]}，按配置顺序。id 不得为空 / 保留字
 *       （{@code official} / {@code configured}）/ 重复，{@code manifest-url} 不得为空，否则启动期 fail-fast。</li>
 * </ol>
 *
 * <p>{@link #defaultRepository()}（供旧版无参 catalog 入口）优先级：兼容仓库 → 官方仓库 → 首个启用的自定义仓库（按配置
 * 顺序），跳过禁用项；都不可用则空——这使「未配置用户仓库时只访问内嵌官方仓库」成立，同时保留旧 {@code manifest-url} 行为。
 *
 * <p><b>主开关</b> {@code plugin-catalog.enabled}（默认 {@code false}）与仓库 {@code enabled} 正交：主开关关 → 整个受信
 * catalog / 市场能力不可用（{@link #featureEnabled()} 为 {@code false}），即便官方仓库默认启用也不会联网；管理员显式开启
 * 主开关后，默认仓库（官方）方才可用。
 */
@Component
public class PluginRepositoryRegistry {

    private final boolean featureEnabled;
    private final List<PluginRepository> repositories;
    private final Map<String, PluginRepository> byId;

    public PluginRepositoryRegistry(PluginCatalogProperties properties) {
        this.featureEnabled = properties.isEnabled();
        this.repositories = List.copyOf(build(properties));
        Map<String, PluginRepository> index = new LinkedHashMap<>();
        for (PluginRepository repository : repositories) {
            index.put(repository.repositoryId(), repository);
        }
        this.byId = Map.copyOf(index);
    }

    private static List<PluginRepository> build(PluginCatalogProperties properties) {
        long connectTimeout = properties.getConnectTimeoutMs();
        long readTimeout = properties.getReadTimeoutMs();
        long maxManifest = properties.getMaxManifestBytes();
        long maxPackage = properties.getMaxPackageBytes();

        List<PluginRepository> result = new ArrayList<>();
        // 1) 内嵌官方默认仓库——始终在列，enabled 由配置决定（可禁用）。
        result.add(PluginRepository.official(properties.isOfficialRepositoryEnabled(),
                connectTimeout, readTimeout, maxManifest, maxPackage));

        // 2) 旧版单一 manifest-url 的兼容仓库（仅当非空）。
        if (properties.hasManifestUrl()) {
            result.add(new PluginRepository(PluginRepository.LEGACY_CONFIGURED_ID,
                    PluginRepository.LEGACY_DISPLAY_NAME_KEY, properties.getManifestUrl().trim(),
                    true, false, false, RepositoryProxyPolicy.DIRECT_STRICT,
                    RepositoryProxyPolicy.DIRECT_STRICT.configId(),
                    false, true, false, false,
                    connectTimeout, readTimeout, maxManifest, maxPackage, trustedKeys(properties.getTrustedKeys())));
        }

        // 3) 自定义仓库列表（按配置顺序；id / manifest-url 校验，重复 / 保留字 fail-fast）。
        Map<String, Boolean> seen = new LinkedHashMap<>();
        seen.put(PluginRepository.OFFICIAL_ID, Boolean.TRUE);
        if (properties.hasManifestUrl()) {
            seen.put(PluginRepository.LEGACY_CONFIGURED_ID, Boolean.TRUE);
        }
        for (PluginCatalogProperties.RepositoryConfig config : properties.getRepositories()) {
            result.add(toRepository(config, seen, connectTimeout, readTimeout, maxManifest, maxPackage));
        }
        return result;
    }

    private static PluginRepository toRepository(PluginCatalogProperties.RepositoryConfig config,
                                                 Map<String, Boolean> seen,
                                                 long defaultConnect, long defaultRead,
                                                 long defaultManifest, long defaultPackage) {
        String id = config.getId() == null ? "" : config.getId().trim();
        if (id.isBlank()) {
            throw new IllegalStateException("plugin-catalog.repositories[*].id must not be blank");
        }
        String normalizedId = id.toLowerCase(Locale.ROOT);
        if (PluginRepository.OFFICIAL_ID.equals(normalizedId) || PluginRepository.LEGACY_CONFIGURED_ID.equals(normalizedId)) {
            throw new IllegalStateException("plugin-catalog.repositories[*].id '" + id
                    + "' is reserved for a built-in repository");
        }
        if (seen.putIfAbsent(normalizedId, Boolean.TRUE) != null) {
            throw new IllegalStateException("duplicate plugin-catalog repository id: " + id);
        }
        String manifestUrl = config.getManifestUrl() == null ? "" : config.getManifestUrl().trim();
        if (manifestUrl.isBlank()) {
            throw new IllegalStateException("plugin-catalog.repositories[" + id + "].manifest-url must not be blank");
        }
        String rawPolicy = config.getProxyPolicy();
        RepositoryProxyPolicy policy = RepositoryProxyPolicy.fromConfig(rawPolicy);
        return new PluginRepository(normalizedId, displayNameKey(config, normalizedId), manifestUrl,
                config.isEnabled(), false, false, policy,
                rawPolicy == null || rawPolicy.isBlank() ? RepositoryProxyPolicy.DEFAULT.configId() : rawPolicy.trim(),
                config.isAllowRedirects(), config.isStrictHttps(),
                config.isAllowNonPublicAddresses(), config.isUseProxy(),
                positiveOr(config.getConnectTimeoutMs(), defaultConnect),
                positiveOr(config.getReadTimeoutMs(), defaultRead),
                positiveOr(config.getMaxManifestBytes(), defaultManifest),
                positiveOr(config.getMaxPackageBytes(), defaultPackage),
                trustedKeys(config.getTrustedKeys()));
    }

    private static String displayNameKey(PluginCatalogProperties.RepositoryConfig config, String id) {
        String key = config.getDisplayNameKey();
        return key != null && !key.isBlank() ? key.trim() : "plugin.market.repository." + id + ".name";
    }

    /** 每仓库覆盖值为正时采用，否则回落到全局默认。 */
    private static long positiveOr(long override, long fallback) {
        return override > 0 ? override : fallback;
    }

    private static List<TrustedPluginKey> trustedKeys(List<PluginCatalogProperties.TrustedKeyConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        List<TrustedPluginKey> result = new ArrayList<>();
        for (PluginCatalogProperties.TrustedKeyConfig config : configs) {
            String keyId = text(config.getKeyId());
            String publicKey = text(config.getPublicKey());
            if (keyId == null || publicKey == null) {
                throw new IllegalStateException("plugin-catalog trusted key requires key-id and public-key");
            }
            result.add(new TrustedPluginKey(
                    keyId,
                    text(config.getAlgorithm()) != null ? text(config.getAlgorithm()) : SignatureMetadata.ED25519,
                    publicKey,
                    parseKeyState(config.getState()),
                    text(config.getPublisher()),
                    text(config.getTrustLabel()),
                    false));
        }
        return List.copyOf(result);
    }

    private static TrustedPluginKey.State parseKeyState(String value) {
        if (value == null || value.isBlank()) {
            return TrustedPluginKey.State.ACTIVE;
        }
        try {
            return TrustedPluginKey.State.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsupported plugin-catalog trusted key state: " + value, e);
        }
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 受信 catalog / 插件市场主开关是否开启（{@code plugin-catalog.enabled}）。 */
    public boolean featureEnabled() {
        return featureEnabled;
    }

    /** 全部已配置仓库（含禁用项；官方在首、兼容仓库次之、自定义按配置顺序），不可变。 */
    public List<PluginRepository> repositories() {
        return repositories;
    }

    /** 全部已启用仓库（保序）。 */
    public List<PluginRepository> enabledRepositories() {
        return repositories.stream().filter(PluginRepository::enabled).toList();
    }

    /** 按 {@code repositoryId} 精确解析（大小写不敏感）；未知 id → 空。 */
    public Optional<PluginRepository> find(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(repositoryId.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 默认仓库（旧版无参 catalog 入口使用）：兼容仓库（旧 {@code manifest-url}）→ 官方仓库 → 首个启用的自定义仓库，
     * 跳过禁用项；都不可用则空。
     */
    public Optional<PluginRepository> defaultRepository() {
        return find(PluginRepository.LEGACY_CONFIGURED_ID).filter(PluginRepository::enabled)
                .or(() -> find(PluginRepository.OFFICIAL_ID).filter(PluginRepository::enabled))
                .or(() -> enabledRepositories().stream().findFirst());
    }
}
