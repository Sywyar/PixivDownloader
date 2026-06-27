package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PluginRepositoryRegistry} 单测：内嵌官方默认仓库（默认启用、可禁用）、旧版 {@code manifest-url} 兼容仓库、
 * 自定义仓库（启用 / 禁用 / 覆盖项 / 代理策略）、默认仓库解析优先级，以及 id 空 / 保留字 / 重复、{@code manifest-url}
 * 空的启动期 fail-fast。主开关与仓库启用正交。
 */
@DisplayName("PluginRepositoryRegistry 插件仓库列表注册中心")
class PluginRepositoryRegistryTest {

    @Test
    @DisplayName("默认配置：主开关关闭、官方仓库内嵌且默认启用、默认仓库为官方")
    void defaultsEmbedEnabledOfficial() {
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(new PluginCatalogProperties());

        assertThat(registry.featureEnabled()).as("主开关默认关闭").isFalse();
        assertThat(registry.repositories()).hasSize(1);
        PluginRepository official = registry.repositories().get(0);
        assertThat(official.repositoryId()).isEqualTo(PluginRepository.OFFICIAL_ID);
        assertThat(official.official()).isTrue();
        assertThat(official.builtIn()).isTrue();
        assertThat(official.enabled()).isTrue();
        assertThat(official.manifestUrl()).isEqualTo(PluginRepository.OFFICIAL_MANIFEST_URL);
        assertThat(official.proxyPolicy()).isEqualTo(RepositoryProxyPolicy.PROXY_TRUSTED);
        assertThat(registry.defaultRepository()).contains(official);
    }

