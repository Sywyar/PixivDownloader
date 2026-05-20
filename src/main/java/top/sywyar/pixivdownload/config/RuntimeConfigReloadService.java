package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.setup.SetupProperties;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigReloadService {

    private final DownloadConfig downloadConfig;
    private final MultiModeConfig multiModeConfig;
    private final SetupProperties setupProperties;
    private final SslConfig sslConfig;
    private final MaintenanceProperties maintenanceProperties;
    private final ProxyConfig proxyConfig;
    private final UpdateConfig updateConfig;

    public synchronized ReloadResult reloadHotConfig() throws IOException {
        Binder binder = loadBinder();
        DownloadConfig nextDownload = bind(binder, "download", DownloadConfig::new, DownloadConfig.class);
        MultiModeConfig nextMultiMode = bind(binder, "multi-mode", MultiModeConfig::new, MultiModeConfig.class);
        SetupProperties nextSetup = bind(binder, "setup", SetupProperties::new, SetupProperties.class);
        SslConfig nextSsl = bind(binder, "ssl", SslConfig::new, SslConfig.class);
        MaintenanceProperties nextMaintenance = bind(binder, "maintenance", MaintenanceProperties::new, MaintenanceProperties.class);
        ProxyConfig nextProxy = bind(binder, "proxy", ProxyConfig::new, ProxyConfig.class);
        UpdateConfig nextUpdate = bind(binder, "update", UpdateConfig::new, UpdateConfig.class);

        List<String> applied = new ArrayList<>();
        applyDownloadConfig(nextDownload, applied);
        applyMultiModeConfig(nextMultiMode, applied);
        applySetupConfig(nextSetup, applied);
        applySslConfig(nextSsl, applied);
        applyMaintenanceConfig(nextMaintenance, applied);
        applyProxyConfig(nextProxy, applied);
        applyUpdateConfig(nextUpdate, applied);

        if (!applied.isEmpty()) {
            log.info("Hot reloaded config keys: {}", applied);
        }
        return new ReloadResult(List.copyOf(applied));
    }

    private Binder loadBinder() throws IOException {
        Path configPath = RuntimeFiles.resolveConfigYamlPath();
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("config.yaml not found: " + configPath.toAbsolutePath());
        }

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = loader.load("runtime-config", new FileSystemResource(configPath));
        for (PropertySource<?> source : loaded) {
            sources.addLast(source);
        }
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    private <T> T bind(Binder binder, String prefix, Supplier<T> fallback, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }

    private void applyDownloadConfig(DownloadConfig next, List<String> applied) {
        applyIfChanged(applied,
                "download.user-flat-folder",
                downloadConfig.isUserFlatFolder(),
                next.isUserFlatFolder(),
                () -> downloadConfig.setUserFlatFolder(next.isUserFlatFolder()));
    }

    private void applyMultiModeConfig(MultiModeConfig next, List<String> applied) {
        MultiModeConfig.Quota currentQuota = ensureQuota(multiModeConfig);
        MultiModeConfig.Quota nextQuota = ensureQuota(next);

        applyIfChanged(applied,
                "multi-mode.quota.enabled",
                currentQuota.isEnabled(),
                nextQuota.isEnabled(),
                () -> currentQuota.setEnabled(nextQuota.isEnabled()));
        applyIfChanged(applied,
                "multi-mode.quota.max-artworks",
                currentQuota.getMaxArtworks(),
                nextQuota.getMaxArtworks(),
                () -> currentQuota.setMaxArtworks(nextQuota.getMaxArtworks()));
        applyIfChanged(applied,
                "multi-mode.quota.reset-period-hours",
                currentQuota.getResetPeriodHours(),
                nextQuota.getResetPeriodHours(),
                () -> currentQuota.setResetPeriodHours(nextQuota.getResetPeriodHours()));
        applyIfChanged(applied,
                "multi-mode.quota.archive-expire-minutes",
                currentQuota.getArchiveExpireMinutes(),
                nextQuota.getArchiveExpireMinutes(),
                () -> currentQuota.setArchiveExpireMinutes(nextQuota.getArchiveExpireMinutes()));
        applyIfChanged(applied,
                "multi-mode.quota.limit-image",
                currentQuota.getLimitImage(),
                nextQuota.getLimitImage(),
                () -> currentQuota.setLimitImage(nextQuota.getLimitImage()));
        applyIfChanged(applied,
                "multi-mode.quota.max-proxy-requests",
                currentQuota.getMaxProxyRequests(),
                nextQuota.getMaxProxyRequests(),
                () -> currentQuota.setMaxProxyRequests(nextQuota.getMaxProxyRequests()));

        applyIfChanged(applied,
                "multi-mode.post-download-mode",
                multiModeConfig.getPostDownloadMode(),
                next.getPostDownloadMode(),
                () -> multiModeConfig.setPostDownloadMode(next.getPostDownloadMode()));
        applyIfChanged(applied,
                "multi-mode.delete-after-hours",
                multiModeConfig.getDeleteAfterHours(),
                next.getDeleteAfterHours(),
                () -> multiModeConfig.setDeleteAfterHours(next.getDeleteAfterHours()));
        applyIfChanged(applied,
                "multi-mode.request-limit-minute",
                multiModeConfig.getRequestLimitMinute(),
                next.getRequestLimitMinute(),
                () -> multiModeConfig.setRequestLimitMinute(next.getRequestLimitMinute()));
        applyIfChanged(applied,
                "multi-mode.static-resource-request-limit-minute",
                multiModeConfig.getStaticResourceRequestLimitMinute(),
                next.getStaticResourceRequestLimitMinute(),
                () -> multiModeConfig.setStaticResourceRequestLimitMinute(next.getStaticResourceRequestLimitMinute()));
        applyIfChanged(applied,
                "multi-mode.limit-page",
                multiModeConfig.getLimitPage(),
                next.getLimitPage(),
                () -> multiModeConfig.setLimitPage(next.getLimitPage()));
    }

    private void applySetupConfig(SetupProperties next, List<String> applied) {
        applyIfChanged(applied,
                "setup.login-rate-limit-minute",
                setupProperties.getLoginRateLimitMinute(),
                next.getLoginRateLimitMinute(),
                () -> setupProperties.setLoginRateLimitMinute(next.getLoginRateLimitMinute()));
    }

    private void applySslConfig(SslConfig next, List<String> applied) {
        applyIfChanged(applied,
                "ssl.domain",
                sslConfig.getDomain(),
                next.getDomain(),
                () -> sslConfig.setDomain(next.getDomain()));
    }

    private void applyMaintenanceConfig(MaintenanceProperties next, List<String> applied) {
        applyIfChanged(applied,
                "maintenance.enabled",
                maintenanceProperties.isEnabled(),
                next.isEnabled(),
                () -> maintenanceProperties.setEnabled(next.isEnabled()));
    }

    private void applyProxyConfig(ProxyConfig next, List<String> applied) {
        applyIfChanged(applied,
                "proxy.enabled",
                proxyConfig.isEnabled(),
                next.isEnabled(),
                () -> proxyConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "proxy.host",
                proxyConfig.getHost(),
                next.getHost(),
                () -> proxyConfig.setHost(next.getHost()));
        applyIfChanged(applied,
                "proxy.port",
                proxyConfig.getPort(),
                next.getPort(),
                () -> proxyConfig.setPort(next.getPort()));
    }

    private void applyUpdateConfig(UpdateConfig next, List<String> applied) {
        applyIfChanged(applied,
                "update.enabled",
                updateConfig.isEnabled(),
                next.isEnabled(),
                () -> updateConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "update.manifest-url",
                updateConfig.getManifestUrl(),
                next.getManifestUrl(),
                () -> updateConfig.setManifestUrl(next.getManifestUrl()));
        applyIfChanged(applied,
                "update.nightly-manifest-url",
                updateConfig.getNightlyManifestUrl(),
                next.getNightlyManifestUrl(),
                () -> updateConfig.setNightlyManifestUrl(next.getNightlyManifestUrl()));
        applyIfChanged(applied,
                "update.auto-check",
                updateConfig.isAutoCheck(),
                next.isAutoCheck(),
                () -> updateConfig.setAutoCheck(next.isAutoCheck()));
        applyIfChanged(applied,
                "update.check-nightly",
                updateConfig.getCheckNightly(),
                next.getCheckNightly(),
                () -> updateConfig.setCheckNightly(next.getCheckNightly()));
    }

    private static MultiModeConfig.Quota ensureQuota(MultiModeConfig config) {
        MultiModeConfig.Quota quota = config.getQuota();
        if (quota == null) {
            quota = new MultiModeConfig.Quota();
            config.setQuota(quota);
        }
        return quota;
    }

    private static void applyIfChanged(List<String> applied,
                                       String key,
                                       Object current,
                                       Object next,
                                       Runnable apply) {
        if (Objects.equals(current, next)) {
            return;
        }
        apply.run();
        applied.add(key);
    }

    public record ReloadResult(List<String> appliedKeys) {}
}
