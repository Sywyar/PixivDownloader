package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** 共享契约、宿主与外置工作台的源码及字节码依赖边界。 */
class PluginApiDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(location -> !location.asURI().toString()
                    .contains("pixivdownload-plugin-gui-swing"))
            .importPackages("top.sywyar.pixivdownload");
    private static final String DOWNLOAD_WORKBENCH_CLASSES_PROPERTY = "download-workbench.plugin.classes";
    private static final Set<String> BUILT_IN_CORE_PLUGIN_IDS = BuiltInPlugins.createAll().stream()
            .filter(plugin -> plugin.kind() == PluginKind.CORE)
            .map(PixivFeaturePlugin::id)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 业务插件禁止直接调用的 {@code java.nio.file.Files} 读取 / 打开类静态方法。普通作品文件的枚举 / 读取是
     * 核心本地资产能力，只能经 {@code WorkAssetService}；sidecar 没有对插件发布读取面，业务插件也不得自行解析。
     */
    private static final Set<String> FORBIDDEN_FILE_READ_METHODS = Set.of(
            "readString", "readAllBytes", "readAllLines", "lines",
            "newBufferedReader", "newInputStream");

    /** target owner 是 {@code java.nio.file.Files} 且方法名落在 {@link #FORBIDDEN_FILE_READ_METHODS} 内的方法调用。 */
    private static final DescribedPredicate<JavaMethodCall> READS_LOCAL_FILES_DIRECTLY =
            new DescribedPredicate<>("调用 java.nio.file.Files 的本地文件读取 / 打开方法") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().getFullName().equals("java.nio.file.Files")
                            && FORBIDDEN_FILE_READ_METHODS.contains(call.getName());
                }
            };

    @Test
    @DisplayName("plugin.api 必须自包含：只依赖 JDK、jakarta.servlet 与 plugin.api 自身")
    void pluginApiIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..", "javax.sql..",
                        "jakarta.servlet..")
                .because("plugin.api 是跨插件边界共享的契约包，不得依赖任何业务包或框架；"
                        + "javax.sql 是 JDK 标准 API、jakarta.servlet 是 Servlet 规范 API，"
                        + "两者都仅允许精确服务接口使用（见下一条规则）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin.api 仅请求 owner 与插件数据源接口可依赖对应规范 API，其余契约保持纯 JDK")
    void pluginApiDataTypesStayPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api..")
                .and().doNotHaveFullyQualifiedName(
                        top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver.class.getName())
                .and().doNotHaveFullyQualifiedName(
                        top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource.class.getName())
                .and().doNotHaveFullyQualifiedName(
                        top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase.class.getName())
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..")
                .because("jakarta.servlet 仅限 RequestOwnerIdentityResolver，javax.sql 仅限 owner 存储接口，"
                        + "纯数据的 contribution / record / 事件类型必须保持零依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin.api 不得重新拥有核心作品查询、元数据、资产、删除或可见性契约")
    void pluginApiDoesNotOwnCoreWorkContracts() {
        assertThat(CLASSES.contain("top.sywyar.pixivdownload.core.work.model.WorkMetadata"))
                .as("核心作品契约必须实际出现在 ArchUnit 导入结果中，避免所有权守卫空跑")
                .isTrue();

        assertThat(CLASSES.stream()
                .filter(javaClass -> javaClass.getPackageName()
                        .startsWith("top.sywyar.pixivdownload.plugin.api.work"))
                .map(JavaClass::getName)
                .sorted()
                .toList())
                .as("完整作品语义由 core-api 长期拥有，plugin-api classpath 不得残留旧契约")
                .isEmpty();
    }

    @Test
    @DisplayName("plugin.api 队列运行时只能依赖纯 JDK 与队列契约自身")
    void pluginApiQueueRuntimeIsPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api.download.queue..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api.download.queue..", "java..")
                .because("队列 tracker / generation drain / 拒绝异常跨宿主与外置插件共享，"
                        + "不得耦合 Spring、HTTP 或 app 的本地化异常层")
                .check(CLASSES);

        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin.api.gui 契约只能依赖 JDK 与 plugin-api 自身")
    void pluginApiGuiThemeContractIsPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api.gui..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..")
                .because("GUI contribution 是跨边界契约：只能依赖 JDK 与 plugin-api 自身，"
                        + "不得引入 PF4J / Spring / FlatLaf / JNA 或 app 业务类型")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin.api.schedule 契约只能依赖 JDK 与 schedule 契约自身")
    void pluginApiScheduleContractIsPureJdk() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api.schedule..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api.schedule..", "java..")
                .because("计划来源、作品、凭证、Guard 与网络路由必须跨插件 classloader 稳定，"
                        + "不得依赖 Servlet、Spring、Jackson、PF4J 或 app 业务类型")
                .check(CLASSES);
    }

    @Test
    @DisplayName("app 生产代码不得 import PF4J 类型")
    void appProductionCodeDoesNotImportPf4j() throws IOException {
        Path sourceRoot = Path.of("pixivdownload-app/src/main/java");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of("src/main/java");
        }
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "import org.pf4j"))
                    .map(Path::toString)
                    .toList())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("下载工作台不得依赖小说插件实现")
    void downloadPackageDoesNotDependOnNovelPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.download..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("小说端点与响应投影归小说插件，下载工作台只消费共享契约")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("导入的 novel 类不得依赖下载工作台实现")
    void novelPackageDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novel..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("小说与下载工作台通过共享契约协作，不相互依赖实现包")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("core 包不得依赖 download 包：Hash 与本地资产定位只经核心资产包")
    void corePackageDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.core..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("ArtworkFileLocator / StagedFileDeletion / sidecar 捕获已迁入核心资产与 metadata；"
                        + "core 层不得依赖外置下载工作台的实现包")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 stats 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnStatsPlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.stats..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.stats..")
                .because("stats 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 duplicate 外置插件包")
    void coreDoesNotDependOnDuplicatePlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.duplicate..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .because("重复检测由外置插件贡献，下载后的 Hash 索引由核心 ArtworkHashService 承担")
                .check(CLASSES);
    }

    @Test
    @DisplayName("app 生产代码与 POM 不得依赖 gallery-tools 外置插件模块")
    void appDoesNotDependOnGalleryToolsPluginModule() throws IOException {
        Path pom = Path.of("pixivdownload-app/pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("pom.xml");
        }
        assertThat(Files.readString(pom, StandardCharsets.UTF_8))
                .doesNotContain("<artifactId>pixivdownload-plugin-gallery-tools</artifactId>");

        Path sourceRoot = Path.of("pixivdownload-app/src/main/java");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of("src/main/java");
        }
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "top.sywyar.pixivdownload.gallerytools")
                            || contains(path, "top.sywyar.pixivdownload.stats")
                            || contains(path, "top.sywyar.pixivdownload.duplicate"))
                    .map(Path::toString)
                    .toList())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("app 生产代码与 POM 不得依赖 gallery / novel 外置插件模块")
    void appDoesNotDependOnGalleryPluginModule() throws IOException {
        Path pom = Path.of("pixivdownload-app/pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("pom.xml");
        }
        assertThat(Files.readString(pom, StandardCharsets.UTF_8))
                .doesNotContain("<artifactId>pixivdownload-plugin-gallery</artifactId>")
                .doesNotContain("<artifactId>pixivdownload-plugin-novel</artifactId>");

        Path sourceRoot = Path.of("pixivdownload-app/src/main/java");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of("src/main/java");
        }
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "top.sywyar.pixivdownload.gallery")
                            || contains(path, "top.sywyar.pixivdownload.novelgallery")
                            || contains(path, "top.sywyar.pixivdownload.novel."))
                    .map(Path::toString)
                    .toList())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("app 生产代码与 POM 不得依赖 douyin 外置插件模块")
    void appDoesNotDependOnDouyinPluginModule() throws IOException {
        Path pom = Path.of("pixivdownload-app/pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("pom.xml");
        }
        assertThat(Files.readString(pom, StandardCharsets.UTF_8))
                .doesNotContain("<artifactId>pixivdownload-plugin-douyin</artifactId>");

        Path sourceRoot = Path.of("pixivdownload-app/src/main/java");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of("src/main/java");
        }
        try (var paths = Files.walk(sourceRoot)) {
            assertThat(paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "top.sywyar.pixivdownload.douyin"))
                    .map(Path::toString)
                    .toList())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("核心 Hash 写入服务 ArtworkHashService 是核心 Bean：不得标 @PluginManagedBean")
    void artworkHashServiceIsCoreNotPluginManaged() {
        classes()
                .that().haveFullyQualifiedName("top.sywyar.pixivdownload.core.hash.ArtworkHashService")
                .should().notBeAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .because("下载后即时算 Hash 是核心资产索引链路，须由根包扫描装配的核心服务承载、"
                        + "不属任何功能插件、不随 plugins.<id>.enabled 缺席；标 @PluginManagedBean 会把它退回"
                        + "插件托管、重新引入『禁用 duplicate 却仍有插件托管 Bean 常驻』的归属歧义")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 gallery 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnGalleryPlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.gallery..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .because("gallery 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "插画 SQL 仓库（GalleryRepository）已收编进核心"
                        + "数据层 core.metadata，BuiltInPlugins 是既定的组合根例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 novel.db 包（novel 包内、tts.narration 与组合根 CorePlugin 除外）")
    void coreDoesNotDependOnNovelDbPackage() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.novel..")
                .and().resideOutsideOfPackage("top.sywyar.pixivdownload.tts.narration..")
                .and().doNotHaveFullyQualifiedName("top.sywyar.pixivdownload.plugin.CorePlugin")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel.db..")
                .because("小说查询数据归核心 metadata；本规则保留 novel、tts.narration 与 CorePlugin 的既有豁免")
                .check(CLASSES);
    }

    @Test
    @DisplayName("业务插件不得依赖 meta sidecar 捕获与存储实现")
    void businessPluginsMustNotDependOnMetaSidecarImplementation() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat()
                .belongToAnyOf(
                        top.sywyar.pixivdownload.core.metadata.WorkMetadataStore.class,
                        top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator.class,
                        top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService.class,
                        top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta.class)
                .because("作品 meta sidecar 的捕获、写入与格式演进是核心内部实现；当前没有真实插件读取"
                        + "消费者，因此不预置解析 DTO 或服务方法，业务插件不得直依赖核心实现层或自行解析 "
                        + "{workId}.meta.json")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("业务插件不得直接调用 java.nio.file.Files 读本地文件：作品文件枚举 / 读取只能经 WorkAssetService")
    void businessPluginsMustNotReadFilesDirectly() {
        // 字节码级补强：上一条只能拦「依赖 sidecar 实现类」，拦不到业务插件手写
        // Files.readString(dir.resolve(id + ".meta.json")) 这类自行解析内部文件格式的回潮。
        // Spring AOP 拦不到 java.nio.file.Files 的 static 方法、也只覆盖跑过的路径，故用 ArchUnit 静态守卫。
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().callMethodWhere(READS_LOCAL_FILES_DIRECTLY)
                .because("普通作品文件的枚举 / 读取只能经 WorkAssetService；sidecar 当前没有对插件发布读取面，"
                        + "业务插件不得直接调用 java.nio.file.Files 读本地文件，更不得自行拼 {workId}.meta.json "
                        + "解析核心内部格式")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心本地资产 serving（core.asset）不得依赖 download 包：图片字节只能经 WorkAssetService 出")
    void coreAssetServingDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.core.asset..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("本地作品文件由 core-api 的 WorkAssetService 提供，不随下载工作台禁用而缺席")
                .check(CLASSES);
    }

    @Test
    @DisplayName("计划任务宿主 schedule 引擎 Bean 不得依赖核心计划任务数据实现层：只能经 core.schedule 语义 Store/API 读写")
    void scheduleEngineBeansMustNotAccessCoreScheduleImplDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "javax.sql..", "java.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..")
                .because("计划事实归核心 schema；工作台 schedule 引擎通过 ScheduledTaskStore 访问，不能绕过语义接口写表")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("schedule 宿主不得依赖 novel 插件包：小说执行只经 plugin-api ScheduledWorkExecutor")
    void scheduleDoesNotDependOnNovelPlugin() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务的小说下载 / 系列合订 / 翻译状态经 plugin-api ScheduledWorkExecutor"
                        + "（小说插件贡献执行器实现）完成，调度编排层不得 import 任何 novel 包类型；小说插件经 plugin→plugin-api 正向"
                        + "依赖实现该契约，发现 / 筛选 / 系列补全 / 共享调度机器仍留调度壳")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("计划来源与作品执行器必须 @PluginManagedBean（不得根包扫描）")
    void scheduledSourceAndWorkExecutorsMustBePluginManaged() {
        JavaClasses workbenchClasses = importDownloadWorkbenchClasses();

        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("来源执行器随贡献插件的 publication 与 child context 生命周期归属，"
                        + "不得由宿主根包扫描注册")
                .check(workbenchClasses);

        classes()
                .that().areAssignableTo(
                        top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(
                        top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("作品执行器随贡献插件的 publication 与 child context 生命周期归属，"
                        + "不得由宿主根包扫描注册")
                .check(workbenchClasses);
    }

    @Test
    @DisplayName("下载工作台计划任务来源 / 执行器不得依赖 novel 包：发现与下载只经核心契约 + 中性载体")
    void downloadScheduleSourcesAndRunnerDoNotDependOnNovel() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.download.schedule..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.novel..")
                .because("计划任务来源 / 插画执行器经 PixivFetchService + 中性载体 + plugin-api ScheduledWorkExecutor "
                        + "工作，不得 import 任何 novel 包类型；小说下载由小说插件贡献的执行器按 work type 解析完成")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("插件托管业务 Bean 不得直连数据库底层：JDBC / MyBatis 与核心 DB 实现层只能经核心语义 Store/API")
    void pluginManagedBeansMustNotAccessRawDatabaseDirectly() {
        // 这里只约束已导入的 @PluginManagedBean；无此注解的核心存储实现不在本规则检查面。
        noClasses()
                .that().areAnnotatedWith(top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean.class)
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAnyPackage(
                                        "java.sql..", "javax.sql..",
                                        "org.springframework.jdbc..",
                                        "org.apache.ibatis..",
                                        "top.sywyar.pixivdownload.core.schedule.db..",
                                        "top.sywyar.pixivdownload.core.stats.db..")
                                .or(JavaClass.Predicates.belongToAnyOf(
                                        top.sywyar.pixivdownload.core.db.PixivMapper.class,
                                        top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper.class)))
                .because("插件托管业务 Bean 对核心表的访问必须经核心语义 Store/API（ScheduledTaskStore / "
                        + "StatsQueryStore 等），不得自建 JdbcTemplate / 注入池化 DataSource / 直依赖 MyBatis mapper "
                        + "或核心 DB 实现层做自由 SQL；核心实现层（@Repository，非 @PluginManagedBean）不受此约束")
                .check(CLASSES);
    }

    @Test
    @DisplayName("内置 CORE 的业务 Bean 不得标 @ConditionalOnPluginEnabled（method 级与 class 级都覆盖）")
    void builtInCorePluginBeansMustNotBeConditionalOnPluginEnabled() {
        // 条件注解只读开关；内置 CORE 的不可禁用性须同时约束工厂方法和整个配置类。
        ArchCondition<JavaMethod> methodNotGateBuiltInCore =
                new ArchCondition<>("@Bean 方法不得用 @ConditionalOnPluginEnabled 门控内置 CORE") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String pluginId = method.getAnnotationOfType(ConditionalOnPluginEnabled.class).value();
                        if (BUILT_IN_CORE_PLUGIN_IDS.contains(pluginId)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " 用 @ConditionalOnPluginEnabled(\"" + pluginId
                                            + "\") 门控了内置 CORE——核心 Bean 必须恒无条件装配"));
                        }
                    }
                };
        ArchCondition<JavaClass> typeNotGateBuiltInCore =
                new ArchCondition<>("@Configuration 类不得用 @ConditionalOnPluginEnabled 门控内置 CORE") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        String pluginId = clazz.getAnnotationOfType(ConditionalOnPluginEnabled.class).value();
                        if (BUILT_IN_CORE_PLUGIN_IDS.contains(pluginId)) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getFullName() + " 用 @ConditionalOnPluginEnabled(\"" + pluginId
                                            + "\") 门控了内置 CORE——核心托管 Bean 必须恒无条件装配"));
                        }
                    }
                };
        String because = "OnPluginEnabledCondition 只读原始开关且不回指组合根；内置 CORE 的 Bean 一律不标本注解"
                + "（method 级或 class 级都不行）、恒无条件装配";
        methods()
                .that().areAnnotatedWith(ConditionalOnPluginEnabled.class)
                .should(methodNotGateBuiltInCore)
                .because(because)
                .check(CLASSES);
        // @Target 含 TYPE，但当前生产无类级用法 → allowEmptyShould 放行空集（前向守卫）。
        classes()
                .that().areAnnotatedWith(ConditionalOnPluginEnabled.class)
                .should(typeNotGateBuiltInCore)
                .allowEmptyShould(true)
                .because(because)
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载队列控制器不得直接依赖具体作品类型下载服务：取消 / 清空只经稳定下载控制面")
    void downloadQueueControllerDoesNotDependOnConcreteDownloadServices() {
        // 此规则只约束跨类型取消/清空的队列控制器，不扩大到同包其它控制器。
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.DownloadQueueController")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.ArtworkDownloadExecutor")
                .because("下载队列控制器的跨类型取消 / 清空经稳定 DownloadControlPlane 多态派发，"
                        + "不得直接依赖插画下载执行器")
                .check(importDownloadWorkbenchClasses());
        noClasses()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.DownloadQueueController")
                .should().dependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.novel.download..")
                .because("下载队列控制器不得反向依赖小说下载实现；小说只经稳定队列契约贡献能力")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("下载工作台脚本入口不得依赖宿主扫描实现或重复调用游客限流器")
    void downloadWorkbenchUsesStableUserscriptCatalog() {
        DescribedPredicate<JavaClass> hostUserscriptImplementation =
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

        noClasses()
                .should().dependOnClassesThat(hostUserscriptImplementation)
                .because("工作台只消费 plugin-api UserscriptCatalog；扫描、物化与游客 UUID 限流归宿主")
                .check(importDownloadWorkbenchClasses());
    }

    @Test
    @DisplayName("宿主脚本物化实现不得编码下载工作台私有文件名或派生安装 id")
    void hostUserscriptMaterializerDoesNotOwnPluginScriptIdentity() throws IOException {
        Path source = Path.of(
                "pixivdownload-app/src/main/java/top/sywyar/pixivdownload/scripts/ScriptRegistry.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/top/sywyar/pixivdownload/scripts/ScriptRegistry.java");
        }

        assertThat(Files.readString(source, StandardCharsets.UTF_8))
                .doesNotContain(
                        "FILENAME_TO_ID",
                        "deriveId(",
                        "Pixiv All-in-One.user.js",
                        "Pixiv 单作品图片下载器(Java后端版).user.js",
                        "Pixiv 单作品图片下载器(Local Download).user.js",
                        "Pixiv User 批量下载器(User Batch).user.js",
                        "Pixiv 页面批量下载器(Page Scrape).user.js",
                        "Pixiv URL 批量导入单作品下载器(URL Batch).user.js",
                        "Pixiv 体验增强工具箱(Toolbox).user.js");
    }

    @Test
    @DisplayName("宿主生产 classpath 不得实现 QueueOperations：具体操作只归各官方外置插件")
    void hostProductionMustNotImplementQueueOperations() {
        // app/core-api/plugin-runtime 只承载队列契约、注册与调用设施，不拥有任何具体队列实现。下载工作台、novel、
        // duplicate 等模块各自由模块内守卫约束其实现必须 @PluginManagedBean；这里用非空的宿主生产类全集作为检查面，
        // 避免旧规则仅筛实现类后因 app 本就没有实现而 allowEmpty，形成无法证明任何边界的空守卫。
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().implement(
                        top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations.class)
                .because("QueueOperations 的具体实现属于贡献它的外置插件；宿主只保留 QueueOperationRegistry，"
                        + "不得重新拥有具体下载或扫描队列适配器")
                .check(CLASSES);
    }

    @Test
    @DisplayName("common 不得依赖业务包：仅允许基础设施包与共享 LocalRequestTrust")
    void commonDependsOnlyOnInfrastructure() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.common..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("top.sywyar.pixivdownload..")
                                .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(
                                        "top.sywyar.pixivdownload.common..",
                                        "top.sywyar.pixivdownload.config..",
                                        "top.sywyar.pixivdownload.i18n..")
                                        .or(JavaClass.Predicates.belongToAnyOf(LocalRequestTrust.class)))))
                .because("common 是叶子工具包，只允许依赖 config / i18n 基础设施包；"
                        + "NetworkUtils 可精确委托 core-api 中纯 JDK 的 LocalRequestTrust，"
                        + "不得借此宽放 web 包或其它业务类型")
                .check(CLASSES);
    }

    @Test
    @DisplayName("quota 包不得依赖 download 包：配额打包排除 sidecar 经中性 WorkSidecarFiles 判定")
    void quotaDoesNotDependOnDownloadPackage() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.quota..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.download..")
                .because("quota 是配额管理包，配额打包排除 *.meta.json 经核心中性类 "
                        + "core.metadata.sidecar.WorkSidecarFiles 判定，不得反向依赖 download 包（消除 "
                        + "download ↔ quota 循环回潮）")
                .check(CLASSES);
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }

    private static JavaClasses importDownloadWorkbenchClasses() {
        String configured = System.getProperty(DOWNLOAD_WORKBENCH_CLASSES_PROPERTY);
        Path classesDir = configured == null || configured.isBlank()
                ? Path.of("..", "pixivdownload-plugin-download-workbench", "target", "classes")
                : Path.of(configured);
        Assumptions.assumeTrue(Files.isDirectory(classesDir),
                () -> "download-workbench 外置插件 classes 目录不存在，跳过 app 侧外置类守卫；"
                        + "外置模块自身 DownloadWorkbenchDependencyGuardTest 会在该模块测试阶段覆盖同等约束。"
                        + "（系统属性 " + DOWNLOAD_WORKBENCH_CLASSES_PROPERTY + "="
                        + classesDir.toAbsolutePath().normalize() + "）");
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPath(classesDir);
    }
}
