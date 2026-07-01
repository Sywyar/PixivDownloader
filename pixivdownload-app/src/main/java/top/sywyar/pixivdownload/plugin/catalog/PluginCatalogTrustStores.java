package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStore;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 从插件 catalog 仓库配置解析供应链验签 trust store。
 */
public final class PluginCatalogTrustStores {

    private PluginCatalogTrustStores() {
    }

    /**
     * 兼容旧调用点的默认 trust store：只包含内置官方 root。自定义仓库必须经 {@link #forRepository(PluginRepository)}
     * 或 {@link #verifierResolver(PluginRepositoryRegistry)} 按来源解析，不能从这里继承其它仓库 key。
     */
    public static PluginTrustStore fromRegistry(PluginRepositoryRegistry registry) {
        return PluginTrustStores.builtInOfficial();
    }

    public static PluginTrustStore forRepository(PluginRepository repository) {
        if (repository != null && repository.official()) {
            return PluginTrustStores.builtInOfficial();
        }
        return PluginTrustStores.of(repository != null ? repository.trustedKeys() : List.of());
    }

    public static PluginSupplyChainVerifier verifierForRepository(PluginRepository repository) {
        return new PluginSupplyChainVerifier(forRepository(repository));
    }

    public static Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver(
            PluginRepositoryRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.repositories().forEach(PluginCatalogTrustStores::forRepository);
        return origin -> new PluginSupplyChainVerifier(forOrigin(registry, origin));
    }

    private static PluginTrustStore forOrigin(PluginRepositoryRegistry registry, PluginPackageOrigin origin) {
        if (origin == null || origin.source() == PluginPackageSource.LOCAL_UPLOAD) {
            return PluginTrustStores.builtInOfficial();
        }
        if (origin.officialRepository()) {
            return PluginTrustStores.builtInOfficial();
        }
        return registry.find(origin.repositoryId())
                .filter(repository -> !repository.official())
                .map(PluginCatalogTrustStores::forRepository)
                .orElseGet(() -> PluginTrustStores.of(List.of()));
    }
}
