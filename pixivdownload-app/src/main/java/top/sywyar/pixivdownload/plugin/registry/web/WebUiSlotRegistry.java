package top.sywyar.pixivdownload.plugin.registry.web;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotCatalog;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;

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
 * 与 {@link NavigationRegistry} / {@link PageSectionRegistry} 一样按插件生命周期注册 / 注销：
 * 宿主页面只声明稳定的槽位锚点，锚点内容
 * （是否渲染、由哪个模块渲染、叠放顺序）全部来自活动插件——禁用 / 停用 / 卸载插件后其槽位自然从快照消失。
 * 本注册中心只持有纯数据 record，不持插件 Bean / classloader / 子 context 引用。
 */
@Component
public class WebUiSlotRegistry implements WebUiSlotCatalog {

    /** 一条已注册 UI 槽位及其声明方插件。 */
    public record RegisteredUiSlot(String pluginId, WebUiSlotContribution slot, String publication, PluginRequestOwner requestOwner) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredUiSlot> snapshot = List.of();
    private final PluginRequestLeaseRegistry requests;

    public WebUiSlotRegistry(PluginRegistry pluginRegistry) {
        this(pluginRegistry, new PluginRequestLeaseRegistry());
    }

    @Autowired
    public WebUiSlotRegistry(PluginRegistry pluginRegistry, PluginRequestLeaseRegistry requests) {
        this.requests = requests;
        pluginRegistry.forEachBootPlugin(registered -> {
            List<WebUiSlotContribution> slots = registered.plugin().uiSlots();
            if (!slots.isEmpty()) {
                register(registered.id(), slots);
            }
        });
    }

    /**
     * 注册一个插件的全部 UI 槽位。同一 pluginId 重复注册、槽位非法或 slotId 与已注册项冲突都立即抛出，
     * 使应用启动失败而不是带病运行。slotId 要求全局唯一（便于诊断 / 去重 / 前端定位）。
     */
    public void register(String pluginId, List<WebUiSlotContribution> slots) {
        register(pluginId, null, slots);
    }

    /** 同一 web 发布事务盖章；撤回检查复用请求代际准入。 */
    public void register(String pluginId, PluginRequestOwner owner, List<WebUiSlotContribution> slots) {
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
            String publication = java.util.UUID.randomUUID().toString();
            for (WebUiSlotContribution item : slots) {
                validate(item, pluginId);
                if (!ids.add(item.slotId())) {
                    throw new IllegalStateException("duplicate ui slot id: "
                            + item.slotId() + " (plugin: " + pluginId + ")");
                }
                next.add(new RegisteredUiSlot(pluginId, item, publication, owner));
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
                    .collect(Collectors.toUnmodifiableList());
        }
    }

    /** 启动期已物化的声明绑定同一 web 请求 owner，不再调用插件 getter。 */
    public void bindBootRequestOwner(PluginRequestOwner owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            snapshot = snapshot.stream().map(slot -> {
                if (!slot.pluginId().equals(owner.pluginId())) return slot;
                if (slot.requestOwner() != null && !slot.requestOwner().equals(owner)) {
                    throw new IllegalStateException("ui slot request owner already bound: " + owner.pluginId());
                }
                return new RegisteredUiSlot(slot.pluginId(), slot.slot(), slot.publication(), owner);
            }).toList();
        }
    }

    /** 按注册顺序返回全部 UI 槽位的不可变快照。 */
    public List<RegisteredUiSlot> slots() {
        return snapshot.stream().filter(this::active).toList();
    }

    private boolean active(RegisteredUiSlot slot) {
        return slot.requestOwner() == null || requests.currentOwner(slot.pluginId()).filter(slot.requestOwner()::equals).isPresent();
    }

    /** 旧实例请求不能进入同 ID 的新 web 发布；路由权限仍由既有过滤链决定。 */
    public boolean permits(String publication, String routeOwner, PluginRequestOwner owner) {
        return slots().stream().anyMatch(slot -> slot.publication().equals(publication)
                && slot.pluginId().equals(routeOwner) && java.util.Objects.equals(slot.requestOwner(), owner));
    }

    /** 实例发出的业务调用绑定来源贡献，跨 owner 路由仍独立鉴权并获取目标请求 lease。 */
    public boolean permits(String publication) {
        return slots().stream().anyMatch(slot -> slot.publication().equals(publication));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return slots().stream().map(RegisteredUiSlot::slot).toList();
    }

    /** 返回某 owner 已在构造期或运行期提交的同一份槽位快照，供下载扩展 boot 装配避免再次调用插件 getter。 */
    public List<WebUiSlotContribution> slotsFor(String pluginId) {
        return snapshot.stream()
                .filter(registered -> registered.pluginId().equals(pluginId))
                .map(RegisteredUiSlot::slot)
                .toList();
    }

    private static void validate(WebUiSlotContribution slot, String pluginId) {
        if (slot == null) {
            throw new IllegalStateException("null ui slot contribution (plugin: " + pluginId + ")");
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
        if (slot.moduleUrl() != null && !WebContributionPaths.isSameOriginAbsolutePath(slot.moduleUrl())) {
            throw new IllegalStateException("ui slot moduleUrl must be a same-origin absolute path starting with '/' "
                    + "(no scheme / protocol-relative): " + slot.moduleUrl()
                    + " (slot: " + slot.slotId() + ", plugin: " + pluginId + ")");
        }
    }
}
