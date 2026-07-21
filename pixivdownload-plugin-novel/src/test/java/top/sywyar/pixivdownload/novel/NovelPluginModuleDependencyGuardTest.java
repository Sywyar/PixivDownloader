package top.sywyar.pixivdownload.novel;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.schedule.PixivScheduledNovelWorkExecutor;
import top.sywyar.pixivdownload.novelgallery.NovelBatchService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.novelgallery.NovelOwnedWorkSearch;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel 模块边界守卫")
class NovelPluginModuleDependencyGuardTest {

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package[\\t ]+([A-Za-z0-9_$.]+)[\\t ]*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import[\\t ]+(?:static[\\t ]+)?([A-Za-z0-9_$.*]+)[\\t ]*;");
    private static final Pattern APP_ARTIFACT = Pattern.compile(
            "<artifactId>\\s*PixivDownload\\s*</artifactId>");
    private static final List<String> FORBIDDEN_RESOURCE_BASENAMES = List.of(
            "classpath:i18n/" + "messages",
            "classpath:i18n/" + "ValidationMessages",
            "classpath:i18n/mail/" + "messages",
            "classpath:i18n/push/" + "messages",
            "classpath:i18n/tts/" + "messages",
            "classpath:i18n/web/" + "tts");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.novel", "top.sywyar.pixivdownload.novelgallery");