    @Test
    @DisplayName("官方仓库可禁用：仍在列表但禁用，无其它仓库时默认仓库为空")
    void officialCanBeDisabled() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setOfficialRepositoryEnabled(false);
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        assertThat(registry.find(PluginRepository.OFFICIAL_ID)).isPresent();
        assertThat(registry.repositories().get(0).enabled()).isFalse();
        assertThat(registry.enabledRepositories()).isEmpty();
        assertThat(registry.defaultRepository()).isEmpty();
    }

    @Test
    @DisplayName("主开关与仓库启用正交：master on 时 featureEnabled=true，官方仓库照常在列")
    void masterSwitchOrthogonal() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        assertThat(registry.featureEnabled()).isTrue();
        assertThat(registry.defaultRepository()).map(PluginRepository::repositoryId)
                .contains(PluginRepository.OFFICIAL_ID);
    }

    @Test
    @DisplayName("旧版 manifest-url 折成兼容仓库 configured（启用、直连），且优先作为默认仓库")
    void legacyManifestUrlBecomesConfiguredRepository() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setManifestUrl("  https://legacy.example/m.json  ");
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        PluginRepository configured = registry.find(PluginRepository.LEGACY_CONFIGURED_ID).orElseThrow();
        assertThat(configured.enabled()).isTrue();
        assertThat(configured.official()).isFalse();
        assertThat(configured.builtIn()).isFalse();
        assertThat(configured.manifestUrl()).isEqualTo("https://legacy.example/m.json");
        assertThat(configured.proxyPolicy()).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        // 兼容仓库优先于官方作为默认仓库（保留旧行为）。
        assertThat(registry.defaultRepository()).map(PluginRepository::repositoryId)
                .contains(PluginRepository.LEGACY_CONFIGURED_ID);
    }

    @Test
    @DisplayName("自定义仓库：按配置顺序在列、可禁用、id 大小写不敏感解析、覆盖项与全局默认")
    void customRepositories() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setConnectTimeoutMs(11_000);
        props.setReadTimeoutMs(22_000);
        props.getRepositories().add(repo("Alpha", "https://a.example/m.json", true, null, 5_000, 0));
        props.getRepositories().add(repo("beta", "https://b.example/m.json", false, "proxy-trusted", 0, 0));
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        assertThat(registry.repositories()).extracting(PluginRepository::repositoryId)
                .containsExactly(PluginRepository.OFFICIAL_ID, "alpha", "beta");
        PluginRepository alpha = registry.find("ALPHA").orElseThrow();
        assertThat(alpha.enabled()).isTrue();
        assertThat(alpha.official()).isFalse();
        assertThat(alpha.connectTimeoutMs()).isEqualTo(5_000);          // 覆盖
        assertThat(alpha.readTimeoutMs()).isEqualTo(22_000);            // 回落全局默认
        assertThat(alpha.proxyPolicy()).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        PluginRepository beta = registry.find("beta").orElseThrow();
        assertThat(beta.enabled()).isFalse();
        assertThat(beta.proxyPolicy()).isEqualTo(RepositoryProxyPolicy.PROXY_TRUSTED);
        assertThat(beta.rawProxyPolicy()).isEqualTo("proxy-trusted");
    }

    @Test
    @DisplayName("默认仓库优先级：兼容仓库 → 官方 → 首个启用的自定义仓库")
    void defaultRepositoryPrecedence() {
        // 官方禁用、无 manifest-url、仅一个启用自定义 → 默认为该自定义。
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setOfficialRepositoryEnabled(false);
        props.getRepositories().add(repo("first", "https://1.example/m.json", false, null, 0, 0));
        props.getRepositories().add(repo("second", "https://2.example/m.json", true, null, 0, 0));
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        assertThat(registry.defaultRepository()).map(PluginRepository::repositoryId).contains("second");
    }

    @Test
    @DisplayName("未知代理策略：proxyPolicy 为 null（拉取时报不支持），原始串保留")
    void unknownProxyPolicyPreservedAsNull() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.getRepositories().add(repo("weird", "https://w.example/m.json", true, "socks5", 0, 0));
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);

        PluginRepository weird = registry.find("weird").orElseThrow();
        assertThat(weird.proxyPolicy()).isNull();
        assertThat(weird.rawProxyPolicy()).isEqualTo("socks5");
        assertThat(weird.isProxyPolicySupported()).isFalse();
    }

    @Test
    @DisplayName("find 未知 / 空 → 空")
    void findUnknown() {
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(new PluginCatalogProperties());
        assertThat(registry.find("ghost")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("  ")).isEmpty();
    }

    @Test
    @DisplayName("fail-fast：空 id / 保留字 id / 重复 id / 空 manifest-url")
    void failFastOnBadConfig() {
        assertThatThrownBy(() -> new PluginRepositoryRegistry(propsWith(repo("", "https://x/m.json", true, null, 0, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> new PluginRepositoryRegistry(propsWith(repo("official", "https://x/m.json", true, null, 0, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new PluginRepositoryRegistry(propsWith(repo("configured", "https://x/m.json", true, null, 0, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new PluginRepositoryRegistry(propsWith(
                repo("dup", "https://x/m.json", true, null, 0, 0),
                repo("dup", "https://y/m.json", true, null, 0, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new PluginRepositoryRegistry(propsWith(repo("blankurl", "  ", true, null, 0, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest-url");
    }

    private static PluginCatalogProperties.RepositoryConfig repo(String id, String url, boolean enabled,
                                                                 String proxyPolicy, long connect, long read) {
        PluginCatalogProperties.RepositoryConfig config = new PluginCatalogProperties.RepositoryConfig();
        config.setId(id);
        config.setManifestUrl(url);
        config.setEnabled(enabled);
        if (proxyPolicy != null) {
            config.setProxyPolicy(proxyPolicy);
        }
        config.setConnectTimeoutMs(connect);
        config.setReadTimeoutMs(read);
        return config;
    }

    private static PluginCatalogProperties propsWith(PluginCatalogProperties.RepositoryConfig... repos) {
        PluginCatalogProperties props = new PluginCatalogProperties();
        for (PluginCatalogProperties.RepositoryConfig repo : repos) {
            props.getRepositories().add(repo);
        }
        return props;
    }
}
