package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private static final String[] HOST_PRIVATE_CLASS_RESOURCES = {
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class"
    };
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package\\s+([A-Za-z0-9_$.]+)\\s*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import\\s+(?:static\\s+)?([A-Za-z0-9_$.*]+)\\s*;");
    private static final Pattern QUALIFIED_NAME_SEPARATOR = Pattern.compile("\\s*\\.\\s*");
    private static final Pattern TOP_LEVEL_TYPE_DECLARATION = Pattern.compile(
            "(?<![\\p{Alnum}_$])(?:class|interface|enum|record)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern APP_ARTIFACT = Pattern.compile(
            "<artifactId>\\s*PixivDownload\\s*</artifactId>");
    private static final String APP_PACKAGE_PREFIX = "top.sywyar.pixivdownload.";
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
            appType("common.UuidUtils"),
            appType("config.RuntimeFiles"),
            appType("core.appconfig.DownloadConfig"),
            appType("core.appconfig.MultiModeConfig"),
            appType("core.notification.NotificationService"),
            appType("core.pixiv.PixivProxyAccessGuard"),
            appType("core.pixiv.PixivThumbnailFetchService"),
            appType("ffmpeg.FfmpegInstallation"),
            appType("ffmpeg.FfmpegLocator"),
            appType("i18n.WebI18nBundleRegistry"),
            appType("quota.UserQuotaService"),
            appType("setup.SetupService"),
            appType("setup.guest.GuestAccessGuard"));
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
                            appType("core.download.control."))
                            || className.startsWith(
                            appType("core.download.queue."))
                            || className.startsWith(
                            appType("plugin.registry.DownloadExtension"));
                }
            };
    private static final DescribedPredicate<JavaClass> HOST_SCHEDULE_CAPABILITY_IMPLEMENTATION =
            new DescribedPredicate<>("host schedule capability implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return javaClass.getFullName().startsWith(
                            appType("core.schedule.capability."));
                }
            };
    private static final DescribedPredicate<JavaClass> HOST_USERSCRIPT_IMPLEMENTATION =
            new DescribedPredicate<>("host userscript implementation or visitor rate limiter") {
                @Override
                public boolean test(JavaClass javaClass) {
                    String className = javaClass.getFullName();
                    return className.equals(appType("quota.RateLimitService"))
                            || className.startsWith(appType("scripts.ScriptRegistry"))
                            || className.startsWith(appType("scripts.ScriptResource"))
                            || className.startsWith(appType("scripts.UserscriptRegistry"));
                }
            };
    private static final Set<String> DIRECT_PIXIV_TRANSPORT_IMPLEMENTATIONS = Set.of(
            "org.springframework.http.client.ClientHttpResponse",
            "org.springframework.web.client.RestTemplate",
            appType("common.PixivRequestHeaders"));
    private static final DescribedPredicate<JavaClass> DIRECT_PIXIV_TRANSPORT_IMPLEMENTATION =
            new DescribedPredicate<>("direct Pixiv transport implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return DIRECT_PIXIV_TRANSPORT_IMPLEMENTATIONS.contains(javaClass.getFullName());
                }
            };
    private static final DescribedPredicate<JavaClass> HOST_LIFECYCLE_IMPLEMENTATION =
            new DescribedPredicate<>("host plugin stream or runtime-task implementation") {
                @Override
                public boolean test(JavaClass javaClass) {
                    String className = javaClass.getFullName();
                    return className.equals(
                            appType("plugin.lifecycle.PluginStream"))
                            || className.equals(
                            appType("plugin.lifecycle.PluginStreamRegistry"))
                            || className.startsWith(
                            "top.sywyar.pixivdownload.plugin.runtime.stream.")
                            || className.startsWith(
                            "top.sywyar.pixivdownload.plugin.runtime.task.");
                }
            };

    private enum SourceState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHARACTER
    }

    @Test
    @DisplayName("下载工作台 POM 不得依赖 PixivDownload app artifact")
    void workbenchPomDoesNotDependOnAppArtifact() {
        String pom = read(repositoryRoot().resolve(
                "pixivdownload-plugin-download-workbench/pom.xml"));

        assertThat(APP_ARTIFACT.matcher(pom).find()).isFalse();
    }

    @Test
    @DisplayName("下载工作台不得依赖宿主私有 HTTP 类型")
    void workbenchDoesNotDependOnPrivateHttpTypes() {
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.schedule..",
                        "top.sywyar.pixivdownload.scripts..",
                        "top.sywyar.pixivdownload.plugin")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.apache.hc..", "org.apache.http..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.http.client."
                                + "HttpComponentsClientHttpRequestFactory")
                .because("插件只能经稳定 HTTP 契约消费宿主传输能力")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载工作台测试类路径不得包含 app 与宿主私有 HTTP 实现")
    void workbenchClasspathExcludesHostApplicationAndPrivateHttpStack() {
        ClassLoader classLoader = getClass().getClassLoader();
        for (String resource : HOST_PRIVATE_CLASS_RESOURCES) {
            assertThat(classLoader.getResource(resource)).as(resource).isNull();
        }
    }

    @Test
    @DisplayName("下载工作台生产与测试源码不得引用 app owned 类型")
    void workbenchSourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path root = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(root);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes)
                .as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        assertThat(appTypes)
                .as("同一源码文件中的 package-private 顶层类型也必须纳入 app owned 集合")
                .contains(appType("gui.panel.StatusPanelThemeOption"));
        collectAppTypeReferences(root, "src/main/java", appTypes, violations);
        collectAppTypeReferences(root, "src/test/java", appTypes, violations);

        assertThat(violations)
                .as("download-workbench must compile and test only against stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("app FQN 扫描忽略注释并保留普通字符串与文本块字符串")
    void appTypeReferenceScannerIgnoresCommentsAndPreservesStringLiterals() {
        String appType = "example.host.AppOwnedType";
        String commentsOnly = "// example.host.AppOwnedType\n"
                + "/* example.host.AppOwnedType */\n";
        String ordinaryString = "String name = \"// example.host.AppOwnedType\";";
        String nestedClassString = "Class.forName(\"example.host.AppOwnedType$Nested\");";
        String textBlockString = "String name = \"\"\"\n"
                + "/* example.host.AppOwnedType */\n"
                + "\"\"\";";
        String spacedImport = "import\n example /* owner */ . host . AppOwnedType\n;";
        String staticImport = "import static example.\n host . AppOwnedType . member;";
        String staticWildcardImport = "import static example . host . AppOwnedType . *;";
        String wildcardImport = "import example . host . *;";
        String inlineReference = "Object type = example /* owner */ . host . AppOwnedType.class;";
        String spacedString = "String label = \"example . host . AppOwnedType\";";
        String samePackageReference = "package\n example /* owner */ . host;\n"
                + "class Probe { AppOwnedType value; }";
        String otherPackageReference = "package example.other;\n"
                + "class Probe { AppOwnedType value; }";
        String topLevelTypes = "package example.host;\n"
                + "class Primary { class Nested {} }\n"
                + "record Secondary(int value) {}\n"
                + "@interface Marker {}";

        assertThat(referencesFullyQualifiedType(referenceCode(commentsOnly), appType))
                .isFalse();
        assertThat(referencesFullyQualifiedType(referenceCode(ordinaryString), appType))
                .isTrue();
        assertThat(referencesFullyQualifiedType(referenceCode(nestedClassString), appType))
                .isTrue();
        assertThat(referencesFullyQualifiedType(referenceCode(textBlockString), appType))
                .isTrue();
        assertThat(importsType(Set.of("example.host.*"), appType)).isTrue();
        assertThat(importsType(Set.of("example.other.*"), appType)).isFalse();
        assertThat(importsType(
                importedNames(normalizedDeclarationCode(spacedImport)),
                appType)).isTrue();
        assertThat(importsType(
                importedNames(normalizedDeclarationCode(staticImport)),
                appType)).isTrue();
        assertThat(importsType(
                importedNames(normalizedDeclarationCode(staticWildcardImport)),
                appType)).isTrue();
        assertThat(importsType(
                importedNames(normalizedDeclarationCode(wildcardImport)),
                appType)).isTrue();
        assertThat(referencesFullyQualifiedType(
                referenceCode(inlineReference),
                appType)).isTrue();
        assertThat(referencesFullyQualifiedType(referenceCode(spacedString), appType)).isFalse();
        String samePackageCode = referenceCode(samePackageReference);
        assertThat(samePackageSimpleReference(
                samePackageCode,
                packageName(normalizedDeclarationCode(samePackageReference)),
                appType)).isTrue();
        String otherPackageCode = referenceCode(otherPackageReference);
        assertThat(samePackageSimpleReference(
                otherPackageCode,
                packageName(normalizedDeclarationCode(otherPackageReference)),
                appType)).isFalse();
        assertThat(referencesFullyQualifiedType(
                "prefixexample.host.AppOwnedType",
                appType)).isFalse();
        assertThat(referencesFullyQualifiedType(
                "example.host.AppOwnedTypeSuffix",
                appType)).isFalse();
        assertThat(topLevelTypeNames(topLevelTypes))
                .containsExactly("Primary", "Secondary", "Marker");
    }

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
    @DisplayName("计划任务宿主只通过 plugin-api 稳定端口访问能力")
    void scheduleHostUsesStableCapabilityAccess() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.schedule..")
                .should().dependOnClassesThat(HOST_SCHEDULE_CAPABILITY_IMPLEMENTATION)
                .because("外置工作台不得依赖宿主 ScheduleCapabilityRegistry、publication、lease 或 owner 实现")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.schedule.ScheduleHostPluginConfiguration")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess")
                .because("child context 必须显式从父 context 注入稳定计划能力访问端口")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess")
                .because("执行引擎必须通过稳定端口取得 planning 与 execution lease")
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
    @DisplayName("下载工作台 SSE 只通过 owner-scoped 稳定端口登记推流与后台任务")
    void workbenchUsesStableStreamAndRuntimeTaskRegistrars() {
        noClasses()
                .should().dependOnClassesThat(HOST_LIFECYCLE_IMPLEMENTATION)
                .because("插件只取得由宿主盖章 owner 的稳定 registrar，不得依赖 app/runtime 生命周期实现")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.SSEController")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar")
                .because("SSE 生命周期注册必须经 plugin-api 的 owner-scoped 稳定端口")
                .check(CLASSES);

        classes()
                .that().haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.download.controller.SSEController")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar")
                .because("heartbeat 与进度任务必须由宿主 owner-scoped 包装器承载并参与 quiesce drain")
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
                        "top.sywyar.pixivdownload.schedule.ScheduleExecutor",
                        "top.sywyar.pixivdownload.schedule.ScheduleHostPluginConfiguration",
                        "top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine");
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

    private static void collectAppTypeReferences(Path root,
                                                 String sourcePath,
                                                 Set<String> appTypes,
                                                 List<String> violations) throws IOException {
        Path sourceRoot = root.resolve("pixivdownload-plugin-download-workbench")
                .resolve(sourcePath);
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String sourceCode = read(source);
                String references = referenceCode(sourceCode);
                String declarations = normalizedDeclarationCode(sourceCode);
                String packageName = packageName(declarations);
                Set<String> imports = importedNames(declarations);
                for (String appType : appTypes) {
                    if (referencesFullyQualifiedType(references, appType)
                            || importsType(imports, appType)
                            || samePackageSimpleReference(
                            references, packageName, appType)) {
                        violations.add(root.relativize(source) + " -> " + appType);
                    }
                }
            }
        }
    }

    private static boolean referencesFullyQualifiedType(String code, String appType) {
        return identifierReference(appType).matcher(code).find();
    }

    private static boolean samePackageSimpleReference(String code,
                                                      String packageName,
                                                      String appType) {
        int separator = appType.lastIndexOf('.');
        if (separator < 0 || !appType.substring(0, separator).equals(packageName)) {
            return false;
        }
        String simpleName = appType.substring(separator + 1);
        return identifierReference(simpleName).matcher(code).find();
    }

    private static Set<String> importedNames(String code) {
        Set<String> imports = new LinkedHashSet<>();
        Matcher matcher = IMPORT_DECLARATION.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    private static boolean importsType(Set<String> imports, String appType) {
        int separator = appType.lastIndexOf('.');
        if (separator < 0) {
            return false;
        }
        String wildcardImport = appType.substring(0, separator) + ".*";
        return imports.stream().anyMatch(importName -> importName.equals(appType)
                || importName.equals(wildcardImport)
                || importName.startsWith(appType + "."));
    }

    private static Pattern identifierReference(String identifier) {
        return Pattern.compile("(?<![\\p{Alnum}_$])" + Pattern.quote(identifier)
                + "(?![\\p{Alnum}_])");
    }

    private static String packageName(String code) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalizedDeclarationCode(String source) {
        return QUALIFIED_NAME_SEPARATOR.matcher(maskCommentsAndLiterals(source))
                .replaceAll(".");
    }

    private static String referenceCode(String source) {
        return stripComments(source) + "\n" + normalizedDeclarationCode(source);
    }

    private static Set<String> appOwnedTypes(Path root) throws IOException {
        Path appSourceRoot = root.resolve("pixivdownload-app/src/main/java");
        Set<String> types = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(appSourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java")
                            && !path.getFileName().toString().equals("module-info.java"))
                    .sorted()
                    .toList()) {
                String sourceCode = read(source);
                String declarationCode = normalizedDeclarationCode(sourceCode);
                String packageName = packageName(declarationCode);
                Set<String> sourceTypes = topLevelTypeNames(sourceCode);
                String primaryType = source.getFileName().toString()
                        .replaceFirst("\\.java$", "");
                if (packageName.isBlank() || !sourceTypes.contains(primaryType)) {
                    throw new IllegalStateException(
                            "Cannot derive primary app type from " + root.relativize(source));
                }
                for (String sourceType : sourceTypes) {
                    String appType = packageName + "." + sourceType;
                    if (!types.add(appType)) {
                        throw new IllegalStateException(
                                "Duplicate app production type " + appType);
                    }
                }
            }
        }
        return Set.copyOf(types);
    }

    private static Set<String> topLevelTypeNames(String source) {
        String code = maskCommentsAndLiterals(source);
        Set<String> types = new LinkedHashSet<>();
        Matcher matcher = TOP_LEVEL_TYPE_DECLARATION.matcher(code);
        int cursor = 0;
        int braceDepth = 0;
        while (matcher.find()) {
            braceDepth = updateBraceDepth(code, cursor, matcher.start(), braceDepth);
            if (braceDepth == 0) {
                types.add(matcher.group(1));
            }
            cursor = matcher.end();
        }
        return types;
    }

    private static int updateBraceDepth(String code,
                                        int start,
                                        int end,
                                        int initialDepth) {
        int depth = initialDepth;
        for (int index = start; index < end; index++) {
            char value = code.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth < 0) {
                    throw new IllegalStateException("Unbalanced app source braces");
                }
            }
        }
        return depth;
    }

    private static String stripComments(String source) {
        return sanitizeSource(source, true);
    }

    private static String maskCommentsAndLiterals(String source) {
        return sanitizeSource(source, false);
    }

    private static String sanitizeSource(String source, boolean preserveLiterals) {
        StringBuilder sanitized = new StringBuilder(source.length());
        SourceState state = SourceState.CODE;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            switch (state) {
                case CODE -> {
                    if (current == '/' && index + 1 < source.length()) {
                        char next = source.charAt(index + 1);
                        if (next == '/' || next == '*') {
                            appendMasked(sanitized, current);
                            appendMasked(sanitized, next);
                            index += 2;
                            state = next == '/'
                                    ? SourceState.LINE_COMMENT
                                    : SourceState.BLOCK_COMMENT;
                            continue;
                        }
                    }
                    if (startsWithTripleQuote(source, index)) {
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        index += 3;
                        state = SourceState.TEXT_BLOCK;
                        continue;
                    }
                    if (current == '"' || current == '\'') {
                        appendLiteral(sanitized, current, preserveLiterals);
                    } else {
                        sanitized.append(current);
                    }
                    index++;
                    if (current == '"') {
                        state = SourceState.STRING;
                    } else if (current == '\'') {
                        state = SourceState.CHARACTER;
                    }
                }
                case LINE_COMMENT -> {
                    appendMasked(sanitized, current);
                    index++;
                    if (current == '\n' || current == '\r') {
                        state = SourceState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && index + 1 < source.length()
                            && source.charAt(index + 1) == '/') {
                        appendMasked(sanitized, current);
                        appendMasked(sanitized, '/');
                        index += 2;
                        state = SourceState.CODE;
                    } else {
                        appendMasked(sanitized, current);
                        index++;
                    }
                }
                case STRING -> {
                    appendLiteral(sanitized, current, preserveLiterals);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        appendLiteral(
                                sanitized, source.charAt(index++), preserveLiterals);
                    } else if (current == '"') {
                        state = SourceState.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (startsWithTripleQuote(source, index)) {
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        appendLiteral(sanitized, '"', preserveLiterals);
                        index += 3;
                        state = SourceState.CODE;
                    } else {
                        appendLiteral(sanitized, current, preserveLiterals);
                        index++;
                        if (current == '\\' && index < source.length()) {
                            appendLiteral(
                                    sanitized,
                                    source.charAt(index++),
                                    preserveLiterals);
                        }
                    }
                }
                case CHARACTER -> {
                    appendLiteral(sanitized, current, preserveLiterals);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        appendLiteral(
                                sanitized, source.charAt(index++), preserveLiterals);
                    } else if (current == '\'') {
                        state = SourceState.CODE;
                    }
                }
            }
        }
        return sanitized.toString();
    }

    private static void appendLiteral(StringBuilder output,
                                      char value,
                                      boolean preserveLiterals) {
        if (preserveLiterals) {
            output.append(value);
        } else {
            appendMasked(output, value);
        }
    }

    private static boolean startsWithTripleQuote(String source, int index) {
        return index + 2 < source.length()
                && source.charAt(index) == '"'
                && source.charAt(index + 1) == '"'
                && source.charAt(index + 2) == '"';
    }

    private static void appendMasked(StringBuilder output, char value) {
        output.append(value == '\n' || value == '\r' ? value : ' ');
    }

    private static String appType(String relativeName) {
        return APP_PACKAGE_PREFIX + relativeName;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    "pixivdownload-official-plugins/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read " + path, failure);
        }
    }
}
