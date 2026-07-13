package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebResourceResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 静态资源注册中心。收集各插件声明的 {@link StaticResourceContribution}，
 * 按 pluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用。
 * <p>
 * 资源根由插件最终 target class 的可验证 CodeSource 直接派生，并要求该 target class 正由
 * {@link PluginRegistry.RegisteredPlugin#classLoader()} 定义。解析不调用可向父级或依赖委派的
 * {@code ClassLoader#getResource}，所以宿主或依赖包中的同路径资源不能冒充 owner 自有资源。开发目录、普通外置
 * JAR 与 Spring Boot {@code BOOT-INF/classes} / nested CodeSource 都保存为同一条直接 {@link Resource}，查询期映射与
 * 前端模块校验复用该对象（见 {@link DynamicStaticResourceHandlerMapping}）。
 * <p>
 * 对外公开路径前缀（{@code publicPathPrefix}）全局唯一：两个声明指向同一前缀会让
 * 资源解析不确定，故前缀冲突（跨插件与同一批次内）一律在注册期拒绝，使应用启动失败而不是带病运行。
 */
@Component
public class StaticResourceRegistry {

    /** 一条已注册静态资源及其精确 owner；location 已固定到 owner 自身代码来源，不会委派到父级 / 依赖。 */
    public record RegisteredStaticResource(PluginRegistry.RegisteredPlugin owner,
                                           StaticResourceContribution contribution,
                                           Resource location) {
        public String pluginId() {
            return owner.id();
        }

        public ClassLoader classLoader() {
            return owner.classLoader();
        }
    }

    /**
     * 在 registry 锁外完成 owner 校验与资源根解析的不透明令牌。最终提交只做全局前缀冲突检查
     * 与快照替换，不再调用插件 getter 或触发 classloader 资源解析。
     */
    public static final class PreparedResources {
        private final Object authority;
        private final PluginRegistry.RegisteredPlugin owner;
        private final List<RegisteredStaticResource> resources;
        private boolean attempted;

        private PreparedResources(Object authority,
                                  PluginRegistry.RegisteredPlugin owner,
                                  List<RegisteredStaticResource> resources) {
            this.authority = authority;
            this.owner = owner;
            this.resources = List.copyOf(resources);
        }

        public PluginRegistry.RegisteredPlugin owner() {
            return owner;
        }

        public List<RegisteredStaticResource> resources() {
            return resources;
        }

        private synchronized void beginAttempt(Object expectedAuthority) {
            if (authority != expectedAuthority) {
                throw new IllegalStateException("prepared static resources belong to another registry");
            }
            if (attempted) {
                throw new IllegalStateException("prepared static resources already attempted for plugin: "
                        + owner.id());
            }
            attempted = true;
        }
    }

    private final Object lock = new Object();
    private final Object preparationAuthority = new Object();
    private final PluginRegistry pluginRegistry;
    private final PluginOwnedWebResourceResolver resourceResolver;

    private volatile List<RegisteredStaticResource> snapshot = List.of();

    @Autowired
    public StaticResourceRegistry(PluginRegistry pluginRegistry,
                                  PluginOwnedWebResourceResolver resourceResolver) {
        this.pluginRegistry = Objects.requireNonNull(pluginRegistry, "plugin registry");
        this.resourceResolver = resourceResolver;
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            List<StaticResourceContribution> resources = readContributionSnapshot(registered);
            if (!resources.isEmpty()) {
                PreparedResources prepared = prepare(registered, resources);
                prepared.beginAttempt(preparationAuthority);
                registerSnapshot(prepared);
            }
        }
    }

    public StaticResourceRegistry(PluginRegistry pluginRegistry) {
        this(pluginRegistry, new PluginOwnedWebResourceResolver());
    }

    /**
     * 注册一个插件声明的全部静态资源。同一 pluginId 重复注册、声明非法，
     * 或对外路径前缀与已注册项冲突都立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(PluginRegistry.RegisteredPlugin owner,
                         List<StaticResourceContribution> resources) {
        register(prepare(owner, resources));
    }

    /** 在 registry 锁外解析 owner 自有资源，不更改 serving 快照。 */
    public PreparedResources prepare(PluginRegistry.RegisteredPlugin owner,
                                     List<StaticResourceContribution> resources) {
        if (owner == null) {
            throw new IllegalStateException("static resource contribution without registered owner");
        }
        String pluginId = owner.id();
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("static resource contribution without pluginId");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalStateException("empty static resource contribution (plugin: " + pluginId + ")");
        }
        List<RegisteredStaticResource> prepared = new ArrayList<>();
        for (StaticResourceContribution contribution : resources) {
            validate(contribution, pluginId);
            prepared.add(new RegisteredStaticResource(
                    owner, contribution, resourceResolver.resolveLocation(owner, contribution)));
        }
        return new PreparedResources(preparationAuthority, owner, prepared);
    }

    /** 提交已准备资源；令牌只能回到创建它的 registry。 */
    public void register(PreparedResources preparedResources) {
        Objects.requireNonNull(preparedResources, "prepared static resources");
        preparedResources.beginAttempt(preparationAuthority);
        pluginRegistry.commitIfActiveIdentity(preparedResources.owner, commit -> {
            registerConsumed(preparedResources, commit);
            return null;
        });
    }

    /** 在 PluginRegistry 精确身份提交窗口内消费一次准备令牌。 */
    public void register(PreparedResources preparedResources, PluginRegistry.ActiveIdentityCommit commit) {
        Objects.requireNonNull(preparedResources, "prepared static resources");
        preparedResources.beginAttempt(preparationAuthority);
        registerConsumed(preparedResources, commit);
    }

    private void registerConsumed(
            PreparedResources preparedResources, PluginRegistry.ActiveIdentityCommit commit) {
        pluginRegistry.requireActiveIdentityCommit(commit, preparedResources.owner);
        registerSnapshot(preparedResources);
    }

    /** 构造期 / fresh direct registration 使用；prepared token 的公开提交路径仍必须经过精确身份复核。 */
    private void registerSnapshot(PreparedResources preparedResources) {
        PluginRegistry.RegisteredPlugin owner = preparedResources.owner;
        String pluginId = owner.id();
        List<RegisteredStaticResource> prepared = preparedResources.resources;
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("static resources already registered for plugin: " + pluginId);
            }
            Set<String> prefixes = snapshot.stream()
                    .map(registered -> registered.contribution().publicPathPrefix())
                    .collect(Collectors.toCollection(HashSet::new));
            List<RegisteredStaticResource> next = new ArrayList<>(snapshot);
            for (RegisteredStaticResource registered : prepared) {
                StaticResourceContribution contribution = registered.contribution();
                if (!prefixes.add(contribution.publicPathPrefix())) {
                    throw new IllegalStateException("duplicate static resource prefix: "
                            + contribution.publicPathPrefix() + " (plugin: " + pluginId + ")");
                }
                next.add(registered);
            }
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销一个插件的全部静态资源。插件可以不声明任何静态资源，统一卸载流程会对每个插件调用，
     * 因此对未注册过的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部已注册静态资源的不可变快照。 */
    public List<RegisteredStaticResource> resources() {
        return snapshot;
    }

    /** 返回该精确注册身份当前被 serving 的静态资源快照。 */
    public List<RegisteredStaticResource> resourcesFor(PluginRegistry.RegisteredPlugin owner) {
        if (owner == null) {
            return List.of();
        }
        return snapshot.stream().filter(resource -> resource.owner() == owner).toList();
    }

    private static void validate(StaticResourceContribution contribution, String pluginId) {
        if (contribution == null) {
            throw new IllegalStateException("null static resource contribution (plugin: " + pluginId + ")");
        }
        if (!pluginId.equals(contribution.pluginId())) {
            throw new IllegalStateException("static resource pluginId mismatch: declared "
                    + contribution.pluginId() + " under plugin " + pluginId);
        }
        String location = contribution.classpathLocation();
        if (location == null || location.isBlank()
                || !location.startsWith("classpath:") || !location.endsWith("/")) {
            throw new IllegalStateException("invalid static resource classpath location: " + location
                    + " (plugin: " + pluginId + ")");
        }
        String prefix = contribution.publicPathPrefix();
        if (prefix == null || prefix.isBlank() || !prefix.startsWith("/")) {
            throw new IllegalStateException("invalid static resource public path: " + prefix
                    + " (plugin: " + pluginId + ")");
        }
        if (contribution.exactFile()) {
            if (prefix.endsWith("/")) {
                throw new IllegalStateException("exact file contribution publicPath must not end with '/': " + prefix
                        + " (plugin: " + pluginId + ")");
            }
        } else {
            if (!prefix.endsWith("/")) {
                throw new IllegalStateException("directory contribution publicPathPrefix must end with '/': " + prefix
                        + " (plugin: " + pluginId + ")");
            }
        }
    }

    private static List<StaticResourceContribution> readContributionSnapshot(
            PluginRegistry.RegisteredPlugin registered) {
        try {
            List<StaticResourceContribution> resources = registered.plugin().staticResources();
            if (resources == null) {
                throw new IllegalStateException("plugin returned null");
            }
            return List.copyOf(resources);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw new IllegalStateException("failed to read static resource contributions for plugin '"
                    + registered.id() + "' (failureType=" + failure.getClass().getName() + ")");
        }
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
