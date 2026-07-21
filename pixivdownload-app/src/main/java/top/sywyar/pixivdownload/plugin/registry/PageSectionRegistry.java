package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 页面区块注册中心。收集各<b>活动</b>插件（{@link PluginRegistry#plugins()}，禁用插件不贡献区块）的
 * {@link PageSectionContribution}，按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁（{@code /api/page-sections} 在每次请求上读取）。
 * <p>
 * 镜像 {@link NavigationRegistry} 的注册形态，但承载比单条导航链接更复杂的「页面区块」：让宿主页面只声明稳定的
 * section slot，区块内容（标题 / 操作入口 / 内嵌导航 slot / 前端模块）全部来自活动插件——宿主不需要知道是哪个插件。
 */
@Component
public class PageSectionRegistry {

    /** 一条已注册区块及其声明方插件。 */
    public record RegisteredSection(String pluginId, PageSectionContribution section) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredSection> snapshot = List.of();

    public PageSectionRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<PageSectionContribution> sections = plugin.pageSections();
            if (!sections.isEmpty()) {
                register(registered.id(), sections);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static PageSectionRegistry forBuiltInPlugins() {
        return new PageSectionRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部区块。同一 pluginId 重复注册、区块非法或区块 id 与已注册项冲突都立即抛出，
     * 使应用启动失败而不是带病运行。id 要求全局唯一（便于诊断 / 去重 / 前端模块定位自身容器）。
     */
    public void register(String pluginId, List<PageSectionContribution> sections) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("page section contribution without pluginId");
        }
        if (sections == null || sections.isEmpty()) {
            throw new IllegalStateException("empty page section contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("page sections already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.section().id())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredSection> next = new ArrayList<>(snapshot);
            for (PageSectionContribution item : sections) {
                validate(item, pluginId);
                if (!ids.add(item.id())) {
                    throw new IllegalStateException("duplicate page section id: "
                            + item.id() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredSection(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部区块。插件可以不声明任何区块，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部区块的不可变快照。 */
    public List<RegisteredSection> sections() {
        return snapshot;
    }

    private static void validate(PageSectionContribution section, String pluginId) {
        if (section == null) {
            throw new IllegalStateException("null page section contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(section.pluginId())) {
            throw new IllegalStateException("page section pluginId mismatch: declared "
                    + section.pluginId() + " under plugin " + pluginId);
        }
        if (section.id() == null || section.id().isBlank()) {
            throw new IllegalStateException("page section without id (plugin: " + pluginId + ")");
        }
        if (section.placement() == null || section.placement().isBlank()) {
            throw new IllegalStateException("page section without placement: "
                    + section.id() + " (plugin: " + pluginId + ")");
        }
        if (section.titleI18nKey() == null || section.titleI18nKey().isBlank()) {
            throw new IllegalStateException("page section without title i18n key: "
                    + section.id() + " (plugin: " + pluginId + ")");
        }
        // titleNamespace 必填：titleI18nKey 是纯 key，必须有确定 namespace 才能在前端解析（tns(namespace, key)）。
        // 留空会让前端 tns 退化为裸 key、在页面首个 namespace 内误解析，故注册期 fail-fast。
        if (section.titleNamespace() == null || section.titleNamespace().isBlank()) {
            throw new IllegalStateException("page section without title namespace: "
                    + section.id() + " (plugin: " + pluginId + ")");
        }
        // 操作入口标题 namespace 随 actionTitleI18nKey 条件必填：两者都为空表示无操作标题（合法）；一旦声明了
        // actionTitleI18nKey（纯 key），就必须随之声明 actionTitleNamespace，否则该纯 key 无确定 namespace 可解析。
        if (section.actionTitleI18nKey() != null && !section.actionTitleI18nKey().isBlank()
                && (section.actionTitleNamespace() == null || section.actionTitleNamespace().isBlank())) {
            throw new IllegalStateException("page section action title i18n key without namespace: "
                    + section.id() + " (plugin: " + pluginId + ")");
        }
        if (section.visibleTo() == null) {
            throw new IllegalStateException("page section without access policy: "
                    + section.id() + " (plugin: " + pluginId + ")");
        }
        if (!section.visibleTo().supportsUiVisibility()) {
            throw new IllegalStateException("page section access policy cannot be projected to UI visibility: "
                    + section.visibleTo() + " (section: " + section.id() + ", plugin: " + pluginId + ")");
        }
        // actionHref / moduleUrl 是可选字段：要么不提供（null），要么必须是同源绝对路径。拒绝 javascript: /
        // http(s):// 等带协议的 URL、//host 协议相对 URL 与 /\host 变体（都不以「单个 /」开头）。这是<b>声明期</b>
        // 安全边界：moduleUrl 会被通用渲染器作为 <script src> 加载、actionHref 渲染为锚点 href，限定为同源路径可
        // 防止区块声明把宿主导去外部站点或执行伪协议脚本。运行期权限边界仍是 AuthFilter / RouteAccessRegistry。
        validateLocalUrl(section.actionHref(), "actionHref", section, pluginId);
        validateLocalUrl(section.moduleUrl(), "moduleUrl", section, pluginId);
    }

    /**
     * 校验区块的可选本地 URL 字段：{@code null}（未提供）放行；否则必须是<b>同源绝对路径</b>
     *（以单个 {@code /} 开头），不满足即抛出使应用启动失败。
     */
    private static void validateLocalUrl(String value, String field,
                                         PageSectionContribution section, String pluginId) {
        if (value == null) {
            return;
        }
        if (!isSameOriginAbsolutePath(value)) {
            throw new IllegalStateException("page section " + field
                    + " must be a same-origin absolute path starting with '/' (no scheme / protocol-relative): "
                    + value + " (section: " + section.id() + ", plugin: " + pluginId + ")");
        }
    }

    /**
     * 同源绝对路径判定：以单个 {@code /} 开头。拒绝空串、{@code javascript:} / {@code http(s)://} 等带协议
     * 的 URL（不以 {@code /} 开头）、{@code //host} 协议相对 URL 与 {@code /\host} 反斜杠变体（第二个字符为
     * {@code /} 或 {@code \}，浏览器可能归一化为协议相对）。路径其余部分（含 query）不限制。
     */
    private static boolean isSameOriginAbsolutePath(String value) {
        if (value.isEmpty() || value.charAt(0) != '/') {
            return false;
        }
        if (value.length() >= 2) {
            char second = value.charAt(1);
            return second != '/' && second != '\\';
        }
        return true;
    }
}
