package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.core.notification.NotificationConfig;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.setup.SetupProperties;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;
import top.sywyar.pixivdownload.core.narration.NarrationTtsConfig;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigReloadService {

    private static final Pattern SAFE_CONFIG_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final DownloadConfig downloadConfig;
    private final MultiModeConfig multiModeConfig;
    private final GuestInviteConfig guestInviteConfig;
    private final SetupProperties setupProperties;
    private final SslConfig sslConfig;
    private final MaintenanceProperties maintenanceProperties;
    private final ProxyConfig proxyConfig;
    private final UpdateConfig updateConfig;
    private final NarrationTtsConfig narrationTtsConfig;
    private final NotificationConfig notificationConfig;
    private final ObjectProvider<PluginLifecycleService> pluginLifecycleService;

    public synchronized ReloadResult reloadHotConfig() throws IOException {
        return reloadHotConfig(List.of());
    }

    public synchronized ReloadResult reloadHotConfig(List<String> changedKeys) throws IOException {
        List<String> requestedChangedKeys = normalizeChangedKeys(changedKeys);
        Binder binder = loadBinder();
        DownloadConfig nextDownload = bind(binder, "download", DownloadConfig::new, DownloadConfig.class);
        MultiModeConfig nextMultiMode = bind(binder, "multi-mode", MultiModeConfig::new, MultiModeConfig.class);
        GuestInviteConfig nextGuestInvite = bind(binder, "guest-invite", GuestInviteConfig::new, GuestInviteConfig.class);
        SetupProperties nextSetup = bind(binder, "setup", SetupProperties::new, SetupProperties.class);
        SslConfig nextSsl = bind(binder, "ssl", SslConfig::new, SslConfig.class);
        MaintenanceProperties nextMaintenance = bind(binder, "maintenance", MaintenanceProperties::new, MaintenanceProperties.class);
        ProxyConfig nextProxy = bind(binder, "proxy", ProxyConfig::new, ProxyConfig.class);
        UpdateConfig nextUpdate = bind(binder, "update", UpdateConfig::new, UpdateConfig.class);
        NarrationTtsConfig nextNarrationTts = bind(binder, "narration-tts", NarrationTtsConfig::new, NarrationTtsConfig.class);
        NotificationConfig nextNotification = bind(binder, "notification", NotificationConfig::new, NotificationConfig.class);

        List<String> applied = new ArrayList<>();
        applyDownloadConfig(nextDownload, applied);
        applyMultiModeConfig(nextMultiMode, applied);
        applyGuestInviteConfig(nextGuestInvite, applied);
        applySetupConfig(nextSetup, applied);
        applySslConfig(nextSsl, applied);
        applyMaintenanceConfig(nextMaintenance, applied);
        applyProxyConfig(nextProxy, applied);
        applyUpdateConfig(nextUpdate, applied);
        applyNarrationTtsConfig(nextNarrationTts, applied);
        applyNotificationConfig(nextNotification, applied);
        rebindPluginConfig(binder, requestedChangedKeys, applied);

        if (!applied.isEmpty()) {
            log.info(message("gui.config.log.hot-reloaded", applied));
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
        PluginConfigPropertySourceLoader.load().ifPresent(sources::addLast);
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

    private void applyGuestInviteConfig(GuestInviteConfig next, List<String> applied) {
        applyIfChanged(applied,
                "guest-invite.request-limit-minute",
                guestInviteConfig.getRequestLimitMinute(),
                next.getRequestLimitMinute(),
                () -> guestInviteConfig.setRequestLimitMinute(next.getRequestLimitMinute()));
        applyIfChanged(applied,
                "guest-invite.static-resource-request-limit-minute",
                guestInviteConfig.getStaticResourceRequestLimitMinute(),
                next.getStaticResourceRequestLimitMinute(),
                () -> guestInviteConfig.setStaticResourceRequestLimitMinute(next.getStaticResourceRequestLimitMinute()));
        applyIfChanged(applied,
                "guest-invite.tts-request-limit-minute",
                guestInviteConfig.getTtsRequestLimitMinute(),
                next.getTtsRequestLimitMinute(),
                () -> guestInviteConfig.setTtsRequestLimitMinute(next.getTtsRequestLimitMinute()));
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
        applyMaintenanceSchedule(DayOfWeek.MONDAY, "monday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.TUESDAY, "tuesday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.WEDNESDAY, "wednesday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.THURSDAY, "thursday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.FRIDAY, "friday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.SATURDAY, "saturday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.SUNDAY, "sunday", next, applied);
    }

    private void applyMaintenanceSchedule(DayOfWeek day, String key, MaintenanceProperties next, List<String> applied) {
        MaintenanceProperties.DaySchedule currentSchedule = maintenanceProperties.mutableScheduleFor(day);
        MaintenanceProperties.DaySchedule nextSchedule = next.scheduleFor(day);
        applyIfChanged(applied,
                "maintenance." + key + ".enabled",
                currentSchedule.isEnabled(),
                nextSchedule.isEnabled(),
                () -> currentSchedule.setEnabled(nextSchedule.isEnabled()));
        applyIfChanged(applied,
                "maintenance." + key + ".time",
                currentSchedule.getTime(),
                nextSchedule.getTime(),
                () -> currentSchedule.setTime(nextSchedule.getTime()));
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
    private void applyNarrationTtsConfig(NarrationTtsConfig next, List<String> applied) {
        applyIfChanged(applied,
                "narration-tts.engine",
                narrationTtsConfig.getEngine(),
                next.getEngine(),
                () -> narrationTtsConfig.setEngine(next.getEngine()));
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

    private void applyNotificationConfig(NotificationConfig next, List<String> applied) {
        for (NotificationScenario scenario : NotificationScenario.values()) {
            String id = scenario.id();
            boolean current = notificationConfig.isScenarioEnabled(id);
            boolean nextEnabled = next.isScenarioEnabled(id);
            applyIfChanged(applied,
                    NotificationConfigKeys.scenarioEnabledKey(id),
                    current,
                    nextEnabled,
                    () -> notificationConfig.setScenarioEnabled(id, nextEnabled));
        }
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
        addAppliedKey(applied, key);
    }

    private void rebindPluginConfig(Binder binder, List<String> requestedChangedKeys, List<String> applied) {
        if (requestedChangedKeys.isEmpty()) {
            return;
        }
        PluginLifecycleService lifecycleService = pluginLifecycleService.getIfAvailable();
        if (lifecycleService == null) {
            return;
        }

        Set<String> reboundKeys = new LinkedHashSet<>();
        for (String pluginId : lifecycleService.servingPluginIds()) {
            lifecycleService.contextFor(pluginId)
                    .ifPresent(context -> rebindPluginContext(binder, context, requestedChangedKeys, reboundKeys));
        }
        for (String key : requestedChangedKeys) {
            if (reboundKeys.contains(key)) {
                addAppliedKey(applied, key);
            }
        }
    }

    private static void rebindPluginContext(Binder binder,
                                            ConfigurableApplicationContext context,
                                            List<String> requestedChangedKeys,
                                            Set<String> reboundKeys) {
        if (context == null || !context.isActive()) {
            return;
        }
        Map<String, Object> beans = context.getBeansWithAnnotation(ConfigurationProperties.class);
        for (Object bean : beans.values()) {
            String prefix = configurationPrefix(bean);
            if (prefix == null) {
                continue;
            }
            List<String> matchingKeys = requestedChangedKeys.stream()
                    .filter(key -> keyMatchesPrefix(key, prefix))
                    .toList();
            if (matchingKeys.isEmpty()) {
                continue;
            }
            binder.bind(prefix, Bindable.ofInstance(bean));
            reboundKeys.addAll(matchingKeys);
        }
    }

    private static String configurationPrefix(Object bean) {
        if (bean == null) {
            return null;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        ConfigurationProperties annotation =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, ConfigurationProperties.class);
        if (annotation == null) {
            return null;
        }
        String prefix = annotation.prefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = annotation.value();
        }
        return prefix == null || prefix.isBlank() ? null : prefix.trim();
    }

    private static boolean keyMatchesPrefix(String key, String prefix) {
        return key.equals(prefix) || key.startsWith(prefix + ".");
    }

    private static List<String> normalizeChangedKeys(List<String> changedKeys) {
        if (changedKeys == null || changedKeys.isEmpty()) {
            return List.of();
        }
        Set<String> safeKeys = new LinkedHashSet<>();
        for (String key : changedKeys) {
            if (key == null) {
                continue;
            }
            String normalized = key.trim();
            if (!normalized.isEmpty() && SAFE_CONFIG_KEY.matcher(normalized).matches()) {
                safeKeys.add(normalized);
            }
        }
        return List.copyOf(safeKeys);
    }

    private static void addAppliedKey(List<String> applied, String key) {
        if (!applied.contains(key)) {
            applied.add(key);
        }
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    public record ReloadResult(List<String> appliedKeys) {}
}
