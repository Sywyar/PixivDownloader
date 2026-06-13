package top.sywyar.pixivdownload.plugin.api.plugin;

import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 功能插件主接口。插件通过本接口向核心声明需要统一合并的信息
 * （schema、路由、i18n、导航、静态资源等）；业务 Bean 仍由各插件的
 * {@code @Configuration} 显式装配，不经本接口返回。
 * <p>
 * 实现类不得携带任何 Spring 注解（插件实例可能由非 Spring 的插件管理器创建），
 * 由每插件一个的 {@code XxxPluginConfiguration} 以 {@code @Bean} 形式提供。
 * <p>
 * 维护任务目前仍经 Spring 自动发现注册到 {@code MaintenanceCoordinator}，
 * 不经本接口声明。
 */
public interface PixivFeaturePlugin {

    /** 插件唯一 id，小写短横线风格，例如 {@code download-workbench}。 */
    String id();

    /** 展示名称。 */
    String displayName();

    /** 插件类别。 */
    PluginKind kind();

    /**
     * 生命周期：启动。注册中心在应用启动（或插件被安装启用）时调用一次。
     */
    default void start() {
    }

    /**
     * 生命周期：停止。必须幂等，并负责释放该插件的全部注册与在途工作；
     * 除应用关闭外，运行期卸载插件时也会调用。
     */
    default void stop() {
    }

    /** 插件声明的表、索引与补列规则。 */
    default List<SchemaContribution> schema() {
        return List.of();
    }

    /** 插件对核心表列的使用声明（只读契约，用于核心列演进时的影响面追踪）。 */
    default List<CoreColumnUsage> coreColumnUsages() {
        return List.of();
    }

    /** 插件声明的路由与访问级别。 */
    default List<WebRouteContribution> routes() {
        return List.of();
    }

    /** 插件声明的静态资源目录。 */
    default List<StaticResourceContribution> staticResources() {
        return List.of();
    }

    /** 插件声明的 i18n namespace。 */
    default List<I18nContribution> i18n() {
        return List.of();
    }

    /** 插件声明的导航项。 */
    default List<NavigationContribution> navigation() {
        return List.of();
    }
}
