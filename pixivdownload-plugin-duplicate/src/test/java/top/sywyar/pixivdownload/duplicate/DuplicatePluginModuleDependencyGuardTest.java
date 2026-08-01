package top.sywyar.pixivdownload.duplicate;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

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
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("duplicate 模块边界守卫")
class DuplicatePluginModuleDependencyGuardTest {

    private static final String[] HOST_PRIVATE_CLASS_RESOURCES = {
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class"
    };
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package[\\t ]+([A-Za-z0-9_$.]+)[\\t ]*;");
    private static final Pattern APP_ARTIFACT = Pattern.compile(
            "<artifactId>\\s*PixivDownload\\s*</artifactId>");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.duplicate");

    @Test
    @DisplayName("duplicate 托管 Bean 不得直连数据库底层")
    void duplicateManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("疑似重复插件只经 core-api 哈希索引语义端口读取和重建核心事实，"
                        + "不得自建 JDBC/MyBatis 访问核心表或注入宿主实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("duplicate 不得依赖宿主私有 HTTP 类型")
    void duplicateDoesNotDependOnPrivateHttpTypes() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.duplicate..")
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
    @DisplayName("duplicate 测试类路径不得包含 app 与宿主私有 HTTP 实现")
    void duplicateClasspathExcludesHostApplicationAndPrivateHttpStack() {
        ClassLoader classLoader = getClass().getClassLoader();
        for (String resource : HOST_PRIVATE_CLASS_RESOURCES) {
            assertThat(classLoader.getResource(resource)).as(resource).isNull();
        }
    }

    @Test
    @DisplayName("duplicate POM 不得依赖 PixivDownload app artifact")
    void duplicatePomDoesNotDependOnAppArtifact() {
        String pom = read(repositoryRoot().resolve("pixivdownload-plugin-duplicate/pom.xml"));

        assertThat(APP_ARTIFACT.matcher(pom).find()).isFalse();
    }

    @Test
    @DisplayName("duplicate 生产与测试源码不得引用 app owned 类型")
    void duplicateSourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path root = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(root);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes).as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        String packagePrivateFixture = "top.sywyar.pixivdownload.gui.panel."
                + "StatusPanelThemeOption";
        assertThat(appTypes)
                .as("package-private top-level app types must be owned too")
                .contains(packagePrivateFixture);
        collectAppTypeReferences(root, "src/main/java", appTypes, violations);
        collectAppTypeReferences(root, "src/test/java", appTypes, violations);

        assertThat(violations)
                .as("duplicate must compile and test only against stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("duplicate 模块不依赖 gallery / novel / download / stats 插件实现")
    void duplicateModuleDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.stats..")
                .because("duplicate 只消费核心 Hash / 作品事实，不应反向依赖其它功能插件实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("duplicate 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void duplicateModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(DuplicatePf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicatePlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicatePluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateController.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateService.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateScanService.class.getName())).isTrue();
        assertThat(CLASSES.contain(DuplicateHashBackfillTask.class.getName())).isTrue();
    }

    @Test
    @DisplayName("duplicate 队列操作实现非空且由插件生命周期托管")
    void queueOperationsArePluginManaged() {
        assertThat(CLASSES.contain(DuplicateScanService.class.getName())).isTrue();
        classes()
                .that().areAssignableTo(QueueOperations.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(PluginManagedBean.class)
                .because("运行期队列操作必须随所属插件 child context 一并发布和撤回")
                .check(CLASSES);
    }

    private static void collectAppTypeReferences(Path root,
                                                 String sourcePath,
                                                 Set<String> appTypes,
                                                 List<String> violations) throws IOException {
        Path sourceRoot = root.resolve("pixivdownload-plugin-duplicate").resolve(sourcePath);
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String code = stripComments(read(source));
                String packageName = packageName(code);
                for (String appType : appTypes) {
                    if (code.contains(appType)
                            || samePackageSimpleReference(code, packageName, appType)) {
                        violations.add(root.relativize(source) + " -> " + appType);
                    }
                }
            }
        }
    }

    private static boolean samePackageSimpleReference(String code, String packageName, String appType) {
        int separator = appType.lastIndexOf('.');
        if (separator < 0 || !appType.substring(0, separator).equals(packageName)) {
            return false;
        }
        String simpleName = appType.substring(separator + 1);
        return Pattern.compile("(?<![\\p{Alnum}_$])" + Pattern.quote(simpleName)
                        + "(?![\\p{Alnum}_$])")
                .matcher(code)
                .find();
    }

    private static String packageName(String code) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Set<String> appOwnedTypes(Path root) throws IOException {
        Path appSourceRoot = root.resolve("pixivdownload-app/src/main/java");
        List<Path> sourcePaths;
        try (Stream<Path> sources = Files.walk(appSourceRoot)) {
            sourcePaths = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java")
                            && !path.getFileName().toString().equals("module-info.java"))
                    .sorted()
                    .toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK compiler is required for ownership checks");
        }
        Set<String> types = new LinkedHashSet<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-proc:none"),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(sourcePaths));
            for (CompilationUnitTree unit : task.parse()) {
                String packageName = unit.getPackageName() == null
                        ? ""
                        : unit.getPackageName().toString();
                Path source = Path.of(unit.getSourceFile().toUri());
                String primaryType = source.getFileName().toString()
                        .replaceFirst("\\.java$", "");
                Set<String> sourceTypes = new LinkedHashSet<>();
                unit.getTypeDecls().stream()
                        .filter(ClassTree.class::isInstance)
                        .map(ClassTree.class::cast)
                        .map(type -> type.getSimpleName().toString())
                        .filter(name -> !name.isBlank())
                        .forEach(sourceTypes::add);
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

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pixivdownload-official-plugins/pom.xml"))) {
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
