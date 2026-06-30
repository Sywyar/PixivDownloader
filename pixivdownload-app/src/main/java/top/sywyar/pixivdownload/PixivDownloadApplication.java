package top.sywyar.pixivdownload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSessionHandoff;

import java.nio.file.Path;

/**
 * 前两个排除过滤器是 {@code @SpringBootApplication} 元注解的原样展开；展开成显式组合
 * 是为了追加第三个：被 {@link PluginManagedBean} 标记的插件托管 Bean 不经根包扫描注册，
 * 一律由所属插件的 {@code XxxPluginConfiguration} 以 {@code @Bean} 显式提供。
 *
 * <p>两个入口：{@link #start(String[])}（headless / 普通）由 Spring 创建 CONTEXT 拥有的 bootstrap 会话；
 * {@link #start(String[], PluginBootstrapSession)}（GUI）接收进程级 PROCESS 会话并交接给 Spring——经
 * {@code ApplicationContextInitializer} 注册中性交接载体，{@code PluginRuntimeConfiguration} 据此复用同一会话、
 * 不重复 new / recover / start。两条路径不使用 static holder / ThreadLocal / 全局可变单例。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = PluginManagedBean.class)})
@EnableScheduling
public class PixivDownloadApplication {

    /*public static void main(String[] args) {
        start(args);
    }*/

    /**
     * headless / 普通入口：由 Spring 创建 CONTEXT 拥有的 bootstrap 会话（恢复事务 + 一次扫描 start），context 关闭时释放。
     * Spring 默认注册 JVM shutdown hook 负责关闭 context——headless 部署不注册 {@code BackendShutdownCoordinator}，
     * 故 Spring 是唯一退出关闭所有者。
     */
    public static ConfigurableApplicationContext start(String[] args) {
        Path configPath = RuntimeFiles.resolveConfigYamlPath();
        String rootFolder = RuntimeFiles.readDownloadRootFromConfig(configPath, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        RuntimeFiles.prepareRuntimeFiles(rootFolder);
        return buildHeadlessApplication().run(args);
    }

    /**
     * GUI 入口：接收进程级 bootstrap 会话（PROCESS 拥有、已 start），经 {@link PluginBootstrapHandoffInitializer}
     * 把会话作为中性交接载体 {@link PluginBootstrapSessionHandoff} 注册进 Spring bean 工厂——
     * {@link top.sywyar.pixivdownload.plugin.PluginRuntimeConfiguration} 据此复用同一会话的 manager / installer /
     * status，不再 new / recover / start。Spring context 关闭（后端 stop / restart）不关闭 PROCESS 会话；进程退出时
     * 由 GUI 关闭。
     *
     * <p><b>禁用 Spring 自动 shutdown hook</b>（{@code setRegisterShutdownHook(false)}）：GUI 进程的退出关闭由
     * {@code BackendShutdownCoordinator} 作为<b>单一</b> JVM shutdown hook 所有者负责（forbid → 清理注册 → 同步关 context →
     * 关 session），顺序确定。若保留 Spring 默认 hook，则 Spring hook 与 coordinator 会并发关闭同一 context，竞态未定义。
     * headless 路径（{@link #start(String[])}）保留 Spring 默认 hook。
     */
    public static ConfigurableApplicationContext start(String[] args, PluginBootstrapSession session) {
        Path configPath = RuntimeFiles.resolveConfigYamlPath();
        String rootFolder = RuntimeFiles.readDownloadRootFromConfig(configPath, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        RuntimeFiles.prepareRuntimeFiles(rootFolder);
        return buildGuiApplication(session).run(args);
    }

    /**
     * headless 的 {@link SpringApplication}：保留默认（注册 JVM shutdown hook）——headless 部署的退出关闭由 Spring 自有 lifecycle
     * 独占。抽成包级可见 seam 供结构守卫断言「headless 未禁用 hook」。
     */
    static SpringApplication buildHeadlessApplication() {
        return new SpringApplication(PixivDownloadApplication.class);
    }

    /**
     * GUI 的 {@link SpringApplication}：禁用 Spring 自动 shutdown hook（GUI 退出关闭由 {@code BackendShutdownCoordinator} 单一所有）+
     * 注入 bootstrap 会话交接载体。抽成包级可见 seam 供结构守卫断言「GUI 显式禁用 hook + 交接载体已注入」。
     */
    static SpringApplication buildGuiApplication(PluginBootstrapSession session) {
        SpringApplication application = new SpringApplication(PixivDownloadApplication.class);
        application.setRegisterShutdownHook(false);
        application.addInitializers(new PluginBootstrapHandoffInitializer(session));
        return application;
    }

    /**
     * 把 GUI 进程级 bootstrap 会话经中性载体交接给 Spring context：在 refresh 前把载体注册为单例，
     * {@link top.sywyar.pixivdownload.plugin.PluginRuntimeConfiguration} 检测到即复用会话。不持有 static 引用——
     * 载体随 context 销毁而释放，测试关闭 context 即不残留会话。
     */
    static final class PluginBootstrapHandoffInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private final PluginBootstrapSession session;

        PluginBootstrapHandoffInitializer(PluginBootstrapSession session) {
            this.session = session;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton(
                    "pluginBootstrapSessionHandoff", new PluginBootstrapSessionHandoff(session));
        }
    }
}
