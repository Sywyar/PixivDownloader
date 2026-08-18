package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("官方外置插件宿主实现依赖边界")
class OfficialPluginHostBoundaryGuardTest {

    private static final Pattern OFFICIAL_PLUGIN_ARTIFACT = Pattern.compile(
            "pixivdownload-plugin-[a-z0-9-]+");
    private static final Pattern PRIVATE_HTTP_ARTIFACT = Pattern.compile(
            "(?i)(?:httpclient|httpcore|httpasyncclient).*");
    private static final String PRIVATE_HTTP_GROUP_PREFIX = "org.apache.httpcomponents";
    private static final Pattern CONFIG_YAML_REFERENCE = Pattern.compile("(?i)config\\.yaml");
    private static final Pattern APPROVED_NOVEL_CONFIG_MIGRATION = Pattern.compile(
            "旧\\s+\\{@code\\s+config\\.yaml\\}\\s+值迁入\\s+(?:\\*\\s*)?"
                    + "\\{@code\\s+config/plugins/novel\\.properties\\}");
    private static final String NOVEL_CONFIG_MIGRATION_SOURCE =
            "pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/config/"
                    + "NovelExecutionSettings.java";
    private static final List<String> PRODUCTION_TEXT_SUFFIXES = List.of(
            ".java",
            ".properties",
            ".yml",
            ".yaml",
            ".xml",
            ".json",
            ".html",
            ".js",
            ".css",
            ".md",
            ".txt");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package\\s+([A-Za-z0-9_$.]+)\\s*;");
    private static final Pattern PROJECT_TYPE_REFERENCE = Pattern.compile(
            "(?<![\\p{Alnum}_$])top\\.sywyar\\.pixivdownload"
                    + "(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import\\s+(?:static\\s+)?([A-Za-z0-9_$.*]+)\\s*;");
    private static final Pattern QUALIFIED_NAME_SEPARATOR = Pattern.compile("\\s*\\.\\s*");
    private static final Pattern TOP_LEVEL_TYPE_DECLARATION = Pattern.compile(
            "(?<![\\p{Alnum}_$])(?:class|interface|enum|record)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern FEATURE_PLUGIN_FACTORY = Pattern.compile(
            "(?s)featurePlugin\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+new\\s+([A-Za-z0-9_$.]+)\\s*\\(");
    private static final Pattern FEATURE_ID_RETURN = Pattern.compile(
            "(?s)String\\s+id\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+(?:\"([^\"]+)\"|([A-Z][A-Z0-9_]*))\\s*;");
    private static final List<String> NOVEL_EXECUTION_OWNER_TOKENS = List.of(
            "download.novel-max-concurrent",
            "download.novel-translate-max-concurrent",
            "getNovelMaxConcurrent",
            "getNovelTranslateMaxConcurrent",
            "novelDownloadTaskExecutor",
            "novelTranslateTaskExecutor");
    private static final List<String> PRIVATE_HOST_EXECUTOR_BEAN_NAMES = List.of(
            "applicationTaskExecutor",
            "downloadTaskExecutor");
    private static final List<String> PRIVATE_HOST_SCHEDULER_BEAN_NAMES = List.of(
            "taskScheduler");
    private static final Pattern EXECUTOR_INJECTION_TYPE = Pattern.compile(
            "(?<![\\p{Alnum}_$])(?:ThreadPoolTaskExecutor|TaskExecutor|Executor)"
                    + "(?![\\p{Alnum}_$])");
    private static final Pattern TASK_SCHEDULER_TYPE = Pattern.compile(
            "(?<![\\p{Alnum}_$])(?:ThreadPoolTaskScheduler|TaskScheduler)"
                    + "(?![\\p{Alnum}_$])");
    private static final Pattern BEAN_METHOD = Pattern.compile(
            "(?s)@Bean\\b\\s*(?:\\((.*?)\\))?\\s*"
                    + "(?:@[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*\\(.*?\\))?\\s*)*"
                    + "([^;{}()]*?)\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*"
                    + "\\((.*?)\\)\\s*\\{");
    private static final Pattern CALLABLE_DECLARATION = Pattern.compile(
            "(?s)(?:^|[;{}])\\s*"
                    + "(?:@[A-Za-z_$][A-Za-z0-9_$.]*"
                    + "(?:\\s*\\([^{};]*?\\))?\\s*)*"
                    + "(?:(?:public|protected|private|static|final|abstract|"
                    + "synchronized|native|strictfp|default)\\s+)*"
                    + "(?:<[^{};()]+>\\s+)?"
                    + "(?:(?:[A-Za-z_$][A-Za-z0-9_$.<>?\\[\\],]*\\s+))?"
                    + "[A-Za-z_$][A-Za-z0-9_$]*\\s*"
                    + "\\(([^{};]*)\\)\\s*"
                    + "(?:throws\\s+[^{};]+)?\\{");
    private static final Pattern INJECTION_ANNOTATION = Pattern.compile(
            "@(?:[A-Za-z_$][A-Za-z0-9_$.]*\\.)?"
                    + "(?:Autowired|Inject|Resource)\\b");
    private static final Pattern EXECUTOR_PARAMETER = Pattern.compile(
            "(?s)(?<![\\p{Alnum}_$])"
                    + "(?:@Qualifier\\s*\\(\\s*\"([^\"\\\\\\r\\n]+)\"\\s*\\)\\s*)?"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$.]*\\.)?"
                    + "(?:ThreadPoolTaskExecutor|TaskExecutor|Executor)"
                    + "\\s+[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern TASK_SCHEDULER_PARAMETER = Pattern.compile(
            "(?s)(?<![\\p{Alnum}_$])"
                    + "(?:@Qualifier\\s*\\(\\s*\"([^\"\\\\\\r\\n]+)\"\\s*\\)\\s*)?"
                    + "(?:[A-Za-z_$][A-Za-z0-9_$.]*\\.)?"
                    + "(?:ThreadPoolTaskScheduler|TaskScheduler)"
                    + "\\s+[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern ASYNC_ANNOTATION = Pattern.compile(
            "(?s)@Async\\b\\s*(?:\\((.*?)\\))?");
    private static final Pattern SCHEDULED_ANNOTATION = Pattern.compile(
            "(?s)@Scheduled\\b\\s*\\((.*?)\\)");
    private static final Pattern SCHEDULED_SCHEDULER_NAME = Pattern.compile(
            "(?s)(?:^|,)\\s*scheduler\\s*=\\s*\"([^\"\\\\\\r\\n]+)\"");
    private static final Pattern SINGLE_ANNOTATION_LITERAL = Pattern.compile(
            "(?s)^\\s*(?:value\\s*=\\s*)?\"([^\"\\\\\\r\\n]+)\"\\s*$");
    private static final Pattern BEAN_POSITIONAL_NAME = Pattern.compile(
            "(?s)^\\s*\"([^\"\\\\\\r\\n]+)\"(?:\\s*,|\\s*$)");
    private static final Pattern BEAN_NAMED_NAME = Pattern.compile(
            "(?s)(?:^|,)\\s*(?:name|value)\\s*=\\s*\"([^\"\\\\\\r\\n]+)\"");

    private static final List<String> CONCRETE_HOST_RUNTIME_TYPES = List.of(
            "top.sywyar.pixivdownload.common.NetworkUtils",
            "top.sywyar.pixivdownload.common.UuidUtils",
            "top.sywyar.pixivdownload.config.AppRuntimePathProvider",
            "top.sywyar.pixivdownload.config.DebugConfig",
            "top.sywyar.pixivdownload.config.ProxyConfig",
            "top.sywyar.pixivdownload.config.RuntimeFiles",
            "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
            "top.sywyar.pixivdownload.core.appconfig.MultiModeConfig",
            "top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain",
            "top.sywyar.pixivdownload.core.download.queue.QueueNotAcceptingException",
            "top.sywyar.pixivdownload.core.download.queue.QueueStatusRetention",
            "top.sywyar.pixivdownload.core.download.queue.QueueTaskTracker",
            "top.sywyar.pixivdownload.setup.HostRequestOwnerIdentityResolver",
            "top.sywyar.pixivdownload.setup.SetupService",
            "top.sywyar.pixivdownload.setup.guest.GuestAccessGuard",
            "top.sywyar.pixivdownload.setup.guest.GuestInviteSession",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeArgumentResolver",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityService",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityWebConfiguration");
    private static final List<String> PRIVATE_HTTP_TYPE_PREFIXES = List.of(
            "org.apache.hc.",
            "org.apache.http.",
            "org.springframework.http.client.HttpComponentsClientHttpRequestFactory",
            "java.net.http.HttpClient",
            "java.net.ProxySelector");

    private enum SourceState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHARACTER
    }

    @Test
    @DisplayName("官方插件必须通过共享契约读取配置、路径、身份、可见性与队列运行时")
    void officialPluginsUseSharedRuntimeContracts() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            Set<String> localTypes = ownedTypes(repositoryRoot, sourceRoot, module);
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                sources.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> collectConcreteRuntimeViolations(
                                repositoryRoot, module, path, localTypes, violations));
            }
        }

        assertThat(violations)
                .as("official plugins must consume core-api/plugin-api ports instead of app implementations")
                .isEmpty();
    }

    @Test
    @DisplayName("官方插件生产源码不得引用 app owned 类型")
    void officialPluginSourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(repositoryRoot);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes).as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        for (String module : officialPluginModules(repositoryRoot)) {
            collectAppTypeReferences(repositoryRoot, module, appTypes, violations);
        }

        assertThat(violations)
                .as("official plugin production sources must use stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("官方插件源码注释只能引用共享契约或本插件类型")
    void officialPluginCommentsReferenceOnlySharedOrLocalTypes() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Set<String> sharedTypes = new LinkedHashSet<>(ownedTypes(
                repositoryRoot,
                repositoryRoot.resolve("pixivdownload-core-api/src/main/java"),
                "core-api"));
        sharedTypes.addAll(ownedTypes(
                repositoryRoot,
                repositoryRoot.resolve("pixivdownload-plugin-api/src/main/java"),
                "plugin-api"));
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            Set<String> localTypes = ownedTypes(repositoryRoot, sourceRoot, module);
            assertThat(localTypes)
                    .as(module + " 必须暴露非空的本地生产类型集合")
                    .isNotEmpty();
            collectCommentOwnershipViolations(
                    repositoryRoot, module, sharedTypes, localTypes, violations);
        }

        assertThat(violations)
                .as("官方插件注释只能引用 core-api、plugin-api 或本插件类型；"
                        + "app、其他插件及已失效项目类型都不得进入文档契约")
                .isEmpty();
    }

    @Test
    @DisplayName("官方插件生产文档不得把业务配置归属到宿主 config.yaml")
    void officialPluginProductionTextUsesOwnerScopedConfiguration() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            if ("pixivdownload-plugin-gui-swing".equals(module)) {
                // 桌面 UI 提供者负责呈现并编辑宿主 config.yaml，不拥有其中的业务配置。
                continue;
            }
            Path mainRoot = repositoryRoot.resolve(module).resolve("src/main");
            if (!Files.isDirectory(mainRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(mainRoot)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .filter(OfficialPluginHostBoundaryGuardTest::isProductionText)
                        .sorted()
                        .toList()) {
                    String content = withoutApprovedNovelConfigMigration(repositoryRoot, path, read(path));
                    if (CONFIG_YAML_REFERENCE.matcher(content).find()) {
                        violations.add(repositoryRoot.relativize(path).toString());
                    }
                }
            }
        }

        assertThat(violations)
                .as("官方插件业务配置与凭证必须由 owner-scoped properties 持有；"
                        + "只允许 novel 插件记录旧 config.yaml 的迁移来源")
                .isEmpty();
    }

    @Test
    @DisplayName("所有权扫描忽略注释并保留字符串类名")
    void appOwnedTypeScannerIgnoresCommentsAndPreservesStrings() {
        String appType = "example.host.AppOwnedType";
        String commentsOnly = "// example.host.AppOwnedType\n"
                + "/* example.host.AppOwnedType */\n";
        String reflectionString = "Class.forName(\"example.host.AppOwnedType$Nested\");";
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

        assertThat(referencesFullyQualifiedType(referenceCode(commentsOnly), appType)).isFalse();
        assertThat(referencesFullyQualifiedType(referenceCode(reflectionString), appType)).isTrue();
        assertThat(referencesFullyQualifiedType(commentText(commentsOnly), appType)).isTrue();
        assertThat(referencesFullyQualifiedType(commentText(reflectionString), appType)).isFalse();
        assertThat(projectTypeReferences(
                commentText("// top.sywyar.pixivdownload.legacy.MissingType\n")))
                .containsExactly("top.sywyar.pixivdownload.legacy.MissingType");
        assertThat(isOwnedTypeReference(
                "top.sywyar.pixivdownload.shared.StableType.Nested",
                Set.of("top.sywyar.pixivdownload.shared.StableType"))).isTrue();
        assertThat(isOwnedTypeReference(
                "top.sywyar.pixivdownload.legacy.MissingType",
                Set.of("top.sywyar.pixivdownload.shared.StableType"))).isFalse();
        assertThat(importsType(Set.of("example.host.*"), appType)).isTrue();
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
    @DisplayName("官方插件不得依赖 app artifact")
    void officialPluginsDoNotDependOnAppArtifact() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Set<String> appConsumers = new LinkedHashSet<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Set<String> dependencies = dependencyArtifactIds(
                    repositoryRoot.resolve(module).resolve("pom.xml"));
            if (dependencies.contains("PixivDownload")) {
                appConsumers.add(module);
            }
        }

        assertThat(appConsumers).isEmpty();
    }

    @Test
    @DisplayName("官方插件不得声明或引用宿主私有 HTTP 栈")
    void officialPluginsDoNotOwnPrivateHttpStacks() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path moduleRoot = repositoryRoot.resolve(module);
            for (DependencyCoordinate dependency :
                    dependencyCoordinates(moduleRoot.resolve("pom.xml"))) {
                if (dependency.hasPlaceholder()) {
                    violations.add(module + ": dependency coordinate must be literal: "
                            + dependency);
                } else if (dependency.groupId().startsWith(PRIVATE_HTTP_GROUP_PREFIX)
                        || PRIVATE_HTTP_ARTIFACT.matcher(dependency.artifactId()).matches()) {
                    violations.add(module + ":pom.xml -> " + dependency);
                }
            }
            Path sourceRoot = moduleRoot.resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                for (Path source : sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    for (String privateType : privateHttpReferences(read(source))) {
                        violations.add(module + ":" + repositoryRoot.relativize(source)
                                + " -> " + privateType);
                    }
                }
            }
        }

        assertThat(violations)
                .as("官方插件只能经稳定 HTTP 契约消费宿主传输能力")
                .isEmpty();
    }

    @Test
    @DisplayName("官方插件不得引用宿主私有执行器 Bean 名")
    void officialPluginsDoNotReferencePrivateHostExecutorBeans() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                for (Path source : sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    for (String beanName : privateHostExecutorBeanReferences(read(source))) {
                        violations.add(module + ":" + repositoryRoot.relativize(source)
                                + " -> " + beanName);
                    }
                }
            }
        }

        assertThat(violations)
                .as("官方插件必须经稳定执行契约使用共享下载并发，插件私有任务应由子上下文拥有")
                .isEmpty();
    }

    @Test
    @DisplayName("执行器 Bean 名扫描忽略注释并识别精确字符串字面量")
    void privateHostExecutorBeanScannerIgnoresCommentsAndFindsLiterals() {
        String source = "// \"applicationTaskExecutor\"\n"
                + "/* \"downloadTaskExecutor\" */\n"
                + "class Example {\n"
                + "  String shared = \"downloadTaskExecutor\";\n"
                + "  String local = \"downloadTaskExecutor-local\";\n"
                + "}\n";

        assertThat(privateHostExecutorBeanReferences(source))
                .containsExactly("downloadTaskExecutor");
    }

    @Test
    @DisplayName("官方插件不得引用宿主私有调度器 Bean 名")
    void officialPluginsDoNotReferencePrivateHostSchedulerBeans() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                for (Path source : sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    for (String beanName : privateHostSchedulerBeanReferences(read(source))) {
                        violations.add(module + ":" + repositoryRoot.relativize(source)
                                + " -> " + beanName);
                    }
                }
            }
        }

        assertThat(violations)
                .as("官方插件的调度任务必须由所属 child context 的本地调度器拥有")
                .isEmpty();
    }

    @Test
    @DisplayName("调度器 Bean 名扫描忽略注释并识别精确字符串字面量")
    void privateHostSchedulerBeanScannerIgnoresCommentsAndFindsLiterals() {
        String source = "// \"taskScheduler\"\n"
                + "class Example {\n"
                + "  String shared = \"taskScheduler\";\n"
                + "  String local = \"downloadWorkbenchTaskScheduler\";\n"
                + "}\n";

        assertThat(privateHostSchedulerBeanReferences(source))
                .containsExactly("taskScheduler");
    }

    @Test
    @DisplayName("官方插件执行器注入与异步方法必须绑定模块本地 Bean")
    void officialPluginsBindExecutorUseToModuleLocalBeans() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> moduleSources;
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                moduleSources = sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            Set<String> localExecutorBeans = new LinkedHashSet<>();
            for (Path source : moduleSources) {
                localExecutorBeans.addAll(explicitExecutorBeanNames(read(source)));
            }
            for (Path source : moduleSources) {
                for (String violation :
                        executorBoundaryViolations(read(source), localExecutorBeans)) {
                    violations.add(module + ":" + repositoryRoot.relativize(source)
                            + " -> " + violation);
                }
            }
        }

        assertThat(violations)
                .as("执行器注入和 @Async 必须显式引用同一官方插件模块的本地 @Bean")
                .isEmpty();
    }

    @Test
    @DisplayName("执行器边界扫描覆盖本地、无限定、外部名称并忽略非代码")
    void executorBoundaryScannerCoversPositiveAndNegativeExamples() {
        String declarations = """
                class Executors {
                    // @Bean(name = "commentOnlyTaskExecutor")
                    @Bean(value = "localTaskExecutor", destroyMethod = "shutdown")
                    ThreadPoolTaskExecutor localTaskExecutor() { return null; }

                    @Bean("aliasTaskExecutor")
                    ThreadPoolTaskExecutor aliasTaskExecutor() { return null; }

                    @Bean
                    ThreadPoolTaskExecutor derivedTaskExecutor() { return null; }

                    @Bean(name = "notAnExecutor")
                    String notAnExecutor() { return ""; }
                }
                """;
        Set<String> localBeans = explicitExecutorBeanNames(declarations);
        String valid = """
                class Valid {
                    @Bean
                    Object consumer(
                            @Qualifier("localTaskExecutor") TaskExecutor executor) {
                        return null;
                    }
                    @Async("aliasTaskExecutor")
                    void run() {}
                    String ignored = "@Async(\\"commentOnlyTaskExecutor\\")";
                    // @Bean Object ignored(TaskExecutor executor) { return null; }
                }
                """;
        String invalid = """
                class Invalid {
                    @Bean Object implicit(TaskExecutor executor) { return null; }
                    @Bean Object derived(
                            @Qualifier("derivedTaskExecutor") Executor executor) {
                        return null;
                    }
                    @Async void unnamed() {}
                    @Async("commentOnlyTaskExecutor") void foreign() {}
                }
                """;

        assertThat(localBeans)
                .containsExactlyInAnyOrder("localTaskExecutor", "aliasTaskExecutor");
        assertThat(executorBoundaryViolations(valid, localBeans)).isEmpty();
        assertThat(executorBoundaryViolations(invalid, localBeans))
                .hasSize(4)
                .anySatisfy(violation ->
                        assertThat(violation).contains("缺少 @Qualifier"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("derivedTaskExecutor"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("@Async 缺少执行器名"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("commentOnlyTaskExecutor"));
    }

    @Test
    @DisplayName("官方插件调度器注入与周期方法必须绑定模块本地 Bean")
    void officialPluginsBindSchedulerUseToModuleLocalBeans() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> moduleSources;
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                moduleSources = sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            Set<String> localSchedulerBeans = new LinkedHashSet<>();
            for (Path source : moduleSources) {
                localSchedulerBeans.addAll(explicitTaskSchedulerBeanNames(read(source)));
            }
            for (Path source : moduleSources) {
                for (String violation :
                        schedulerBoundaryViolations(read(source), localSchedulerBeans)) {
                    violations.add(module + ":" + repositoryRoot.relativize(source)
                            + " -> " + violation);
                }
            }
        }

        assertThat(violations)
                .as("TaskScheduler 注入和 @Scheduled 必须显式引用同一官方插件模块的本地 @Bean")
                .isEmpty();
    }

    @Test
    @DisplayName("调度器边界扫描覆盖本地、无限定、外部名称并忽略非代码")
    void schedulerBoundaryScannerCoversPositiveAndNegativeExamples() {
        String declarations = """
                class Schedulers {
                    // @Bean("commentOnlyTaskScheduler")
                    @Bean(name = "localTaskScheduler", destroyMethod = "shutdown")
                    ThreadPoolTaskScheduler localTaskScheduler() { return null; }

                    @Bean
                    ThreadPoolTaskScheduler derivedTaskScheduler() { return null; }

                    @Bean("notAScheduler")
                    String notAScheduler() { return ""; }
                }
                """;
        Set<String> localBeans = explicitTaskSchedulerBeanNames(declarations);
        String valid = """
                class Valid {
                    @Bean
                    Object consumer(
                            @Qualifier("localTaskScheduler") TaskScheduler scheduler) {
                        return null;
                    }
                    Valid(
                            @Qualifier("localTaskScheduler") TaskScheduler scheduler) {}
                    @Autowired
                    void replace(
                            @Qualifier("localTaskScheduler") TaskScheduler scheduler) {}
                    @Autowired
                    @Qualifier("localTaskScheduler")
                    TaskScheduler injectedField;
                    @Scheduled(
                            fixedDelayString = "${fixture.delay:1000}",
                            scheduler = "localTaskScheduler")
                    void run() {}
                    String ignored = "@Scheduled(scheduler = \\"commentOnlyTaskScheduler\\")";
                }
                """;
        String invalid = """
                class Invalid {
                    @Bean Object implicit(TaskScheduler scheduler) { return null; }
                    @Bean Object derived(
                            @Qualifier("derivedTaskScheduler") TaskScheduler scheduler) {
                        return null;
                    }
                    Invalid(TaskScheduler scheduler) {}
                    @Autowired void replace(TaskScheduler scheduler) {}
                    @Autowired TaskScheduler injectedField;
                    @Autowired
                    @Qualifier("foreignTaskScheduler")
                    TaskScheduler foreignField;
                    @Scheduled(fixedDelay = 1000) void unnamed() {}
                    @Scheduled(
                            fixedDelay = 1000,
                            scheduler = "commentOnlyTaskScheduler")
                    void foreign() {}
                }
                """;

        assertThat(localBeans).containsExactly("localTaskScheduler");
        assertThat(schedulerBoundaryViolations(valid, localBeans)).isEmpty();
        assertThat(schedulerBoundaryViolations(invalid, localBeans))
                .hasSize(8)
                .anySatisfy(violation ->
                        assertThat(violation).contains("缺少 @Qualifier"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("derivedTaskScheduler"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("foreignTaskScheduler"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("@Scheduled 缺少 scheduler"))
                .anySatisfy(violation ->
                        assertThat(violation).contains("commentOnlyTaskScheduler"));
    }

    @Test
    @DisplayName("HTTP 所有权扫描忽略注释并识别导入与反射类名")
    void privateHttpScannerIgnoresCommentsAndFindsRuntimeReferences() {
        String source = "// org.apache.hc.client5.http.impl.classic.CloseableHttpClient\n"
                + "import org /* owner */ . apache . hc . client5 . http . impl . classic"
                + " . CloseableHttpClient;\n"
                + "import java.net /* transport */ . http . HttpClient;\n"
                + "class Example {\n"
                + "  String factory = \"org.springframework.http.client."
                + "HttpComponentsClientHttpRequestFactory\";\n"
                + "  String selector = \"java.net.ProxySelector\";\n"
                + "}\n";

        assertThat(privateHttpReferences(source)).containsExactlyInAnyOrder(
                "org.apache.hc.",
                "org.springframework.http.client.HttpComponentsClientHttpRequestFactory",
                "java.net.http.HttpClient",
                "java.net.ProxySelector");
        assertThat(privateHttpReferences(
                "// org.apache.http.client.HttpClient\nclass Example {}\n")).isEmpty();
    }

    @Test
    @DisplayName("每个官方外置包只声明一个与 plugin.properties 同 id 的功能插件")
    void officialProviderIdentityMatchesPackageDescriptor() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Path descriptorPath = repositoryRoot.resolve(module)
                    .resolve("src/main/resources/plugin.properties");
            Properties descriptor = new Properties();
            descriptor.load(new StringReader(read(descriptorPath)));
            String packageId = descriptor.getProperty("plugin.id");
            String providerClass = descriptor.getProperty("plugin.class");
            if (packageId == null || packageId.isBlank() || providerClass == null || providerClass.isBlank()) {
                violations.add(module + ": plugin.properties must declare plugin.id and plugin.class");
                continue;
            }

            Path providerSource = repositoryRoot.resolve(module).resolve("src/main/java")
                    .resolve(providerClass.replace('.', '/') + ".java");
            if (!Files.isRegularFile(providerSource)) {
                violations.add(module + ": provider source not found: " + providerClass);
                continue;
            }
            String providerCode = stripComments(read(providerSource));
            Matcher factory = FEATURE_PLUGIN_FACTORY.matcher(providerCode);
            if (!factory.find()) {
                violations.add(module + ": provider must return exactly one concrete featurePlugin()");
                continue;
            }
            String featureSimpleName = factory.group(1);
            if (factory.find()) {
                violations.add(module + ": provider contains multiple featurePlugin factories");
                continue;
            }
            int lastDot = providerClass.lastIndexOf('.');
            String featureClass = featureSimpleName.contains(".")
                    ? featureSimpleName
                    : providerClass.substring(0, lastDot + 1) + featureSimpleName;
            Path featureSource = repositoryRoot.resolve(module).resolve("src/main/java")
                    .resolve(featureClass.replace('.', '/') + ".java");
            if (!Files.isRegularFile(featureSource)) {
                violations.add(module + ": feature source not found: " + featureClass);
                continue;
            }
            String featureCode = stripComments(read(featureSource));
            Matcher idReturn = FEATURE_ID_RETURN.matcher(featureCode);
            if (!idReturn.find()) {
                violations.add(module + ": feature id() must return a literal or local String constant");
                continue;
            }
            String featureId = idReturn.group(1);
            if (featureId == null) {
                Pattern constant = Pattern.compile("(?m)\\bString\\s+" + Pattern.quote(idReturn.group(2))
                        + "\\s*=\\s*\"([^\"]+)\"\\s*;");
                Matcher constantMatcher = constant.matcher(featureCode);
                if (!constantMatcher.find()) {
                    violations.add(module + ": unresolved feature id constant " + idReturn.group(2));
                    continue;
                }
                featureId = constantMatcher.group(1);
            }
            if (!packageId.equals(featureId)) {
                violations.add(module + ": package id " + packageId + " != feature id " + featureId);
            }
        }

        assertThat(violations)
                .as("official PF4J package identity must equal its singular feature identity")
                .isEmpty();
    }

    @Test
    @DisplayName("宿主生产代码与资源不拥有小说执行设置或线程池")
    void hostDoesNotOwnNovelExecutionConfiguration() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path appMain = repositoryRoot.resolve("pixivdownload-app/src/main");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(appMain)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")
                            || path.toString().endsWith(".properties"))
                    .sorted()
                    .toList()) {
                String content = read(path);
                for (String token : NOVEL_EXECUTION_OWNER_TOKENS) {
                    if (content.contains(token)) {
                        violations.add(repositoryRoot.relativize(path) + " contains " + token);
                    }
                }
            }
        }

        assertThat(violations)
                .as("novel execution settings and executors must remain inside the novel plugin")
                .isEmpty();
    }

    private static void collectAppTypeReferences(Path repositoryRoot,
                                                 String module,
                                                 Set<String> appTypes,
                                                 List<String> violations) throws IOException {
        Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        Set<String> localTypes = ownedTypes(repositoryRoot, sourceRoot, module);
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String sourceCode = read(source);
                String references = referenceCode(sourceCode);
                String declarations = normalizedDeclarationCode(sourceCode);
                String packageName = packageName(declarations);
                Set<String> imports = importedNames(declarations);
                for (String appType : appTypes) {
                    if (localTypes.contains(appType)) {
                        continue;
                    }
                    if (referencesFullyQualifiedType(references, appType)
                            || importsType(imports, appType)
                            || samePackageSimpleReference(
                            references, packageName, appType)) {
                        violations.add(module + ":" + repositoryRoot.relativize(source) + " -> " + appType);
                    }
                }
            }
        }
    }

    private static void collectCommentOwnershipViolations(Path repositoryRoot,
                                                          String module,
                                                          Set<String> sharedTypes,
                                                          Set<String> localTypes,
                                                          List<String> violations) throws IOException {
        Path sourceRoot = repositoryRoot.resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String comments = commentText(read(source));
                for (String projectType : projectTypeReferences(comments)) {
                    if (!isOwnedTypeReference(projectType, sharedTypes)
                            && !isOwnedTypeReference(projectType, localTypes)) {
                        violations.add(module + ":" + repositoryRoot.relativize(source)
                                + " -> " + projectType);
                    }
                }
            }
        }
    }

    private static Set<String> projectTypeReferences(String comments) {
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = PROJECT_TYPE_REFERENCE.matcher(comments);
        while (matcher.find()) {
            references.add(matcher.group());
        }
        return Set.copyOf(references);
    }

    private static boolean isOwnedTypeReference(String reference, Set<String> ownedTypes) {
        return ownedTypes.stream().anyMatch(
                ownedType -> reference.equals(ownedType)
                        || reference.startsWith(ownedType + ".")
                        || ownedType.startsWith(reference + "."));
    }

    private static boolean referencesFullyQualifiedType(String code, String appType) {
        return identifierReference(appType).matcher(code).find();
    }

    private static boolean samePackageSimpleReference(String code, String packageName, String appType) {
        int separator = appType.lastIndexOf('.');
        if (separator < 0 || !appType.substring(0, separator).equals(packageName)) {
            return false;
        }
        return identifierReference(appType.substring(separator + 1)).matcher(code).find();
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

    private static String commentText(String source) {
        String codeWithoutComments = stripComments(source);
        StringBuilder comments = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char original = source.charAt(index);
            if (original != codeWithoutComments.charAt(index)) {
                comments.append(original);
            } else {
                appendMasked(comments, original);
            }
        }
        return comments.toString();
    }

    private static Set<String> appOwnedTypes(Path repositoryRoot) throws IOException {
        Path appSourceRoot = repositoryRoot.resolve("pixivdownload-app/src/main/java");
        return ownedTypes(repositoryRoot, appSourceRoot, "app");
    }

    private static Set<String> ownedTypes(Path repositoryRoot,
                                          Path sourceRoot,
                                          String owner) throws IOException {
        Set<String> types = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
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
                            "Cannot derive primary " + owner + " type from "
                                    + repositoryRoot.relativize(source));
                }
                for (String sourceType : sourceTypes) {
                    String ownedType = packageName + "." + sourceType;
                    if (!types.add(ownedType)) {
                        throw new IllegalStateException(
                                "Duplicate " + owner + " production type " + ownedType);
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
                            state = next == '/' ? SourceState.LINE_COMMENT : SourceState.BLOCK_COMMENT;
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

    private static void collectConcreteRuntimeViolations(Path repositoryRoot,
                                                         String module,
                                                         Path source,
                                                         Set<String> localTypes,
                                                         List<String> violations) {
        String content = read(source);
        for (String forbiddenType : concreteRuntimeReferences(content)) {
            if (!localTypes.contains(forbiddenType)) {
                violations.add(module + ":" + repositoryRoot.relativize(source) + " -> " + forbiddenType);
            }
        }
    }

    private static List<String> officialPluginModules(Path repositoryRoot) throws IOException {
        Set<String> modules = new LinkedHashSet<>();
        for (String artifactId : dependencyArtifactIds(
                repositoryRoot.resolve("pixivdownload-official-plugins/pom.xml"))) {
            if (OFFICIAL_PLUGIN_ARTIFACT.matcher(artifactId).matches()) {
                modules.add(artifactId);
            }
        }
        assertThat(modules).as("official plugin aggregate must not be empty").hasSizeGreaterThan(10);
        assertThat(modules).allSatisfy(module ->
                assertThat(repositoryRoot.resolve(module).resolve("pom.xml")).isRegularFile());
        return List.copyOf(modules);
    }

    private static boolean isProductionText(Path path) {
        String name = path.getFileName().toString();
        return PRODUCTION_TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static String withoutApprovedNovelConfigMigration(Path repositoryRoot,
                                                              Path path,
                                                              String content) {
        String relativePath = repositoryRoot.relativize(path).toString().replace('\\', '/');
        if (!NOVEL_CONFIG_MIGRATION_SOURCE.equals(relativePath)) {
            return content;
        }
        return APPROVED_NOVEL_CONFIG_MIGRATION.matcher(content).replaceFirst("");
    }

    @Test
    @DisplayName("源码扫描忽略注释并识别字符串字面量中的宿主类名")
    void sourceScannerIgnoresCommentsAndFindsStringLiterals() {
        String source = "package example;\n"
                + "// top.sywyar.pixivdownload.config.ProxyConfig\n"
                + "/** top.sywyar.pixivdownload.config.RuntimeFiles */\n"
                + "class Example {\n"
                + "  String name = \"top.sywyar.pixivdownload.setup.guest.GuestAccessGuard\";\n"
                + "  String block = \"\"\"\n"
                + "      top.sywyar.pixivdownload.core.appconfig.DownloadConfig\n"
                + "      \"\"\";\n"
                + "}\n";

        assertThat(concreteRuntimeReferences(source)).containsExactlyInAnyOrder(
                "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
                "top.sywyar.pixivdownload.setup.guest.GuestAccessGuard");
    }

    @Test
    @DisplayName("源码扫描识别显式导入、通配导入、静态导入与内联全限定名")
    void sourceScannerFindsProductionTypeReferences() {
        String source = "package example;\n"
                + "import top.sywyar.pixivdownload.config.ProxyConfig;\n"
                + "import top.sywyar.pixivdownload.setup.guest.*;\n"
                + "import static top.sywyar.pixivdownload.config.RuntimeFiles.*;\n"
                + "class Example {\n"
                + "  top.sywyar.pixivdownload.core.appconfig.DownloadConfig settings;\n"
                + "  GuestAccessGuard guard;\n"
                + "}\n";

        assertThat(concreteRuntimeReferences(source)).containsExactlyInAnyOrder(
                "top.sywyar.pixivdownload.config.ProxyConfig",
                "top.sywyar.pixivdownload.config.RuntimeFiles",
                "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
                "top.sywyar.pixivdownload.setup.guest.GuestAccessGuard",
                "top.sywyar.pixivdownload.setup.guest.GuestInviteSession",
                "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeArgumentResolver",
                "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory",
                "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityWebConfiguration",
                "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityService");
    }

    private static Set<String> concreteRuntimeReferences(String source) {
        String productionCode = referenceCode(source);
        String declarationCode = normalizedDeclarationCode(source);
        Set<String> imports = new LinkedHashSet<>();
        var importMatcher = IMPORT_DECLARATION.matcher(declarationCode);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }

        Set<String> references = new LinkedHashSet<>();
        for (String forbiddenType : CONCRETE_HOST_RUNTIME_TYPES) {
            int packageSeparator = forbiddenType.lastIndexOf('.');
            String wildcardImport = forbiddenType.substring(0, packageSeparator) + ".*";
            boolean imported = imports.stream().anyMatch(importName ->
                    importName.equals(forbiddenType)
                            || importName.equals(wildcardImport)
                            || importName.startsWith(forbiddenType + "."));
            if (imported || identifierReference(forbiddenType).matcher(productionCode).find()) {
                references.add(forbiddenType);
            }
        }
        return references;
    }

    private static Set<String> privateHttpReferences(String source) {
        String code = referenceCode(source);
        Set<String> references = new LinkedHashSet<>();
        for (String privateType : PRIVATE_HTTP_TYPE_PREFIXES) {
            Pattern reference = Pattern.compile(
                    "(?<![\\p{Alnum}_$])" + Pattern.quote(privateType));
            if (reference.matcher(code).find()) {
                references.add(privateType);
            }
        }
        return references;
    }

    private static Set<String> privateHostExecutorBeanReferences(String source) {
        return privateHostBeanReferences(source, PRIVATE_HOST_EXECUTOR_BEAN_NAMES);
    }

    private static Set<String> privateHostSchedulerBeanReferences(String source) {
        return privateHostBeanReferences(source, PRIVATE_HOST_SCHEDULER_BEAN_NAMES);
    }

    private static Set<String> privateHostBeanReferences(
            String source,
            List<String> privateBeanNames) {
        String code = referenceCode(source);
        Set<String> references = new LinkedHashSet<>();
        for (String beanName : privateBeanNames) {
            Pattern literal = Pattern.compile("\"" + Pattern.quote(beanName) + "\"");
            if (literal.matcher(code).find()) {
                references.add(beanName);
            }
        }
        return references;
    }

    private static Set<String> explicitExecutorBeanNames(String source) {
        String code = stripComments(source);
        Matcher methods = BEAN_METHOD.matcher(maskCommentsAndLiterals(source));
        Set<String> names = new LinkedHashSet<>();
        while (methods.find()) {
            if (!EXECUTOR_INJECTION_TYPE.matcher(methods.group(2)).find()
                    || methods.start(1) < 0) {
                continue;
            }
            String name = explicitBeanName(
                    code.substring(methods.start(1), methods.end(1)));
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static Set<String> explicitTaskSchedulerBeanNames(String source) {
        String code = stripComments(source);
        Matcher methods = BEAN_METHOD.matcher(maskCommentsAndLiterals(source));
        Set<String> names = new LinkedHashSet<>();
        while (methods.find()) {
            if (!TASK_SCHEDULER_TYPE.matcher(methods.group(2)).find()
                    || methods.start(1) < 0) {
                continue;
            }
            String name = explicitBeanName(
                    code.substring(methods.start(1), methods.end(1)));
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> executorBoundaryViolations(
            String source,
            Set<String> localExecutorBeans) {
        String code = stripComments(source);
        String structure = maskCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        Matcher methods = BEAN_METHOD.matcher(structure);
        while (methods.find()) {
            String parameters = code.substring(methods.start(3), methods.end(3));
            Matcher executor = EXECUTOR_PARAMETER.matcher(parameters);
            while (executor.find()) {
                String qualifier = executor.group(1);
                if (qualifier == null) {
                    violations.add("@Bean 执行器参数缺少 @Qualifier: "
                            + executor.group().replaceAll("\\s+", " ").trim());
                } else if (!localExecutorBeans.contains(qualifier)) {
                    violations.add("@Qualifier(\"" + qualifier
                            + "\") 未引用同模块本地执行器 @Bean");
                }
            }
        }

        Matcher async = ASYNC_ANNOTATION.matcher(structure);
        while (async.find()) {
            String executorName = async.start(1) < 0 ? null : annotationLiteral(
                    code.substring(async.start(1), async.end(1)));
            if (executorName == null) {
                violations.add("@Async 缺少执行器名");
            } else if (!localExecutorBeans.contains(executorName)) {
                violations.add("@Async(\"" + executorName
                        + "\") 未引用同模块本地执行器 @Bean");
            }
        }
        return List.copyOf(violations);
    }

    private static List<String> schedulerBoundaryViolations(
            String source,
            Set<String> localSchedulerBeans) {
        String code = stripComments(source);
        String structure = maskCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        List<int[]> parameterRanges = new ArrayList<>();
        Matcher callables = CALLABLE_DECLARATION.matcher(structure);
        while (callables.find()) {
            parameterRanges.add(new int[]{callables.start(1), callables.end(1)});
            collectTaskSchedulerParameterViolations(
                    code.substring(callables.start(1), callables.end(1)),
                    localSchedulerBeans,
                    violations);
        }

        Matcher schedulerDeclarations = TASK_SCHEDULER_PARAMETER.matcher(structure);
        while (schedulerDeclarations.find()) {
            if (insideAnyRange(schedulerDeclarations.start(), parameterRanges)) {
                continue;
            }
            int statementStart = previousStatementBoundary(
                    structure, schedulerDeclarations.start());
            int statementEnd = structure.indexOf(';', schedulerDeclarations.end());
            if (statementEnd < 0) {
                continue;
            }
            String statementStructure =
                    structure.substring(statementStart, statementEnd + 1);
            if (!INJECTION_ANNOTATION.matcher(statementStructure).find()) {
                continue;
            }
            collectTaskSchedulerParameterViolations(
                    code.substring(statementStart, statementEnd + 1),
                    localSchedulerBeans,
                    violations);
        }

        Matcher scheduled = SCHEDULED_ANNOTATION.matcher(structure);
        while (scheduled.find()) {
            String schedulerName = scheduledSchedulerName(
                    code.substring(scheduled.start(1), scheduled.end(1)));
            if (schedulerName == null) {
                violations.add("@Scheduled 缺少 scheduler 属性");
            } else if (!localSchedulerBeans.contains(schedulerName)) {
                violations.add("@Scheduled(scheduler = \"" + schedulerName
                        + "\") 未引用同模块本地调度器 @Bean");
            }
        }
        return List.copyOf(violations);
    }

    private static void collectTaskSchedulerParameterViolations(
            String declaration,
            Set<String> localSchedulerBeans,
            List<String> violations) {
        Matcher scheduler = TASK_SCHEDULER_PARAMETER.matcher(declaration);
        while (scheduler.find()) {
            String qualifier = scheduler.group(1);
            if (qualifier == null) {
                violations.add("调度器注入参数缺少 @Qualifier: "
                        + scheduler.group().replaceAll("\\s+", " ").trim());
            } else if (!localSchedulerBeans.contains(qualifier)) {
                violations.add("@Qualifier(\"" + qualifier
                        + "\") 未引用同模块本地调度器 @Bean");
            }
        }
    }

    private static boolean insideAnyRange(int position, List<int[]> ranges) {
        return ranges.stream()
                .anyMatch(range -> position >= range[0] && position < range[1]);
    }

    private static int previousStatementBoundary(String source, int position) {
        int boundary = -1;
        for (char marker : new char[]{';', '{', '}'}) {
            boundary = Math.max(boundary, source.lastIndexOf(marker, position - 1));
        }
        return boundary + 1;
    }

    private static String explicitBeanName(String arguments) {
        Matcher positional = BEAN_POSITIONAL_NAME.matcher(arguments);
        if (positional.find()) {
            return positional.group(1);
        }
        Matcher named = BEAN_NAMED_NAME.matcher(arguments);
        return named.find() ? named.group(1) : null;
    }

    private static String annotationLiteral(String arguments) {
        Matcher literal = SINGLE_ANNOTATION_LITERAL.matcher(arguments);
        return literal.matches() ? literal.group(1) : null;
    }

    private static String scheduledSchedulerName(String arguments) {
        Matcher schedulerName = SCHEDULED_SCHEDULER_NAME.matcher(arguments);
        return schedulerName.find() ? schedulerName.group(1) : null;
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

    private static Set<String> dependencyArtifactIds(Path pom) throws IOException {
        Set<String> artifactIds = new LinkedHashSet<>();
        for (DependencyCoordinate dependency : dependencyCoordinates(pom)) {
            artifactIds.add(dependency.artifactId());
        }
        return Set.copyOf(artifactIds);
    }

    private static List<DependencyCoordinate> dependencyCoordinates(Path pom)
            throws IOException {
        Document document = parsePom(pom);
        List<DependencyCoordinate> dependencies = new ArrayList<>();
        NodeList dependencyGroups = document.getElementsByTagNameNS("*", "dependencies");
        for (int groupIndex = 0; groupIndex < dependencyGroups.getLength(); groupIndex++) {
            Node dependencyGroup = dependencyGroups.item(groupIndex);
            String parentName = localName(dependencyGroup.getParentNode());
            if (!"project".equals(parentName) && !"profile".equals(parentName)) {
                continue;
            }
            NodeList children = dependencyGroup.getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                Node child = children.item(childIndex);
                if (!(child instanceof Element dependency)
                        || !"dependency".equals(localName(dependency))) {
                    continue;
                }
                String artifactId = directChildText(dependency, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    String groupId = directChildText(dependency, "groupId");
                    String scope = directChildText(dependency, "scope");
                    dependencies.add(new DependencyCoordinate(
                            groupId == null ? "" : groupId.trim(),
                            artifactId.trim(),
                            scope == null || scope.isBlank() ? "compile" : scope.trim()));
                }
            }
        }
        return List.copyOf(dependencies);
    }

    private static Document parsePom(Path pom) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream input = Files.newInputStream(pom)) {
                return factory.newDocumentBuilder().parse(input);
            }
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException failure) {
            throw new IllegalStateException("Failed to parse Maven POM safely: " + pom, failure);
        }
    }

    private static String directChildText(Element parent, String childName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element && childName.equals(localName(child))) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private static String localName(Node node) {
        if (node == null) {
            return null;
        }
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pixivdownload-official-plugins/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from current working directory");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read " + path, failure);
        }
    }

    private record DependencyCoordinate(
            String groupId,
            String artifactId,
            String scope
    ) {
        private boolean hasPlaceholder() {
            return groupId.contains("${")
                    || artifactId.contains("${")
                    || scope.contains("${");
        }
    }
}