    private enum SourceState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHARACTER
    }

    @Test
    @DisplayName("novel-gallery 托管 Bean 不得直连数据库底层或核心 mapper")
    void novelGalleryManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("novel-gallery 只能经小说核心服务与 plugin.api 契约读取展示数据，"
                        + "不得自建 JDBC/MyBatis 访问核心表")
                .check(CLASSES);
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                        top.sywyar.pixivdownload.novel.db.NovelMapper.class))
                .because("novel-gallery 不得直接依赖小说核心 mapper")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel POM 不得依赖 PixivDownload app artifact")
    void novelPomDoesNotDependOnAppArtifact() {
        String pom = read(repositoryRoot().resolve("pixivdownload-plugin-novel/pom.xml"));

        assertThat(APP_ARTIFACT.matcher(pom).find()).isFalse();
    }

    @Test
    @DisplayName("novel 生产与测试源码不得引用 app owned 类型或跨 owner bundle")
    void novelSourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path root = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(root);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes).as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        collectAppTypeReferences(root, "src/main/java", appTypes, violations);
        collectAppTypeReferences(root, "src/test/java", appTypes, violations);
        collectForbiddenResourceReferences(root, "src/main/java", violations);
        collectForbiddenResourceReferences(root, "src/test/java", violations);

        assertThat(violations)
                .as("novel must compile and test only against stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("源码扫描识别导入、同包类型与反射类名")
    void sourceScannerFindsAppOwnedReferences() {
        String source = """
                package example;
                import app.explicit.HostType;
                import app.wildcard.*;
                import static app.statical.StaticType.VALUE;
                class Example {
                    SamePackageType samePackage;
                    String reflected = "app.reflection.ReflectedType$Nested";
                }
                """;
        String code = stripComments(source);

        assertThat(referencesAppType(code, "example", "app.explicit.HostType")).isTrue();
        assertThat(referencesAppType(code, "example", "app.wildcard.WildcardType")).isTrue();
        assertThat(referencesAppType(code, "example", "app.statical.StaticType")).isTrue();
        assertThat(referencesAppType(code, "example", "example.SamePackageType")).isTrue();
        assertThat(referencesAppType(code, "example", "app.reflection.ReflectedType")).isTrue();
    }

    @Test
    @DisplayName("源码扫描忽略注释中的宿主类名")
    void sourceScannerIgnoresComments() {
        String source = """
                package example;
                // app.comment.LineCommentType
                /* app.comment.BlockCommentType */
                class Example {
                }
                """;
        String code = stripComments(source);

        assertThat(referencesAppType(code, "example", "app.comment.LineCommentType")).isFalse();
        assertThat(referencesAppType(code, "example", "app.comment.BlockCommentType")).isFalse();
    }

    @Test
    @DisplayName("novel-gallery 通用查询走中性接口且仅正文适配层可读插件数据库")
    void novelGalleryServicesKeepPrivateContentAccessInOwnedAdapter() {
        Set<String> forbiddenTypes = Set.of(
                NovelDatabase.class.getName(),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelGalleryRepository"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRepository"),
                hostType("top.sywyar.pixivdownload.author", "AuthorService"));
        noClasses()
                .that(JavaClass.Predicates.belongToAnyOf(
                        NovelGalleryService.class,
                        NovelBatchService.class))
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "宿主元数据实现或插件正文数据库",
                        javaClass -> forbiddenTypes.contains(javaClass.getName())))
                .because("novel-gallery 的通用元数据查询走 WorkQueryService/WorkMetadataRepository，"
                        + "删除走 WorkDeletionService，普通文件枚举走 WorkAssetService")
                .check(CLASSES);
    }

    @Test
    @DisplayName("小说删除只能走宿主统一作品删除入口")
    void novelPersistenceDoesNotExposeDeletionBypass() {
        assertThat(Arrays.stream(NovelDatabase.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("deleteNovel", "markNovelDeleted");

        for (Method method : NovelMapper.class.getDeclaredMethods()) {
            Delete delete = method.getAnnotation(Delete.class);
            if (delete == null) {
                continue;
            }
            assertThat(delete.value())
                    .as(method.getName() + " 不得直接删除 novels 主行")
                    .noneMatch(sql -> sql.matches("(?is).*\\bDELETE\\s+FROM\\s+novels\\b.*"));
        }
    }

    @Test
    @DisplayName("novel 生产代码只能依赖宿主稳定端口，不得依赖配置、模式、路径、身份与可见性实现")
    void novelModuleDoesNotDependOnHostImplementations() {
        Set<String> forbiddenTypes = Set.of(
                hostType("top.sywyar.pixivdownload.core.appconfig", "DownloadConfig"),
                hostType("top.sywyar.pixivdownload.core.appconfig", "MultiModeConfig"),
                hostType("top.sywyar.pixivdownload.config", "DebugConfig"),
                hostType("top.sywyar.pixivdownload.config", "RuntimeFiles"),
                hostType("top.sywyar.pixivdownload.common", "ErrorResponse"),
                hostType("top.sywyar.pixivdownload.common", "PixivCoverDownloader"),
                hostType("top.sywyar.pixivdownload.common", "PixivRequestHeaders"),
                hostType("top.sywyar.pixivdownload.common", "SafePathSegment"),
                hostType("top.sywyar.pixivdownload.core.ai", "AiService"),
                hostType("top.sywyar.pixivdownload.core.db", "PixivDatabase"),
                hostType("top.sywyar.pixivdownload.core.db.pathprefix", "PathPrefixCodec"),
                hostType("top.sywyar.pixivdownload.core.db.schema", "DatabaseInitializer"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRepository"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelMetadataRow"),
                hostType("top.sywyar.pixivdownload.core.metadata.novel", "NovelSeriesTitleRow"),
                hostType("top.sywyar.pixivdownload.core.metadata.sidecar", "WorkMetaCaptureService"),
                hostType("top.sywyar.pixivdownload.core.narration", "NarrationEngineRegistry"),
                hostType("top.sywyar.pixivdownload.core.narration", "NarrationTtsConfig"),
                hostType("top.sywyar.pixivdownload.core.pixiv", "PixivAjaxProxyClient"),
                hostType("top.sywyar.pixivdownload.core.pixiv", "PixivBookmarkService"),
                hostType("top.sywyar.pixivdownload.core.pixiv", "PixivImageDownloadService"),
                hostType("top.sywyar.pixivdownload.core.pixiv", "PixivProxyAccessGuard"),
                hostType("top.sywyar.pixivdownload.i18n", "LocalizedException"),
                hostType("top.sywyar.pixivdownload.i18n", "MessageBundles"),
                hostType("top.sywyar.pixivdownload.author", "AuthorService"),
                hostType("top.sywyar.pixivdownload.collection", "CollectionService"),
                hostType("top.sywyar.pixivdownload.quota", "UserQuotaService"),
                hostType("top.sywyar.pixivdownload.series", "MangaSeriesService"),
                hostType("top.sywyar.pixivdownload.setup", "SetupService"),
                hostType("top.sywyar.pixivdownload.setup.guest", "GuestAccessGuard"),
                hostType("top.sywyar.pixivdownload.setup.guest", "GuestInviteSession"),
                hostType("top.sywyar.pixivdownload.common", "UuidUtils"));
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "宿主实现类型",
                        javaClass -> forbiddenTypes.contains(javaClass.getName())))
                .because("外置 novel 插件应依赖 core-api/plugin-api 稳定端口，"
                        + "宿主配置绑定、运行期路径、setup、访客会话与 UUID 解析实现必须留在 app")
                .check(CLASSES);
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web.client..")
                .because("Pixiv HTTP 客户端实现与失败分类已由 core-api 稳定端口隔离，"
                        + "novel 不得依赖宿主 RestTemplate 异常")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel 计划作品执行只走 plugin-api 契约并随插件生命周期托管")
    void novelScheduleUsesOnlyPluginApiWorkExecutors() {
        noClasses()
                .that().resideInAnyPackage(
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.core.schedule..")
                .because("小说计划作品与自动翻译生命周期只走 plugin-api 稳定契约，"
                        + "不得依赖 app schedule registry、lease 或 legacy runner")
                .check(CLASSES);

        assertThat(CLASSES.contain(PixivScheduledNovelWorkExecutor.class.getName())).isTrue();
        classes()
                .that().areAssignableTo(ScheduledWorkExecutor.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(PluginManagedBean.class)
                .andShould().notBeAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("小说计划作品执行器必须由 child context 显式装配并随插件生命周期撤回")
                .check(CLASSES);
    }

    private static String hostType(String packageName, String simpleName) {
        return packageName + "." + simpleName;
    }

    private static void collectAppTypeReferences(Path root,
                                                 String sourcePath,
                                                 Set<String> appTypes,
                                                 List<String> violations) throws IOException {
        Path sourceRoot = root.resolve("pixivdownload-plugin-novel").resolve(sourcePath);
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String code = stripComments(read(source));
                String packageName = packageName(code);
                Set<String> imports = importedNames(code);
                for (String appType : appTypes) {
                    if (referencesFullyQualifiedType(code, appType)
                            || importsType(imports, appType)
                            || samePackageSimpleReference(code, packageName, appType)) {
                        violations.add(root.relativize(source) + " -> " + appType);
                    }
                }
            }
        }
    }

    private static void collectForbiddenResourceReferences(Path root,
                                                           String sourcePath,
                                                           List<String> violations) throws IOException {
        Path sourceRoot = root.resolve("pixivdownload-plugin-novel").resolve(sourcePath);
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String code = stripComments(read(source));
                for (String basename : FORBIDDEN_RESOURCE_BASENAMES) {
                    if (code.contains(basename)) {
                        violations.add(root.relativize(source) + " -> " + basename);
                    }
                }
            }
        }
    }

    private static boolean referencesAppType(String code, String packageName, String appType) {
        return referencesFullyQualifiedType(code, appType)
                || importsType(importedNames(code), appType)
                || samePackageSimpleReference(code, packageName, appType);
    }

    private static boolean referencesFullyQualifiedType(String code, String appType) {
        return identifierReference(appType).matcher(code).find();
    }

    private static boolean samePackageSimpleReference(String code, String packageName, String appType) {
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

    private static Set<String> appOwnedTypes(Path root) throws IOException {
        Path appSourceRoot = root.resolve("pixivdownload-app/src/main/java");
        Set<String> types = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(appSourceRoot)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .map(appSourceRoot::relativize)
                    .map(Path::toString)
                    .filter(path -> !path.endsWith("package-info.java") && !path.endsWith("module-info.java"))
                    .map(path -> path.substring(0, path.length() - ".java".length())
                            .replace('\\', '.').replace('/', '.'))
                    .sorted()
                    .forEach(types::add);
        }
        return Set.copyOf(types);
    }

    private static String stripComments(String source) {
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
                        sanitized.append("\"\"\"");
                        index += 3;
                        state = SourceState.TEXT_BLOCK;
                        continue;
                    }
                    sanitized.append(current);
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
                    sanitized.append(current);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        sanitized.append(source.charAt(index++));
                    } else if (current == '"') {
                        state = SourceState.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (startsWithTripleQuote(source, index)) {
                        sanitized.append("\"\"\"");
                        index += 3;
                        state = SourceState.CODE;
                    } else {
                        sanitized.append(current);
                        index++;
                        if (current == '\\' && index < source.length()) {
                            sanitized.append(source.charAt(index++));
                        }
                    }
                }
                case CHARACTER -> {
                    sanitized.append(current);
                    index++;
                    if (current == '\\' && index < source.length()) {
                        sanitized.append(source.charAt(index++));
                    } else if (current == '\'') {
                        state = SourceState.CODE;
                    }
                }
            }
        }
        return sanitized.toString();
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

    @Test
    @DisplayName("novel-gallery 不依赖 AI/TTS/download-workbench/gallery/duplicate/stats 插件实现")
    void novelGalleryDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.novelgallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.ai..",
                        "top.sywyar.pixivdownload.tts..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.stats..")
                .because("novel-gallery 只消费小说核心服务、核心作品事实与插件契约，"
                        + "AI/TTS 等能力通过 UI slot / 能力缺席语义协作")
                .check(CLASSES);
    }

    @Test
    @DisplayName("novel 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void novelModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(NovelPf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelPluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelGalleryController.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelGalleryService.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelOwnedWorkSearch.class.getName())).isTrue();
        assertThat(CLASSES.contain(NovelBatchService.class.getName())).isTrue();
    }
}
