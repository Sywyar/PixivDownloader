package top.sywyar.pixivdownload.plugin.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLease;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionPublication;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * 外置插件 web 贡献（route / static / i18n / navigation / ui-slot / userscript）与下载扩展 publication
 * 的统一注册 / 注销入口。把一个插件向这些下游的接入收口到一处，供 {@link ExternalPluginContextManager} 在外置插件子
 * {@code ApplicationContext} 关闭、或子 context 建立 / controller 注册失败时按 pluginId 一次性撤销其全部 web 贡献。
 *
 * <h2>注册方向（{@link #register}）</h2>
 * 多数下游注册中心在<b>构造期</b>已从 {@link PluginRegistry} 的活动快照收集贡献；路由注册中心只直接聚合内置路由，
 * 外置路由由本类在同一启动准备快照中先发布请求 owner、再携 exact owner 接入。本类的 {@code register} 用于
 * <b>先 {@link #unregister} 后再注册</b>的可逆链路——把同一插件的
 * 六类贡献按与构造期一致的口径重新接入，使「注册 → 注销 → 再注册」后各注册中心快照与首次一致。插件 getter、
 * 资源解析与 owner-local 校验先在所有 registrar 锁外完成；最终提交按
 * {@code PluginRegistry → Web registrar → downstream registry} 固定锁序执行。任一下游拒绝都尝试回滚；
 * 若回滚本身在完成删除前报错，保留 provisional 句柄与逐步清理进度，由生命周期在 QUIESCED 安全态重试，
 * 不会丢失残留足迹的 recovery token。
 *
 * <h2>注销方向（{@link #unregister}）</h2>
 * 先精确撤回新请求准入并确认在途请求已经排空，再撤回下载 publication，随后按 pluginId 从六类注册中心逐一注销
 * （各注册中心对未注册过的 pluginId 静默返回，故幂等），最后
 * <b>清该插件 classloader 的 {@link ResourceBundle} 缓存</b>（避免注销后仍按旧 classloader 读到陈旧 i18n），
 * 并刷新 {@link ScriptRegistry}（其聚合的脚本列表 / 内容来源随 {@link UserscriptRegistry} 快照重算，使被注销插件
 * 的油猴脚本不再残留）。注销后这六类的快照都不含该插件；查询期静态资源映射会感知快照变化并回收处理器，
 * 路由注销后 {@code AuthFilter} 也会对其 URL「未声明即 404」（与插件禁用语义一致）。
 *
 * <p>本类只管理请求准入租约，不触碰鉴权与请求分发表 handler（前者由 {@code AuthFilter} 按
 * {@link RouteAccessRegistry} 独立执行，后者由 {@code PluginControllerRegistrar} 负责），也不碰 schema
 * （受管 schema 经 {@link PluginRegistry#allPlugins()}
 * 合并，禁用 / 卸载都保留已建表与数据）。只有最终发布 / 撤回修改在本对象锁内串行；插件回调与资源准备不持锁。
 */
@Slf4j
@Component
public class PluginWebContributionRegistrar {

    private final RouteAccessRegistry routeAccessRegistry;
    private final StaticResourceRegistry staticResourceRegistry;
    private final WebI18nBundleRegistry webI18nBundleRegistry;
    private final NavigationRegistry navigationRegistry;
    private final WebUiSlotRegistry webUiSlotRegistry;
    private final UserscriptRegistry userscriptRegistry;
    private final ScriptRegistry scriptRegistry;
    private final PluginRegistry pluginRegistry;
    private final DownloadExtensionRegistry downloadExtensionRegistry;
    private final PluginRequestLeaseRegistry requestLeaseRegistry;

    private final Object lock = new Object();
    /** 每个 pluginId 只有一个当前 serving；撤回授权由其中的句柄对象身份决定。 */
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private long nextServingId;

    private enum RegistrationState {
        COMMITTING,
        ACTIVE,
        CLEANUP_PENDING
    }

    private enum CleanupStep {
        REQUEST_ADMISSION,
        DOWNLOAD_EXTENSION,
        ROUTE,
        STATIC_RESOURCE,
        WEB_I18N,
        NAVIGATION,
        UI_SLOT,
        USERSCRIPT,
        RESOURCE_BUNDLE,
        SCRIPT_REFRESH
    }

    /** 一个 serving 代的可重试注册状态；仅在 {@link #lock} 内读写。 */
    private static final class Registration {
        private final PluginWebContributionHandle handle;
        private DownloadExtensionPublication downloadPublication;
        private RegistrationState state;
        private final EnumSet<CleanupStep> requiredCleanup = EnumSet.noneOf(CleanupStep.class);
        private final EnumSet<CleanupStep> completedCleanup = EnumSet.noneOf(CleanupStep.class);

        private Registration(PluginWebContributionHandle handle,
                             DownloadExtensionPublication downloadPublication,
                             RegistrationState state) {
            this.handle = handle;
            this.downloadPublication = downloadPublication;
            this.state = state;
        }

        private PluginWebContributionHandle handle() {
            return handle;
        }

        private DownloadExtensionPublication downloadPublication() {
            return downloadPublication;
        }

        private void requireCleanup(CleanupStep step) {
            requiredCleanup.add(step);
        }

        private void beginFullCleanup() {
            state = RegistrationState.CLEANUP_PENDING;
            requiredCleanup.addAll(EnumSet.allOf(CleanupStep.class));
        }

        private void beginPendingCleanup() {
            state = RegistrationState.CLEANUP_PENDING;
        }

        private boolean needsCleanup(CleanupStep step) {
            return requiredCleanup.contains(step) && !completedCleanup.contains(step);
        }

        private boolean cleanupSatisfied(CleanupStep step) {
            return !requiredCleanup.contains(step) || completedCleanup.contains(step);
        }

        private void completeCleanup(CleanupStep step) {
            completedCleanup.add(step);
        }

        private boolean cleanupComplete() {
            return completedCleanup.containsAll(requiredCleanup);
        }

        private void activate(DownloadExtensionPublication publication) {
            downloadPublication = publication;
            state = RegistrationState.ACTIVE;
            requiredCleanup.clear();
            completedCleanup.clear();
        }
    }

    /** 插件 getter 在任何 host registry 变更前一次性快照化，后续注册事务不再回调插件代码。 */
    private record ContributionSnapshot(
            List<WebRouteContribution> routes,
            List<StaticResourceContribution> staticResources,
            List<I18nContribution> i18n,
            List<NavigationContribution> navigation,
            List<WebUiSlotContribution> uiSlots,
            List<UserscriptContribution> userscripts,
            List<QueueTypeContribution> queueTypes,
            List<TabContribution> downloadTabs) {
    }

    /** 启动期外置路由只在 Web registrar 读取一次，并与随后分配的 exact serving 句柄一起提交。 */
    private record BootServingSnapshot(
            PluginWebContributionHandle handle,
            List<WebRouteContribution> routes) {
    }

    /**
     * 插件 getter、owner 资源解析与下载扩展本地校验均已完成的不透明准备令牌。生命周期只可把它原样交回
     * {@link #commit(PreparedWebContribution)}；同包的 controller registrar 仅能读取其中的路由声明判定，
     * 下载 publication 与其它准备结果在 commit 前均不可见。
     */
    public static final class PreparedWebContribution {
        private final PluginRegistry.RegisteredPlugin registered;
        private final ContributionSnapshot contributions;
        private final StaticResourceRegistry.PreparedResources staticResources;
        private final DownloadExtensionRegistry.PreparedPublication downloadPublication;
        private final PluginWebContributionHandle handle;
        private boolean attempted;

        private PreparedWebContribution(
                PluginRegistry.RegisteredPlugin registered,
                ContributionSnapshot contributions,
                StaticResourceRegistry.PreparedResources staticResources,
                DownloadExtensionRegistry.PreparedPublication downloadPublication,
                PluginWebContributionHandle handle) {
            this.registered = registered;
            this.contributions = contributions;
            this.staticResources = staticResources;
            this.downloadPublication = downloadPublication;
            this.handle = handle;
        }

        /** 准备时已分配且提交时不会更换的精确 serving 请求 owner。 */
        public PluginRequestOwner requestOwner() {
            return handle.requestOwner();
        }

        /** 只供同包 controller 注册器校验本次快照自己的 route contribution。 */
        boolean declares(String pluginId, String path, HttpMethod method) {
            return registered.id().equals(pluginId) && contributions.routes().stream()
                    .anyMatch(route -> route.matches(path) && route.acceptsMethod(method));
        }

        private synchronized void beginAttempt() {
            if (attempted) {
                throw new IllegalStateException("prepared web contribution already attempted for plugin: "
                        + registered.id());
            }
            attempted = true;
        }
    }

    @Autowired
    public PluginWebContributionRegistrar(RouteAccessRegistry routeAccessRegistry,
                                          StaticResourceRegistry staticResourceRegistry,
                                          WebI18nBundleRegistry webI18nBundleRegistry,
                                          NavigationRegistry navigationRegistry,
                                          WebUiSlotRegistry webUiSlotRegistry,
                                          UserscriptRegistry userscriptRegistry,
                                          ScriptRegistry scriptRegistry,
                                          PluginRegistry pluginRegistry,
                                          DownloadExtensionRegistry downloadExtensionRegistry,
                                          PluginRequestLeaseRegistry requestLeaseRegistry) {
        this.routeAccessRegistry = routeAccessRegistry;
        this.staticResourceRegistry = staticResourceRegistry;
        this.webI18nBundleRegistry = webI18nBundleRegistry;
        this.navigationRegistry = navigationRegistry;
        this.webUiSlotRegistry = webUiSlotRegistry;
        this.userscriptRegistry = userscriptRegistry;
        this.scriptRegistry = scriptRegistry;
        this.pluginRegistry = pluginRegistry;
        this.downloadExtensionRegistry = downloadExtensionRegistry;
        this.requestLeaseRegistry = requestLeaseRegistry;
        if (pluginRegistry != null && downloadExtensionRegistry != null) {
            for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
                DownloadExtensionPublication publication =
                        downloadExtensionRegistry.currentPublication(registered).orElse(null);
                if (isRequestManaged(registered)) {
                    BootServingSnapshot prepared = prepareBootServing(registered);
                    commitBootRegistration(registered, prepared, publication);
                } else {
                    PluginWebContributionHandle handle = newHandle(registered);
                    registrations.put(registered.id(), new Registration(
                            handle, publication, RegistrationState.ACTIVE));
                }
            }
        }
    }

    /** 兼容显式构造 registrar 的测试；生产装配额外注入请求租约 registry。 */
    public PluginWebContributionRegistrar(RouteAccessRegistry routeAccessRegistry,
                                          StaticResourceRegistry staticResourceRegistry,
                                          WebI18nBundleRegistry webI18nBundleRegistry,
                                          NavigationRegistry navigationRegistry,
                                          WebUiSlotRegistry webUiSlotRegistry,
                                          UserscriptRegistry userscriptRegistry,
                                          ScriptRegistry scriptRegistry,
                                          PluginRegistry pluginRegistry,
                                          DownloadExtensionRegistry downloadExtensionRegistry) {
        this(routeAccessRegistry, staticResourceRegistry, webI18nBundleRegistry,
                navigationRegistry, webUiSlotRegistry, userscriptRegistry, scriptRegistry,
                pluginRegistry, downloadExtensionRegistry, null);
    }

    /** 兼容独立 registry 单测；生产装配使用包含下载扩展 registry 的完整构造器。 */
    public PluginWebContributionRegistrar(RouteAccessRegistry routeAccessRegistry,
                                          StaticResourceRegistry staticResourceRegistry,
                                          WebI18nBundleRegistry webI18nBundleRegistry,
                                          NavigationRegistry navigationRegistry,
                                          WebUiSlotRegistry webUiSlotRegistry,
                                          UserscriptRegistry userscriptRegistry,
                                          ScriptRegistry scriptRegistry) {
        this(routeAccessRegistry, staticResourceRegistry, webI18nBundleRegistry,
                navigationRegistry, webUiSlotRegistry, userscriptRegistry, scriptRegistry,
                null, null, null);
    }

    private BootServingSnapshot prepareBootServing(
            PluginRegistry.RegisteredPlugin registered) {
        List<WebRouteContribution> routes = readPluginList(
                registered.id(), "routes", registered.plugin()::routes);
        return new BootServingSnapshot(newHandle(registered), routes);
    }

    private void commitBootRegistration(
            PluginRegistry.RegisteredPlugin registered,
            BootServingSnapshot prepared,
            DownloadExtensionPublication publication) {
        PluginWebContributionHandle handle = prepared.handle();
        PluginRequestOwner owner = handle.requestOwner();
        Registration bootRegistration =
                new Registration(handle, publication, RegistrationState.ACTIVE);
        boolean registrationPublished = false;
        try {
            requestLeaseRegistry.publish(owner);
            if (!prepared.routes().isEmpty()) {
                routeAccessRegistry.register(owner, prepared.routes());
            }
            Registration previous = registrations.putIfAbsent(registered.id(), bootRegistration);
            if (previous != null) {
                throw new IllegalStateException(
                        "web contributions already registered at boot for plugin: " + registered.id());
            }
            registrationPublished = true;
        } catch (Throwable failure) {
            if (registrationPublished) {
                registrations.remove(registered.id(), bootRegistration);
            }
            Throwable cleanupFailure = rollbackBootRequestOwner(owner);
            if (isFatal(failure)) {
                addSuppressedSafely(failure, cleanupFailure);
                rethrowFatal(failure);
            }
            if (isFatal(cleanupFailure)) {
                addSuppressedSafely(cleanupFailure, failure);
                rethrowFatal(cleanupFailure);
            }
            if (cleanupFailure != null) {
                throw boundaryFailure(
                        "failed to roll back boot request owner for plugin: " + registered.id(),
                        cleanupFailure);
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw boundaryFailure(
                    "failed to bind boot request owner for plugin: " + registered.id(), failure);
        }
    }

    private Throwable rollbackBootRequestOwner(PluginRequestOwner owner) {
        Throwable cleanupFailure = null;
        boolean requestsDrained = true;
        try {
            Optional<PluginRequestGenerationDrain> drain = requestLeaseRegistry.withdraw(owner);
            if (drain.isPresent()) {
                PluginRequestGenerationDrain servingDrain = drain.orElseThrow();
                requestsDrained = servingDrain.isDrained();
                if (!requestsDrained) {
                    throw new IllegalStateException(
                            "boot request serving still has active leases: " + owner.pluginId());
                }
                requestLeaseRegistry.retire(owner);
            }
        } catch (Throwable rollbackFailure) {
            cleanupFailure = rollbackFailure;
        }
        if (requestsDrained) {
            try {
                routeAccessRegistry.unregister(owner);
            } catch (Throwable rollbackFailure) {
                cleanupFailure = mergeFailures(cleanupFailure, rollbackFailure);
            }
        }
        return cleanupFailure;
    }

    private boolean isRequestManaged(PluginRegistry.RegisteredPlugin registered) {
        return requestLeaseRegistry != null && isExternal(registered);
    }

    private static boolean isExternal(PluginRegistry.RegisteredPlugin registered) {
        return registered.source() == PluginSource.EXTERNAL;
    }

    /**
     * 按与各注册中心构造期一致的口径接入一个插件的六类 web 贡献（仅接入非空贡献），随后刷新 {@link ScriptRegistry}。
     * 任一注册中心抛出（重复 id / 前缀 / namespace / 路由 / 槽位冲突）即回滚本次已接入足迹；
     * 回滚未完成时保留可重试句柄。
     *
     * @param registered 插件及其来源 classloader（解析静态资源 / i18n / userscript 用）
     * @throws RuntimeException 任一注册中心拒绝接入时透传其诊断（已回滚本次接入）
     */
    public PluginWebContributionHandle register(PluginRegistry.RegisteredPlugin registered) {
        PluginWebContributionHandle handle = commit(prepare(registered));
        log.info("Registered web contributions for plugin '{}'.", registered.id());
        return handle;
    }

    /**
     * 读取插件 getter、解析 owner 资源根并校验下载模块。该方法不持有 web/downstream registry 锁。
     */
    public PreparedWebContribution prepare(PluginRegistry.RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        ContributionSnapshot contributions = captureContributions(registered, registered.plugin());
        StaticResourceRegistry.PreparedResources preparedStatic = contributions.staticResources().isEmpty()
                ? null
                : staticResourceRegistry.prepare(registered, contributions.staticResources());
        DownloadExtensionRegistry.PreparedPublication preparedDownload = null;
        if (downloadExtensionRegistry != null) {
            List<StaticResourceRegistry.RegisteredStaticResource> resources = preparedStatic == null
                    ? List.of()
                    : preparedStatic.resources();
            preparedDownload = downloadExtensionRegistry.preparePublication(
                    registered, contributions.queueTypes(), contributions.downloadTabs(),
                    contributions.uiSlots(), resources);
        }
        PluginWebContributionHandle handle = newHandle(registered);
        return new PreparedWebContribution(
                registered, contributions, preparedStatic, preparedDownload, handle);
    }

    /**
     * 原子提交 {@link #prepare} 产生的同一快照；不会再次调用插件 getter，也不会重新解析资源或下载扩展。
     */
    public PluginWebContributionHandle commit(PreparedWebContribution prepared) {
        Objects.requireNonNull(prepared, "prepared web contribution");
        prepared.beginAttempt();
        PluginRegistry.RegisteredPlugin registered = prepared.registered;
        return pluginRegistry == null
                ? commitPrepared(prepared, null)
                : pluginRegistry.commitIfActiveIdentity(
                        registered, commit -> commitPrepared(prepared, commit));
    }

    /** 在 PluginRegistry 身份锁内按固定锁序提交 web/downstream，失败则保留可重试清理句柄。 */
    private PluginWebContributionHandle commitPrepared(
            PreparedWebContribution prepared,
            PluginRegistry.ActiveIdentityCommit identityCommit) {
        PluginRegistry.RegisteredPlugin registered = prepared.registered;
        ContributionSnapshot contributions = prepared.contributions;
        String pluginId = registered.id();
        ClassLoader classLoader = registered.classLoader();
        synchronized (lock) {
            Registration existing = registrations.get(pluginId);
            if (existing != null) {
                throw new IllegalStateException("web contributions already registered for plugin: " + pluginId
                        + " (state=" + existing.state + ")");
            }
            Registration registration = new Registration(
                    prepared.handle, null, RegistrationState.COMMITTING);
            registrations.put(pluginId, registration);
            try {
                if (isRequestManaged(registered)) {
                    registration.requireCleanup(CleanupStep.REQUEST_ADMISSION);
                    requestLeaseRegistry.publish(registration.handle().requestOwner());
                }
                if (!contributions.routes().isEmpty()) {
                    registration.requireCleanup(CleanupStep.ROUTE);
                    if (isExternal(registered)) {
                        routeAccessRegistry.register(
                                registration.handle().requestOwner(), contributions.routes());
                    } else {
                        routeAccessRegistry.register(pluginId, contributions.routes());
                    }
                }
                if (prepared.staticResources != null) {
                    registration.requireCleanup(CleanupStep.STATIC_RESOURCE);
                    if (identityCommit == null) {
                        staticResourceRegistry.register(prepared.staticResources);
                    } else {
                        staticResourceRegistry.register(prepared.staticResources, identityCommit);
                    }
                }
                if (!contributions.i18n().isEmpty()) {
                    registration.requireCleanup(CleanupStep.WEB_I18N);
                    if (classLoader != null) {
                        registration.requireCleanup(CleanupStep.RESOURCE_BUNDLE);
                    }
                    webI18nBundleRegistry.register(pluginId, classLoader, contributions.i18n());
                }
                if (!contributions.navigation().isEmpty()) {
                    registration.requireCleanup(CleanupStep.NAVIGATION);
                    navigationRegistry.register(pluginId, contributions.navigation());
                }
                if (!contributions.uiSlots().isEmpty()) {
                    registration.requireCleanup(CleanupStep.UI_SLOT);
                    webUiSlotRegistry.register(pluginId, contributions.uiSlots());
                }
                if (!contributions.userscripts().isEmpty()) {
                    registration.requireCleanup(CleanupStep.USERSCRIPT);
                    registration.requireCleanup(CleanupStep.SCRIPT_REFRESH);
                    userscriptRegistry.register(pluginId, classLoader, contributions.userscripts());
                }
                // 脚本聚合先与 userscript 快照对齐；下载扩展 publication 是最后一个可见发布点。
                scriptRegistry.refresh();
                DownloadExtensionPublication publication = prepared.downloadPublication == null
                        ? null
                        : downloadExtensionRegistry.publish(
                                prepared.downloadPublication, identityCommit).orElse(null);
                if (publication != null) {
                    registration.requireCleanup(CleanupStep.DOWNLOAD_EXTENSION);
                }
                registration.activate(publication);
                return registration.handle();
            } catch (Throwable failure) {
                registration.beginPendingCleanup();
                Throwable cleanupFailure = cleanPendingRegistration(registration);
                if (registration.cleanupComplete()) {
                    registrations.remove(pluginId, registration);
                }
                if (isFatal(failure)) {
                    addSuppressedSafely(failure, cleanupFailure);
                    rethrowFatal(failure);
                }
                if (isFatal(cleanupFailure)) {
                    addSuppressedSafely(cleanupFailure, failure);
                    rethrowFatal(cleanupFailure);
                }
                if (cleanupFailure != null) {
                    throw boundaryFailure(
                            "failed to roll back web contribution registration for plugin: " + pluginId,
                            cleanupFailure);
                }
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw boundaryFailure(
                        "failed to register web contributions for plugin: " + pluginId, failure);
            }
        }
    }

    private ContributionSnapshot captureContributions(
            PluginRegistry.RegisteredPlugin registered, PixivFeaturePlugin plugin) {
        String pluginId = registered.id();
        List<WebRouteContribution> routes = readPluginList(pluginId, "routes", plugin::routes);
        List<StaticResourceContribution> staticResources =
                readPluginList(pluginId, "staticResources", plugin::staticResources);
        List<I18nContribution> i18n = readPluginList(pluginId, "i18n", plugin::i18n);
        List<NavigationContribution> navigation =
                readPluginList(pluginId, "navigation", plugin::navigation);
        List<WebUiSlotContribution> uiSlots = readPluginList(pluginId, "uiSlots", plugin::uiSlots);
        List<UserscriptContribution> userscripts =
                readPluginList(pluginId, "userscripts", plugin::userscripts);
        List<QueueTypeContribution> queueTypes = downloadExtensionRegistry == null
                ? List.of()
                : readPluginList(pluginId, "queueTypes", plugin::queueTypes);
        List<TabContribution> downloadTabs = downloadExtensionRegistry == null
                ? List.of()
                : readPluginList(pluginId, "downloadTabs", plugin::downloadTabs);
        return new ContributionSnapshot(
                routes, staticResources, i18n, navigation, uiSlots, userscripts, queueTypes, downloadTabs);
    }

    private static <T> List<T> readPluginList(
            String pluginId, String field, Supplier<List<T>> reader) {
        try {
            List<T> contributions = reader.get();
            if (contributions == null) {
                throw new IllegalStateException("plugin returned null");
            }
            return List.copyOf(contributions);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw boundaryFailure("failed to read plugin contribution '" + field
                    + "' for plugin: " + pluginId, failure);
        }
    }

    /** 返回启动期或当前 serving 的精确句柄；只接受同一个 RegisteredPlugin 对象身份。 */
    public Optional<PluginWebContributionHandle> currentHandle(
            PluginRegistry.RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered plugin");
        synchronized (lock) {
            Registration current = registrations.get(registered.id());
            if (current == null || current.handle().registered() != registered) {
                return Optional.empty();
            }
            return Optional.of(current.handle());
        }
    }

    /** 该句柄是否仍是当前 serving；生命周期据此区分“已撤回但清理报错”和“前置撤回失败并恢复”。 */
    public boolean isCurrent(PluginWebContributionHandle handle) {
        if (handle == null) {
            return false;
        }
        synchronized (lock) {
            Registration current = registrations.get(handle.pluginId());
            return current != null && current.handle() == handle;
        }
    }

    /**
     * 撤回精确外置 serving 的新请求准入并返回其代际 drain。该动作幂等，且不等待 drain；调用方不得在
     * registrar 锁内等待。内置 serving 或未装配请求租约 registry 的独立测试 registrar 返回空。
     */
    public Optional<PluginRequestGenerationDrain> withdrawRequests(
            PluginWebContributionHandle handle) {
        Objects.requireNonNull(handle, "web contribution handle");
        if (!isRequestManaged(handle.registered())) {
            return Optional.empty();
        }
        synchronized (lock) {
            Registration current = registrations.get(handle.pluginId());
            if (current == null || current.handle() != handle) {
                throw new IllegalStateException(
                        "web contribution handle is not the current serving: " + handle.pluginId());
            }
            return requestLeaseRegistry.withdraw(handle.requestOwner());
        }
    }

    /** Preallocate an inactive request lease for one exact current serving. */
    public Optional<PluginRequestLease> prepareRequestLease(PluginWebContributionHandle handle) {
        Objects.requireNonNull(handle, "web contribution handle");
        if (!isRequestManaged(handle.registered())) {
            return Optional.empty();
        }
        synchronized (lock) {
            Registration current = registrations.get(handle.pluginId());
            if (current == null || current.handle() != handle) {
                return Optional.empty();
            }
            return requestLeaseRegistry.prepareLease(handle.requestOwner());
        }
    }

    /** Activate a prepared lease only while the same exact serving handle is still current. */
    public boolean activateRequestLease(
            PluginWebContributionHandle handle,
            PluginRequestLease lease) {
        Objects.requireNonNull(handle, "web contribution handle");
        Objects.requireNonNull(lease, "prepared request lease");
        if (!isRequestManaged(handle.registered()) || !lease.owner().equals(handle.requestOwner())) {
            return false;
        }
        synchronized (lock) {
            Registration current = registrations.get(handle.pluginId());
            if (current == null || current.handle() != handle) {
                return false;
            }
            return requestLeaseRegistry.activate(lease);
        }
    }

    /**
     * 按 pluginId 注销一个插件的六类 web 贡献（幂等），清其 classloader 的 {@link ResourceBundle} 缓存，
     * 并刷新 {@link ScriptRegistry}。统一卸载流程会对每个外置插件调用，故对未注册过的 pluginId 静默完成。
     *
     * @param handle 要注销的精确 serving 句柄；旧句柄不会影响当前 publication
     */
    public boolean unregister(PluginWebContributionHandle handle) {
        if (handle == null) {
            return false;
        }
        PluginRegistry.RegisteredPlugin registered = handle.registered();
        String pluginId = registered.id();
        synchronized (lock) {
            Registration registration = registrations.get(pluginId);
            if (registration == null || registration.handle() != handle) {
                log.debug("Ignoring stale web contribution handle for plugin '{}' generation {} serving {}.",
                        pluginId, registered.generation(), handle.servingId());
                return false;
            }
            registration.beginFullCleanup();
            Throwable cleanupFailure = cleanPendingRegistration(registration);
            if (isFatal(cleanupFailure)) {
                rethrowFatal(cleanupFailure);
            }
            if (cleanupFailure != null) {
                throw boundaryFailure(
                        "failed to fully unregister web contributions for plugin: " + pluginId,
                        cleanupFailure);
            }
            if (!registration.cleanupComplete()) {
                throw new IllegalStateException(
                        "web contribution cleanup remains pending for plugin: " + pluginId);
            }
            registrations.remove(pluginId, registration);
        }
        log.info("Unregistered web contributions for plugin '{}'.", pluginId);
        return true;
    }

    private PluginWebContributionHandle newHandle(PluginRegistry.RegisteredPlugin registered) {
        synchronized (lock) {
            long servingId = Math.addExact(nextServingId, 1L);
            nextServingId = servingId;
            return new PluginWebContributionHandle(registered, servingId);
        }
    }

    /** 重试尚未完成的清理步骤；成功步骤不再重复，请求准入与 drain 始终先于其它可见足迹撤回。 */
    private Throwable cleanPendingRegistration(Registration registration) {
        String pluginId = registration.handle().pluginId();
        Throwable cleanupFailure = cleanupStep(
                registration, CleanupStep.REQUEST_ADMISSION, () -> {
                    if (!isRequestManaged(registration.handle().registered())) {
                        return;
                    }
                    PluginRequestOwner owner = registration.handle().requestOwner();
                    Optional<PluginRequestGenerationDrain> drain = requestLeaseRegistry.withdraw(owner);
                    if (drain.isPresent()) {
                        PluginRequestGenerationDrain servingDrain = drain.orElseThrow();
                        if (!servingDrain.isDrained()) {
                            throw new IllegalStateException(
                                    "plugin request serving still has active leases: " + pluginId
                                            + " (active=" + servingDrain.activeLeaseCount() + ")");
                        }
                        requestLeaseRegistry.retire(owner);
                    }
                }, null, pluginId, "request-admission");
        if (!registration.cleanupSatisfied(CleanupStep.REQUEST_ADMISSION)) {
            return cleanupFailure;
        }

        cleanupFailure = cleanupStep(
                registration, CleanupStep.DOWNLOAD_EXTENSION, () -> {
                    DownloadExtensionPublication publication = registration.downloadPublication();
                    if (publication != null && downloadExtensionRegistry != null
                            && !downloadExtensionRegistry.withdraw(publication)) {
                        throw new IllegalStateException(
                                "download extension publication is no longer current for plugin: " + pluginId);
                    }
                }, null, pluginId, "download-extension");
        if (!registration.cleanupSatisfied(CleanupStep.DOWNLOAD_EXTENSION)) {
            // download 是后续 web 足迹拆除的前置；它仍可见时不制造反向的半撤回快照。
            return cleanupFailure;
        }

        cleanupFailure = cleanupStep(
                registration, CleanupStep.ROUTE,
                () -> {
                    if (isExternal(registration.handle().registered())) {
                        routeAccessRegistry.unregister(registration.handle().requestOwner());
                    } else {
                        routeAccessRegistry.unregister(pluginId);
                    }
                }, cleanupFailure, pluginId, "route");
        cleanupFailure = cleanupStep(
                registration, CleanupStep.STATIC_RESOURCE,
                () -> staticResourceRegistry.unregister(pluginId), cleanupFailure, pluginId, "static-resource");
        cleanupFailure = cleanupStep(
                registration, CleanupStep.WEB_I18N,
                () -> webI18nBundleRegistry.unregister(pluginId), cleanupFailure, pluginId, "web-i18n");
        cleanupFailure = cleanupStep(
                registration, CleanupStep.NAVIGATION,
                () -> navigationRegistry.unregister(pluginId), cleanupFailure, pluginId, "navigation");
        cleanupFailure = cleanupStep(
                registration, CleanupStep.UI_SLOT,
                () -> webUiSlotRegistry.unregister(pluginId), cleanupFailure, pluginId, "ui-slot");
        cleanupFailure = cleanupStep(
                registration, CleanupStep.USERSCRIPT,
                () -> userscriptRegistry.unregister(pluginId), cleanupFailure, pluginId, "userscript");

        if (registration.cleanupSatisfied(CleanupStep.WEB_I18N)) {
            ClassLoader classLoader = registration.handle().registered().classLoader();
            cleanupFailure = cleanupStep(
                    registration, CleanupStep.RESOURCE_BUNDLE,
                    () -> {
                        if (classLoader != null) {
                            ResourceBundle.clearCache(classLoader);
                        }
                    }, cleanupFailure, pluginId, "resource-bundle");
        }
        if (registration.cleanupSatisfied(CleanupStep.USERSCRIPT)) {
            cleanupFailure = cleanupStep(
                    registration, CleanupStep.SCRIPT_REFRESH,
                    scriptRegistry::refresh, cleanupFailure, pluginId, "script-refresh");
        }
        return cleanupFailure;
    }

    private static Throwable cleanupStep(Registration registration,
                                         CleanupStep step,
                                         Runnable action,
                                         Throwable firstFailure,
                                         String pluginId,
                                         String actionName) {
        if (!registration.needsCleanup(step)) {
            return firstFailure;
        }
        try {
            action.run();
            registration.completeCleanup(step);
            return firstFailure;
        } catch (Throwable failure) {
            log.warn("Web contribution cleanup failed for plugin '{}' action '{}' (failureType={}).",
                    pluginId, actionName, failure.getClass().getName());
            return mergeFailures(firstFailure, failure);
        }
    }

    private static Throwable mergeFailures(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (isFatal(next) && !isFatal(first)) {
            addSuppressedSafely(next, first);
            return next;
        }
        addSuppressedSafely(first, next);
        return first;
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target != null && suppressed != null && target != suppressed) {
            try {
                target.addSuppressed(suppressed);
            } catch (Throwable ignored) {
                // 保留原始失败对象身份；诊断附加本身不得覆盖主失败。
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static IllegalStateException boundaryFailure(String message, Throwable failure) {
        return new IllegalStateException(message + " (failureType=" + failure.getClass().getName() + ")");
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }
}
