package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Web UI 槽位注册中心。收集各<b>活动</b>插件（{@link PluginRegistry#plugins()}，禁用插件不贡献槽位）的
 * {@link WebUiSlotContribution}，按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用、读侧无锁（如下载工作台扩展点在每次请求上读取）。
 * <p>
 * 镜像 {@link NavigationRegistry} / {@link PageSectionRegistry} 的注册形态，把此前仅存在于前端 JS 的「页面槽位」
 * 机制提升为后端可追踪、可随插件生命周期动态注册 / 注销的契约：让宿主页面只声明稳定的槽位锚点，锚点内容
 * （是否渲染、由哪个模块渲染、叠放顺序）全部来自活动插件——禁用 / 停用 / 卸载插件后其槽位自然从快照消失。
 * 本注册中心只持有纯数据 record，不持插件 Bean / classloader / 子 context 引用。
 */
@Component
public class WebUiSlotRegistry {

    /** 一条已注册 UI 槽位及其声明方插件。 */
    public record RegisteredUiSlot(String pluginId, WebUiSlotContribution slot) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredUiSlot> snapshot = List.of();

    public WebUiSlotRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<WebUiSlotContribution> slots = plugin.uiSlots();
            if (!slots.isEmpty()) {
                register(plugin.id(), slots);
            }
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（测试 / 启动期检查等）使用。 */
    public static WebUiSlotRegistry forBuiltInPlugins() {
        return new WebUiSlotRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一个插件的全部 UI 槽位。同一 pluginId 重复注册、槽位非法或 slotId 与已注册项冲突都立即抛出，
     * 使应用启动失败而不是带病运行。slotId 要求全局唯一（便于诊断 / 去重 / 前端定位）。
     */
    public void register(String pluginId, List<WebUiSlotContribution> slots) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("ui slot contribution without pluginId");
        }
        if (slots == null || slots.isEmpty()) {
            throw new IllegalStateException("empty ui slot contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("ui slots already registered for plugin: " + pluginId);
            }
            Set<String> ids = snapshot.stream()
                    .map(registered -> registered.slot().slotId())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredUiSlot> next = new ArrayList<>(snapshot);
            for (WebUiSlotContribution item : slots) {
                validate(item, pluginId);
                if (!ids.add(item.slotId())) {
                    throw new IllegalStateException("duplicate ui slot id: "
                            + item.slotId() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredUiSlot(pluginId, item));
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部 UI 槽位。插件可以不声明任何槽位，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部 UI 槽位的不可变快照。 */
    public List<RegisteredUiSlot> slots() {
        return snapshot;
    }

    private static void validate(WebUiSlotContribution slot, String pluginId) {
        if (slot == null) {
            throw new IllegalStateException("null ui slot contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(slot.pluginId())) {
            throw new IllegalStateException("ui slot pluginId mismatch: declared "
                    + slot.pluginId() + " under plugin " + pluginId);
        }
        if (slot.slotId() == null || slot.slotId().isBlank()) {
            throw new IllegalStateException("ui slot without id (plugin: " + pluginId + ")");
        }
        if (slot.target() == null || slot.target().isBlank()) {
            throw new IllegalStateException("ui slot without target: "
                    + slot.slotId() + " (plugin: " + pluginId + ")");
        }
        // moduleUrl 是可选字段：要么不提供（null），要么必须是同源绝对路径。拒绝 javascript: / http(s):// 等带协议的
        // URL、//host 协议相对 URL 与 /\host 变体（都不以「单个 /」开头）。这是<b>声明期</b>安全边界：moduleUrl 会被
        // 前端作为 <script src> 加载，限定为同源路径可防止槽位声明把宿主导去外部站点或执行伪协议脚本。运行期权限
        // 边界仍是 AuthFilter / RouteAccessRegistry。
        if (slot.moduleUrl() != null && !isSameOriginAbsolutePath(slot.moduleUrl())) {
            throw new IllegalStateException("ui slot moduleUrl must be a same-origin absolute path starting with '/' "
                    + "(no scheme / protocol-relative): " + slot.moduleUrl()
                    + " (slot: " + slot.slotId() + ", plugin: " + pluginId + ")");
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
