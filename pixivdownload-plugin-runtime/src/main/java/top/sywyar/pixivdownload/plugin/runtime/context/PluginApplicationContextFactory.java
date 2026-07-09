package top.sywyar.pixivdownload.plugin.runtime.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.Objects;

/**
 * 每外置插件子 {@code ApplicationContext} 工厂：为一个外置插件包（{@link PluginContextModule}）建立一个子
 * {@link AnnotationConfigApplicationContext}，父 context 为核心应用 context。插件的 {@code @Configuration}
 * 配置类在子 context 中实例化（其 {@code @Bean} 装配插件自有 Bean），并能向父 context 解析核心 API / 服务接口。
 *
 * <h2>装配取舍</h2>
 * <ul>
 *   <li><b>子 context 的 classloader 用插件 classloader。</b>插件的配置类 / Bean 类与其类路径资源都经插件 loader
 *       解析，绝不回退核心壳应用 classloader。</li>
 *   <li><b>父 context 提供核心 API / 服务 Bean。</b>{@code setParent} 后子 context 找不到的依赖向父 BeanFactory
 *       解析，因此插件 Bean 可注入父 context 暴露的核心 API / 服务接口（但不应直接依赖核心实现类）。</li>
 *   <li><b>继承父 context 的环境属性。</b>{@code setParent} 会把父环境的属性源合并进子环境，使
 *       {@code @ConditionalOnPluginEnabled} 等条件、属性解析与父一致（须早于注册配置类，故先 {@code setParent}
 *       再 {@code register}）；据此被禁用插件的条件托管 Bean 在子 context 中同样缺席。</li>
 *   <li><b>统一启用事务注解处理。</b>子 context 注册通用基础设施配置，使插件内 {@code @Transactional}
 *       Bean 由子 context 自己创建代理；事务管理器仍从父 context 解析。</li>
 *   <li><b>纯附加、不触碰核心壳。</b>子 context 只实例化插件声明的配置类和通用基础设施，不引入 Spring Boot
 *       自动装配、不向父 context 的请求分发 / 注册中心注册任何东西。</li>
 * </ul>
 *
 * <p>本类是无状态 POJO（不带 Spring 注解），由核心壳侧装配为 Bean 并按外置插件生命周期调用。它<b>不</b>持有所创建
 * 子 context 的引用，生命周期（持有 / 关闭）由调用方（核心壳侧的子 context 管理器）负责。
 */
public final class PluginApplicationContextFactory {

    private static final Logger log = LoggerFactory.getLogger(PluginApplicationContextFactory.class);
    public static final String SCOPED_PROPERTY_SOURCE_PREFIX = "pixivdownloadPluginScoped:";

    private final PluginContextPropertySourceProvider propertySourceProvider;

    public PluginApplicationContextFactory() {
        this(PluginContextPropertySourceProvider.EMPTY);
    }

    public PluginApplicationContextFactory(PluginContextPropertySourceProvider propertySourceProvider) {
        this.propertySourceProvider = Objects.requireNonNull(propertySourceProvider, "propertySourceProvider");
    }

    /**
     * 为一个外置插件包建立并刷新子 {@code ApplicationContext}。返回的 context 已 {@code refresh()}、可用；
     * 调用方负责在插件停止 / 卸载时 {@code close()} 它。
     *
     * @param parent 父 context（核心应用 context），子 context 据此解析核心 API / 服务 Bean 并继承环境属性
     * @param module 外置插件包的子 context 装配定义（插件 classloader + 配置类 + 来源 id）
     * @return 已刷新的子 context（{@link ConfigurableApplicationContext}）
     */
    public ConfigurableApplicationContext create(ApplicationContext parent, PluginContextModule module) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(module, "module");

        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setId("plugin-context:" + module.sourcePluginId());
        child.setDisplayName("Plugin ApplicationContext for '" + module.sourcePluginId() + "'");
        // 子 context 的类加载用插件 classloader：插件配置类 / Bean 类与其类路径资源都经插件 loader 解析。
        child.setClassLoader(module.classLoader());
        child.getBeanFactory().setBeanClassLoader(module.classLoader());
        // 先挂父 context：合并父环境属性源（供条件 / 属性解析）+ 让子 context 找不到的依赖向父解析核心 API/服务 Bean。
        // 须早于 register（@Configuration 条件评估在注册与刷新期进行）。
        child.setParent(parent);
        replaceScopedPropertySource(child.getEnvironment(), module.sourcePluginId(),
                propertySourceProvider.propertiesFor(module.sourcePluginId()));
        child.register(PluginContextInfrastructureConfiguration.class);
        for (Class<?> configurationClass : module.configurationClasses()) {
            child.register(configurationClass);
        }
        child.refresh();

        log.info("Plugin context started for '{}': {} configuration class(es), {} bean definition(s).",
                module.sourcePluginId(), module.configurationClasses().size(), child.getBeanDefinitionCount());
        return child;
    }

    public static void replaceScopedPropertySource(ConfigurableEnvironment environment,
                                                   String ownerPluginId,
                                                   Map<String, ?> properties) {
        Objects.requireNonNull(environment, "environment");
        String owner = Objects.requireNonNull(ownerPluginId, "ownerPluginId").trim();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        String sourceName = SCOPED_PROPERTY_SOURCE_PREFIX + owner;
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(sourceName);
        if (properties == null || properties.isEmpty()) {
            return;
        }
        MapPropertySource scoped = new MapPropertySource(sourceName, Map.copyOf(properties));
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, scoped);
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, scoped);
        } else {
            sources.addFirst(scoped);
        }
    }
}
