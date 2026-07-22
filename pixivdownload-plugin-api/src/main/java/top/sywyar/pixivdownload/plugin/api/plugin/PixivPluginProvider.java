package top.sywyar.pixivdownload.plugin.api.plugin;

import java.util.List;

/**
 * 外置插件向核心暴露其 {@link PixivFeaturePlugin} 的入口契约。外置插件 jar 的主类
 * （PF4J {@code Plugin-Class}，运行期同时继承插件框架的插件基类）实现本接口，返回它贡献的唯一
 * {@link PixivFeaturePlugin}；核心壳的发现桥接据此把外置插件接入 {@code PluginRegistry}。
 *
 * <p><b>跨 classloader 边界契约。</b>本接口与 {@link PixivFeaturePlugin} 是外置插件发现和 contribution
 * 发布的入口类型，必须由父 classloader 加载的共享契约包 {@code pixivdownload-plugin-api} 提供——否则会出现
 * 「同名类不同 classloader」的 {@link ClassCastException}。插件 Bean 还可以消费父 classloader 提供的稳定
 * core-api 语义端口与宿主明确共享的规范依赖；任何跨边界类型都必须来自父 classloader，不能复制进插件产物。
 * 本接口自身保持零业务、零框架依赖（仅 JDK + {@code plugin.api}），<b>不</b>引用 PF4J、Spring 等加载框架类型。
 *
 * <p>一个外置插件包必须贡献且只贡献一个非 {@code null} 的 {@link PixivFeaturePlugin}，其
 * {@link PixivFeaturePlugin#id()} 必须与包描述符的 plugin id 相同。宿主在首次发现时校验该身份不变量，
 * 不合规的包以隔离诊断拒绝接入。
 */
public interface PixivPluginProvider {

    /**
     * 本外置插件包贡献的唯一 {@link PixivFeaturePlugin}。返回实例由插件自身 classloader 创建，
     * 其声明的静态资源 / i18n bundle 等也经该 classloader 解析。不得返回 {@code null}，且
     * {@link PixivFeaturePlugin#id()} 必须与包描述符的 plugin id 相同。
     */
    PixivFeaturePlugin featurePlugin();

    /**
     * 本外置插件需要由宿主装配的 Spring {@code @Configuration} 配置类。宿主为每个外置插件建立一个子
     * {@code ApplicationContext}（父 context 为核心应用），在其中实例化这里返回的配置类——插件的 Bean
     *（{@code @Service} / {@code @RestController} 等）由各配置类以
     * {@code @Bean} 显式装配，不经核心根包扫描。
     *
     * <p>子 context 的 Bean 可注入父 context 暴露的 plugin-api 契约与 core-api 稳定语义端口，但<b>不得</b>
     * 直接依赖宿主实现类；跨子 context 边界传递的类型限于 plugin-api、core-api、JDK 与宿主父 classloader
     * 明确共享的规范依赖。
     *
     * <p>返回类型只用 JDK {@link Class}，本契约不引用 Spring 类型，保持 {@code plugin.api} 的零框架依赖。
     * 默认返回空列表：不声明任何 Spring Bean 的插件无需覆写，宿主不为其建立子 context。不得返回 {@code null}。
     */
    default List<Class<?>> configurationClasses() {
        return List.of();
    }
}
