package top.sywyar.pixivdownload.plugin.runtime.context;

import java.util.List;
import java.util.Objects;

/**
 * 一个外置插件包的「Spring 子 context 装配定义」：要在子 {@code ApplicationContext} 中实例化的配置类、
 * 解析这些类与其类路径资源所用的插件 classloader，以及来源插件包 id。这是发现桥接
 * （{@code PixivPluginDiscoveryBridge.inspect(...)}）与功能插件身份在同一次准入读取中为
 * <b>已加载且SDK 兼容</b>的外置插件包在准入时产出的
 * 中性载体——只承载 {@code plugin.api} 入口契约暴露的 {@link Class} 配置类令牌与 JDK 类型，<b>不</b>泄露任何
 * 插件加载框架（PF4J）类型。
 *
 * <p>装配粒度是<b>外置插件包</b>（一个 PF4J pluginId = {@link #sourcePluginId}）：该包的全部配置类共享同一个
 * 插件 classloader，故归入同一个子 context。当前发布格式要求一个包只提供一个同 id 的
 * {@code PluginInstallation}；两种载体由宿主按同一 package generation 原子固化。
 *
 * @param sourcePluginId       外置插件包 id（PF4J pluginId），用于子 context 标识与诊断
 * @param classLoader          解析配置类与其类路径资源的插件 classloader（子 context 的 classloader）
 * @param configurationClasses 该插件包在已验证描述符中声明的 Spring {@code @Configuration} 配置类，
 *                             由插件 classloader 加载但不触发类初始化
 */
public record PluginContextModule(String sourcePluginId, ClassLoader classLoader,
                                  List<Class<?>> configurationClasses,
                                  List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> privateSchema,
                                  List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration> storageMigrations,
                                  List<String> sharedMainStorage) {

    public PluginContextModule(String sourcePluginId, ClassLoader classLoader, List<Class<?>> configurationClasses,
                               List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> privateSchema,
                               List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration> storageMigrations) {
        this(sourcePluginId, classLoader, configurationClasses, privateSchema, storageMigrations, List.of());
    }

    public PluginContextModule(String sourcePluginId, ClassLoader classLoader, List<Class<?>> configurationClasses) {
        this(sourcePluginId, classLoader, configurationClasses, List.of(), List.of());
    }

    public PluginContextModule {
        Objects.requireNonNull(sourcePluginId, "sourcePluginId");
        Objects.requireNonNull(classLoader, "classLoader");
        configurationClasses = List.copyOf(configurationClasses);
        privateSchema = List.copyOf(privateSchema);
        storageMigrations = List.copyOf(storageMigrations);
        sharedMainStorage = List.copyOf(sharedMainStorage);
        if (sharedMainStorage.stream().distinct().count() != sharedMainStorage.size())
            throw new IllegalArgumentException("STORAGE_REQUEST_DUPLICATE");
        sharedMainStorage.forEach(id -> new top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution.SharedAccess(id, true));
        top.sywyar.pixivdownload.plugin.api.storage.relational.PrivateStorageSchema.merge(privateSchema, storageMigrations);
    }

    /** 是否声明了至少一个业务配置类。 */
    public boolean hasConfigurationClasses() {
        return !configurationClasses.isEmpty();
    }

    /** 即使没有业务 Bean，私有结构的初始化和释放仍需受管生命周期。 */
    public boolean requiresContext() {
        return hasConfigurationClasses() || !privateSchema.isEmpty() || !storageMigrations.isEmpty() || !sharedMainStorage.isEmpty();
    }
}
