package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 下钻注册中心。收集各<b>活动</b>插件（{@link PluginRegistry#plugins()}，禁用插件不贡献下钻）的
 * {@link DrilldownContribution}，按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁（{@code /api/drilldowns} 在每次请求上读取）。
 * <p>
 * 镜像 {@link PageSectionRegistry} / {@link NavigationRegistry} 的注册形态，承载「带变量占位的语义下钻链接模板」：
 * 让宿主页面只声明语义 placement，模板由活动插件贡献——宿主不需要知道是哪个插件，禁用贡献方后该 placement 自然无贡献。
 */
@Component
public class DrilldownRegistry {

    /** 一条已注册下钻贡献及其声明方插件。 */
    public record RegisteredDrilldown(String pluginId, DrilldownContribution drilldown) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredDrilldown> snapshot = List.of();

    public DrilldownRegistry(PluginRegistry pluginRegistry) {
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            PixivFeaturePlugin plugin = registered.plugin();
            List<DrilldownContribution> drilldowns = plugin.drilldowns();
            if (!drilldowns.isEmpty()) {
                register(registered.id(), drilldowns);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static DrilldownRegistry forBuiltInPlugins() {
        return new DrilldownRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部下钻贡献。同一 pluginId 重复注册、贡献非法或贡献 id 与已注册项冲突都立即抛出，
     * 使应用启动失败而不是带病运行。id 要求全局唯一（便于诊断 / 去重）。
     */
    public void register(String pluginId, List<DrilldownContribution> drilldowns) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("drilldown contribution without pluginId");
        }
        if (drilldowns == null || drilldowns.isEmpty()) {
            throw new IllegalStateException("empty drilldown contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("drilldowns already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.drilldown().id())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredDrilldown> next = new ArrayList<>(snapshot);
            for (DrilldownContribution item : drilldowns) {
                validate(item, pluginId);
                if (!ids.add(item.id())) {
                    throw new IllegalStateException("duplicate drilldown id: "
                            + item.id() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredDrilldown(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部下钻贡献。插件可以不声明任何下钻，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部下钻贡献的不可变快照。 */
    public List<RegisteredDrilldown> drilldowns() {
        return snapshot;
    }

    private static void validate(DrilldownContribution drilldown, String pluginId) {
        if (drilldown == null) {
            throw new IllegalStateException("null drilldown contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(drilldown.pluginId())) {
            throw new IllegalStateException("drilldown pluginId mismatch: declared "
                    + drilldown.pluginId() + " under plugin " + pluginId);
        }
        if (drilldown.id() == null || drilldown.id().isBlank()) {
            throw new IllegalStateException("drilldown without id (plugin: " + pluginId + ")");
        }
        if (drilldown.placements() == null || drilldown.placements().isEmpty()) {
            throw new IllegalStateException("drilldown without placement: "
                    + drilldown.id() + " (plugin: " + pluginId + ")");
        }
        for (String placement : drilldown.placements()) {
            if (placement == null || placement.isBlank()) {
                throw new IllegalStateException("drilldown with blank placement: "
                        + drilldown.id() + " (plugin: " + pluginId + ")");
            }
        }
        if (drilldown.visibleTo() == null) {
            throw new IllegalStateException("drilldown without access policy: "
                    + drilldown.id() + " (plugin: " + pluginId + ")");
        }
        // hrefTemplate 是必填项，且必须是同源绝对路径。拒绝 javascript: / http(s):// 等带协议的 URL、//host
        // 协议相对 URL 与 /\host 反斜杠变体（都不以「单个 /」开头）。这是<b>声明期</b>安全边界：模板会被前端通用
        // 下钻渲染器变量替换后作为锚点 href，限定为同源路径可防止贡献把宿主导去外部站点或执行伪协议脚本。
        // 运行期权限边界仍是 AuthFilter / RouteAccessRegistry。
        if (drilldown.hrefTemplate() == null || drilldown.hrefTemplate().isBlank()) {
            throw new IllegalStateException("drilldown without hrefTemplate: "
                    + drilldown.id() + " (plugin: " + pluginId + ")");
        }
        if (!isSameOriginAbsolutePath(drilldown.hrefTemplate())) {
            throw new IllegalStateException("drilldown hrefTemplate must be a same-origin absolute path "
                    + "starting with '/' (no scheme / protocol-relative): " + drilldown.hrefTemplate()
                    + " (drilldown: " + drilldown.id() + ", plugin: " + pluginId + ")");
        }
    }

    /**
     * 同源绝对路径判定：以单个 {@code /} 开头。拒绝空串、{@code javascript:} / {@code http(s)://} 等带协议
     * 的 URL（不以 {@code /} 开头）、{@code //host} 协议相对 URL 与 {@code /\host} 反斜杠变体（第二个字符为
     * {@code /} 或 {@code \}，浏览器可能归一化为协议相对）。路径其余部分（含 query 与 {@code {变量}} 占位）不限制。
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
