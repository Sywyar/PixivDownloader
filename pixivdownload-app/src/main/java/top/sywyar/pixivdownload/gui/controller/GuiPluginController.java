package top.sywyar.pixivdownload.gui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementEntry;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementReport;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

/**
 * GUI 专用插件状态只读接口。GUI（Swing，与后端同进程）经此读取插件管理视图，在桌面端展示已发现插件的安装 / 运行状态，
 * 并把启用 / 停用 / 安装 / 卸载等写操作引导到 Web 插件管理页（{@code /plugin-manage.html}）。
 *
 * <p><b>状态语义单一来源</b>：本控制器只委托核心 {@link PluginManagementService#list()} 投影，<b>不自行扫描插件目录、
 * 不复制状态判断逻辑</b>——GUI 与 Web 插件管理页共享同一份后端状态报告。本控制器只读，不暴露任何运行期生命周期动词
 * （load / start / quiesce / stop / unload / reload 仍只在 Web 管理页经 {@code /api/plugins/{id}/{verb}} 触发，
 * 受其 ADMIN 鉴权约束）。
 *
 * <p><b>鉴权边界不放宽</b>：路径在 {@code /api/gui/**} 下，由 {@code AuthFilter} 的 GUI 分支强制「本机可信请求 + GUI
 * token」双重校验；本控制器内部再做一次本机可信请求校验（与其它 {@code /api/gui/**} 端点一致），失败即 403。它不碰
 * {@code AuthFilter} / {@code RouteAccessRegistry}，也不持有任何 PF4J 类型。
 *
 * <p>插件展示名称在<b>服务端</b>按请求 locale 解析（复用 {@link WebI18nService} 的 classloader-aware i18n 流水线，
 * 覆盖外置插件），解析失败回退到插件 id——GUI 因此无需自行解析外置插件的 i18n bundle（GUI 的 {@code ConfigFieldRegistry}
 * 只遍历内置插件、解析不到外置）。
 */
@RestController
@RequestMapping("/api/gui/plugins")
public class GuiPluginController {

    private final PluginManagementService pluginManagementService;
    private final WebI18nService webI18nService;
    private final AppLocaleResolver localeResolver;

    public GuiPluginController(PluginManagementService pluginManagementService,
                              WebI18nService webI18nService,
                              AppLocaleResolver localeResolver) {
        this.pluginManagementService = pluginManagementService;
        this.webI18nService = webI18nService;
        this.localeResolver = localeResolver;
    }

    /**
     * GET /api/gui/plugins/status：插件管理视图的 GUI 投影（是否处于恢复模式 + 每个插件的展示名称 / 来源 / 状态 /
     * 运行期阶段 / 是否受管 / 是否必选 / 版本）。状态语义直接取自 {@link PluginManagementService#list()}，仅把展示名称
     * 在服务端解析为请求 locale 文案。
     */
    @GetMapping("/status")
    public ResponseEntity<GuiPluginStatusResponse> status(HttpServletRequest req) {
        if (!NetworkUtils.isTrustedLocalRequest(req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Locale locale = localeResolver.resolveLocale(req);
        PluginManagementReport report = pluginManagementService.list();
        DisplayNameResolver resolver = new DisplayNameResolver(webI18nService, locale);
        List<GuiPluginEntry> plugins = report.plugins().stream()
                .map(entry -> toEntry(entry, resolver))
                .toList();
        return ResponseEntity.ok(new GuiPluginStatusResponse(report.recoveryMode(), plugins));
    }

    private static GuiPluginEntry toEntry(PluginManagementEntry entry, DisplayNameResolver resolver) {
        return new GuiPluginEntry(
                entry.id(),
                resolver.resolve(entry.displayNamespace(), entry.displayNameKey(), entry.id()),
                entry.source(),
                entry.status() != null ? entry.status().name() : null,
                entry.runtimePhase() != null ? entry.runtimePhase().name() : null,
                entry.managed(),
                entry.requiredByPolicy(),
                entry.version(),
                entry.verification());
    }

    /**
     * 把插件声明的「namespace + 纯 i18n key」在服务端解析为当前 locale 的展示名称：复用 {@link WebI18nService}
     * 的 classloader-aware bundle 解析（覆盖外置插件），按 namespace 缓存本次请求的 bundle；namespace / key 缺失、
     * namespace 不可解析或 key 缺失时回退到插件 id——绝不抛出，使一个插件的 i18n 缺失不影响整份状态列表。
     */
    private static final class DisplayNameResolver {

        private final WebI18nService webI18nService;
        private final Locale locale;
        private final Map<String, Map<String, String>> bundleCache = new HashMap<>();

        DisplayNameResolver(WebI18nService webI18nService, Locale locale) {
            this.webI18nService = webI18nService;
            this.locale = locale;
        }

        String resolve(String namespace, String key, String fallback) {
            if (namespace == null || namespace.isBlank() || key == null || key.isBlank()) {
                return fallback;
            }
            String text = bundleCache.computeIfAbsent(namespace, this::loadBundle).get(key);
            return (text == null || text.isBlank()) ? fallback : text;
        }

        private Map<String, String> loadBundle(String namespace) {
            try {
                return webI18nService.loadBundle(namespace, locale).getMessages();
            } catch (RuntimeException e) {
                // 未注册 / 不可解析的 namespace（如未安装项、探针插件）：回退到 id，不影响其它条目。
                return Map.of();
            }
        }
    }

    /**
     * GUI 插件状态响应（{@code /api/gui/plugins/status} 的 JSON 契约）。复用核心
     * {@link PluginManagementReport} 的状态语义，仅把展示名称解析为文案、去掉 GUI 不需要的市场 / 描述符字段。
     *
     * @param recoveryMode 核心壳当前是否处于恢复模式（存在未满足的必选插件）
     * @param plugins      各插件状态条目
     */
    public record GuiPluginStatusResponse(boolean recoveryMode, List<GuiPluginEntry> plugins) {
    }

    /**
     * 单个插件的 GUI 状态条目。
     *
     * @param id           插件 id
     * @param name         展示名称（已按请求 locale 解析；解析不到时为插件 id）
     * @param source       来源：{@code built-in} / {@code external} / {@code not-installed}
     * @param status       评估状态（{@code PluginStatus} 名，如 {@code STARTED}）
     * @param runtimePhase 运行期阶段（{@code PluginRuntimePhase} 名；仅受管外置插件有，否则 {@code null}）
     * @param managed      是否受运行期生命周期管理
     * @param required     是否被必选策略声明为必选
     * @param version      插件版本（未安装的必选项为 {@code null}）
     * @param verification 验签状态投影
     */
    public record GuiPluginEntry(
            String id,
            String name,
            String source,
            String status,
            String runtimePhase,
            boolean managed,
            boolean required,
            String version,
            PluginVerificationView verification) {
    }
}
