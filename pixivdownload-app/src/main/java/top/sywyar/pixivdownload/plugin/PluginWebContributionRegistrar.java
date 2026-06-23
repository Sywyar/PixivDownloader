package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ResourceBundle;

/**
 * 外置插件 web 贡献（route / static / i18n / navigation / ui-slot / userscript）的统一注册 / 注销入口。
 * 把一个插件向这六类下游注册中心的接入收口到一处，供 {@link ExternalPluginContextManager} 在外置插件子
 * {@code ApplicationContext} 关闭、或子 context 建立 / controller 注册失败时按 pluginId 一次性撤销其全部 web 贡献。
 *
 * <h2>注册方向（{@link #register}）</h2>
 * 各下游注册中心在<b>构造期</b>已从 {@link PluginRegistry} 的活动快照收集内置 + 外置插件的贡献（启动期接入路径，
 * 内置插件行为不变）。本类的 {@code register} 用于<b>先 {@link #unregister} 后再注册</b>的可逆链路——把同一插件的
 * 六类贡献按与构造期一致的口径重新接入，使「注册 → 注销 → 再注册」后各注册中心快照与首次一致。注册按插件<b>原子</b>：
 * 任一注册中心拒绝（id / 前缀 / namespace / 路由 / 槽位冲突 fail-fast）即把本次已接入的其它注册中心全部回滚后抛出，
 * 不留半接入状态；冲突诊断来自各注册中心、逐字透传。
 *
 * <h2>注销方向（{@link #unregister}）</h2>
 * 按 pluginId 从六类注册中心逐一注销（各注册中心对未注册过的 pluginId 静默返回，故幂等），随后
 * <b>清该插件 classloader 的 {@link ResourceBundle} 缓存</b>（避免注销后仍按旧 classloader 读到陈旧 i18n），
 * 并刷新 {@link ScriptRegistry}（其聚合的脚本列表 / 内容来源随 {@link UserscriptRegistry} 快照重算，使被注销插件
 * 的油猴脚本不再残留）。注销后这六类的快照都不含该插件；静态资源的访问回收不靠改 ResourceHandler——路由注销后
 * {@code AuthFilter} 对其 URL「未声明即 404」（与插件禁用语义一致）。
 *
 * <p>本类<b>不</b>触碰鉴权与请求分发表 handler（前者由 {@code AuthFilter} 按 {@link RouteAccessRegistry} 独立执行，
 * 后者由 {@code PluginControllerRegistrar} 负责），也不碰 schema（受管 schema 经 {@link PluginRegistry#allPlugins()}
 * 合并，禁用 / 卸载都保留已建表与数据）。register / unregister 在本对象的锁内串行，写入各注册中心复用其内部锁。
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

    private final Object lock = new Object();

    public PluginWebContributionRegistrar(RouteAccessRegistry routeAccessRegistry,
                                          StaticResourceRegistry staticResourceRegistry,
                                          WebI18nBundleRegistry webI18nBundleRegistry,
                                          NavigationRegistry navigationRegistry,
                                          WebUiSlotRegistry webUiSlotRegistry,
                                          UserscriptRegistry userscriptRegistry,
                                          ScriptRegistry scriptRegistry) {
        this.routeAccessRegistry = routeAccessRegistry;
        this.staticResourceRegistry = staticResourceRegistry;
        this.webI18nBundleRegistry = webI18nBundleRegistry;
        this.navigationRegistry = navigationRegistry;
        this.webUiSlotRegistry = webUiSlotRegistry;
        this.userscriptRegistry = userscriptRegistry;
        this.scriptRegistry = scriptRegistry;
    }

    /**
     * 按与各注册中心构造期一致的口径接入一个插件的六类 web 贡献（仅接入非空贡献），随后刷新 {@link ScriptRegistry}。
     * 接入按插件原子：任一注册中心抛出（重复 id / 前缀 / namespace / 路由 / 槽位冲突）即回滚本次已接入的其它注册中心并抛出。
     *
     * @param registered 插件及其来源 classloader（解析静态资源 / i18n / userscript 用）
     * @throws RuntimeException 任一注册中心拒绝接入时透传其诊断（已回滚本次接入）
     */
    public void register(PluginRegistry.RegisteredPlugin registered) {
        String pluginId = registered.id();
        ClassLoader classLoader = registered.classLoader();
        PixivFeaturePlugin plugin = registered.plugin();
        synchronized (lock) {
            // 记录本次成功接入的回滚动作，失败时逆序执行（不留半接入状态）。
            Deque<Runnable> rollback = new ArrayDeque<>();
            RuntimeException failure = null;
            try {
                if (!plugin.routes().isEmpty()) {
                    routeAccessRegistry.register(pluginId, plugin.routes());
                    rollback.push(() -> routeAccessRegistry.unregister(pluginId));
                }
                if (!plugin.staticResources().isEmpty()) {
                    staticResourceRegistry.register(pluginId, classLoader, plugin.staticResources());
                    rollback.push(() -> staticResourceRegistry.unregister(pluginId));
                }
                if (!plugin.i18n().isEmpty()) {
                    webI18nBundleRegistry.register(pluginId, classLoader, plugin.i18n());
                    rollback.push(() -> webI18nBundleRegistry.unregister(pluginId));
                }
                if (!plugin.navigation().isEmpty()) {
                    navigationRegistry.register(pluginId, plugin.navigation());
                    rollback.push(() -> navigationRegistry.unregister(pluginId));
                }
                if (!plugin.uiSlots().isEmpty()) {
                    webUiSlotRegistry.register(pluginId, plugin.uiSlots());
                    rollback.push(() -> webUiSlotRegistry.unregister(pluginId));
                }
                if (!plugin.userscripts().isEmpty()) {
                    userscriptRegistry.register(pluginId, classLoader, plugin.userscripts());
                    rollback.push(() -> userscriptRegistry.unregister(pluginId));
                }
            } catch (RuntimeException e) {
                rollback.forEach(Runnable::run);
                failure = e;
            }
            // 无论成功或回滚，都让脚本层与 userscript 注册中心最终状态对齐。
            scriptRegistry.refresh();
            if (failure != null) {
                throw failure;
            }
        }
        log.info("Registered web contributions for plugin '{}'.", pluginId);
    }

    /**
     * 按 pluginId 注销一个插件的六类 web 贡献（幂等），清其 classloader 的 {@link ResourceBundle} 缓存，
     * 并刷新 {@link ScriptRegistry}。统一卸载流程会对每个外置插件调用，故对未注册过的 pluginId 静默完成。
     *
     * @param pluginId    要注销的插件 id
     * @param classLoader 该插件的来源 classloader（清 i18n bundle 缓存用；{@code null} 时跳过清缓存）
     */
    public void unregister(String pluginId, ClassLoader classLoader) {
        synchronized (lock) {
            routeAccessRegistry.unregister(pluginId);
            staticResourceRegistry.unregister(pluginId);
            webI18nBundleRegistry.unregister(pluginId);
            navigationRegistry.unregister(pluginId);
            webUiSlotRegistry.unregister(pluginId);
            userscriptRegistry.unregister(pluginId);
            if (classLoader != null) {
                // 清掉以该 classloader 加载过的全部 bundle，避免注销后旧 i18n 被缓存命中。
                ResourceBundle.clearCache(classLoader);
            }
            scriptRegistry.refresh();
        }
        log.info("Unregistered web contributions for plugin '{}'.", pluginId);
    }

    /** 便利重载：用插件注册条目（含来源 classloader）注销。 */
    public void unregister(PluginRegistry.RegisteredPlugin registered) {
        unregister(registered.id(), registered.classLoader());
    }
}
