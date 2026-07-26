package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台外置模块自己的编译边界守卫。
 *
 * <p>app 模块也会在外置模块已编译时镜像检查这些约束；这里保证 clean reactor
 * 按模块顺序执行时，download-workbench 模块会强制覆盖前置清债与物理外置边界。
 */
class DownloadWorkbenchDependencyGuardTest {

    private static final JavaClasses CLASSES = importPluginClasses();
    private static final DescribedPredicate<JavaClass> CONCRETE_DOWNLOAD_SERVICE =
            new DescribedPredicate<>("concrete download service") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return javaClass.getFullName().equals(
                            "top.sywyar.pixivdownload.download.ArtworkDownloadExecutor")
                            || javaClass.getPackageName().startsWith(
                                    "top.sywyar.pixivdownload.novel.download");
                }
            };
    private static final Set<String> HOST_BOUNDARY_IMPLEMENTATIONS = Set.of(
            "top.sywyar.pixivdownload.common.UuidUtils",
            "top.sywyar.pixivdownload.config.RuntimeFiles",
            "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
            "top.sywyar.pixivdownload.core.appconfig.MultiModeConfig",
            "top.sywyar.pixivdownload.core.notification.NotificationService",
            "top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessGuard",
            "top.sywyar.pixivdownload.core.pixiv.PixivThumbnailFetchService",
            "top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation",
            "top.sywyar.pixivdownload.ffmpeg.FfmpegLocator",
            "top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry",
            "top.sywyar.pixivdownload.quota.UserQuotaService",
            "top.sywyar.pixivdownload.setup.SetupService",
            "top.sywyar.pixivdownload.setup.guest.GuestAccessGuard");
    private static final DescribedPredicate<JavaClass> HOST_BOUNDARY_IMPLEMENTATION =
            new DescribedPredicate<>("host runtime/config/setup implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return HOST_BOUNDARY_IMPLEMENTATIONS.contains(javaClass.getFullName());
                }
            };
    private static final DescribedPredicate<JavaClass> HOST_DOWNLOAD_CONTROL_IMPLEMENTATION =
            new DescribedPredicate<>("host download control implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    String className = javaClass.getFullName();
                    return className.startsWith(
                            "top.sywyar.pixivdownload.core.download.control.")
                            || className.startsWith(
                            "top.sywyar.pixivdownload.core.download.queue.")
                            || className.startsWith(
                            "top.sywyar.pixivdownload.plugin.registry.DownloadExtension");
                }
            };
    private static final DescribedPredicate<JavaClass> HOST_USERSCRIPT_IMPLEMENTATION =
            new DescribedPredicate<>("host userscript implementation or visitor rate limiter") {
                @Override
                public boolean test(JavaClass javaClass) {
                    String className = javaClass.getFullName();
                    return className.equals("top.sywyar.pixivdownload.quota.RateLimitService")
                            || className.startsWith("top.sywyar.pixivdownload.scripts.ScriptRegistry")
                            || className.startsWith("top.sywyar.pixivdownload.scripts.ScriptResource")
                            || className.startsWith("top.sywyar.pixivdownload.scripts.UserscriptRegistry");
                }
            };
    private static final Set<String> DIRECT_PIXIV_TRANSPORT_IMPLEMENTATIONS = Set.of(
            "org.springframework.http.client.ClientHttpResponse",
            "org.springframework.web.client.RestTemplate",
            "top.sywyar.pixivdownload.common.PixivRequestHeaders");
    private static final DescribedPredicate<JavaClass> DIRECT_PIXIV_TRANSPORT_IMPLEMENTATION =
            new DescribedPredicate<>("direct Pixiv transport implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return DIRECT_PIXIV_TRANSPORT_IMPLEMENTATIONS.contains(javaClass.getFullName());
                }
            };

    @Test
    @DisplayName("download 包不得依赖 novel 包")
    void downloadPackageDoesNotDependOnNovelPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.download..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("download-workbench 外置后不得重新引入 download -> novel 编译依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("schedule 宿主不得依赖 novel 插件包")
    void scheduleDoesNotDependOnNovelPlugin() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务宿主只能经 plugin-api ScheduledWorkExecutor 契约派发小说作品，不得 import novel 包")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台计划任务来源 / 执行器不得依赖 novel 包")
    void downloadScheduleSourcesAndRunnerDoNotDependOnNovel() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.download.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务来源 / 插画执行器经 PixivFetchService + 中性载体 + 核心执行契约工作")
                .check(CLASSES);
    }

    @Test
    @DisplayName("计划任务宿主 Bean 不得直连核心计划任务数据实现层")
    void scheduleEngineBeansMustNotAccessCoreScheduleImplDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "javax.sql..", "java.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("scheduled_tasks / scheduled_task_pending 是核心 owned schema，调度壳只能经 core.schedule 语义 Store/API")
                .check(CLASSES);
    }

    @Test
    @DisplayName("插件托管业务 Bean 不得直连数据库底层")
    void pluginManagedBeansMustNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().areAnnotatedWith(top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..",
                        "javax.sql..", "java.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("插件托管 Bean 对核心数据的访问必须经核心语义 Store/API，不得绕过到 JDBC / MyBatis / 核心 DB 实现层")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载队列控制器不得直接依赖具体作品类型下载服务")
    void downloadQueueControllerDoesNotDependOnConcreteDownloadServices() {
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.DownloadQueueController")
                .should().dependOnClassesThat(CONCRETE_DOWNLOAD_SERVICE)
                .because("下载队列控制器的跨类型取消 / 清空只能经稳定 DownloadControlPlane")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台不得依赖宿主队列命令与扩展 registry 实现")
    void workbenchUsesStableDownloadControlPlane() {
        noClasses()
                .should().dependOnClassesThat(HOST_DOWNLOAD_CONTROL_IMPLEMENTATION)
                .because("descriptor 快照、currentness 与队列命令对象身份由宿主 DownloadControlPlane adapter 维护")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台脚本入口只消费稳定目录且不自行重复限流")
    void workbenchUsesStableUserscriptCatalog() {
        noClasses()
                .should().dependOnClassesThat(HOST_USERSCRIPT_IMPLEMENTATION)
                .because("脚本扫描、资源物化与游客 UUID 限流归宿主；工作台只消费 UserscriptCatalog")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台的 Pixiv 传输必须通过稳定宿主端口")
    void pixivTransfersUseStableHostPorts() {
        noClasses()
                .should().dependOnClassesThat(DIRECT_PIXIV_TRANSPORT_IMPLEMENTATION)
                .because("插件只拥有请求编排与 HTTP 投影，请求头、目标校验和具体客户端转换归宿主稳定端口")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台不得依赖宿主路径、配置、i18n、通知、FFmpeg、代理访问、setup 与访客守卫实现")
    void workbenchDoesNotDependOnHostBoundaryImplementations() {
        noClasses()
                .should().dependOnClassesThat(HOST_BOUNDARY_IMPLEMENTATION)
                .because("外置插件只能依赖稳定路径、设置、身份、i18n、通知、FFmpeg、代理访问端口与 WorkVisibilityService")
                .check(CLASSES);
    }

    @Test
    @DisplayName("计划来源与作品执行器必须 @PluginManagedBean")
    void scheduledSourceAndWorkExecutorsMustBePluginManaged() {
        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("来源执行器随贡献插件 publication 与 child context 生命周期归属，不得被根包扫描注册")
                .check(CLASSES);

        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("作品执行器随贡献插件 publication 与 child context 生命周期归属，不得被根包扫描注册")
                .check(CLASSES);
    }

    @Test
    @DisplayName("队列宿主操作适配器必须 @PluginManagedBean")
    void queueOperationsMustBePluginManaged() {
        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("队列操作适配器随贡献插件生命周期归属，不得被根包扫描注册")
                .check(CLASSES);
    }

    @Test
    @DisplayName("宿主策略必选的下载工作台不得用插件开关门控配置类或 Bean")
    void requiredWorkbenchBeansMustNotBeConditionalOnPluginEnabled() {
        assertThat(CLASSES.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(ConditionalOnPluginEnabled.class))
                .map(JavaClass::getName)
                .toList())
                .as("download-workbench 配置类由宿主 RequiredPluginPolicy 保证恒活动，不得读取原始插件开关门控")
                .isEmpty();
        assertThat(CLASSES.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(ConditionalOnPluginEnabled.class))
                .map(method -> method.getFullName())
                .toList())
                .as("download-workbench Bean 由宿主 RequiredPluginPolicy 保证恒活动，不得读取原始插件开关门控")
                .isEmpty();
    }

    @Test
    @DisplayName("外置模块类导入非空且覆盖关键工作台类型")
    void pluginClassImportIsNonEmpty() {
        assertThat(CLASSES)
                .extracting(javaClass -> javaClass.getName())
                .contains(
                        "top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin",
                        "top.sywyar.pixivdownload.download.controller.DownloadQueueController",
                        "top.sywyar.pixivdownload.download.schedule.source.executor.PixivUserNewScheduledSourceExecutor",
                        "top.sywyar.pixivdownload.download.schedule.work.PixivScheduledIllustWorkExecutor",
                        "top.sywyar.pixivdownload.schedule.ScheduleExecutor");
    }

    private static JavaClasses importPluginClasses() {
        Path classesDir = Path.of("target", "classes");
        if (!Files.isDirectory(classesDir)) {
            classesDir = Path.of("pixivdownload-plugin-download-workbench", "target", "classes");
        }
        assertThat(Files.isDirectory(classesDir))
                .as("download-workbench target/classes should exist before ArchUnit guards run")
                .isTrue();
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPath(classesDir);
    }

}
