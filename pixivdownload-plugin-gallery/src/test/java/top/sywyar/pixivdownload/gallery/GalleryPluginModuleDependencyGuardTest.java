package top.sywyar.pixivdownload.gallery;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 模块边界守卫")
class GalleryPluginModuleDependencyGuardTest {

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package[\\t ]+([A-Za-z0-9_$.]+)[\\t ]*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import[\\t ]+(?:static[\\t ]+)?([A-Za-z0-9_$.*]+)[\\t ]*;");
    private static final Pattern APP_ARTIFACT = Pattern.compile(
            "<artifactId>\\s*PixivDownload\\s*</artifactId>");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.gallery");

    private static final Set<String> FORBIDDEN_FILE_READ_METHODS = Set.of(
            "readString", "readAllBytes", "readAllLines", "lines",
            "newBufferedReader", "newInputStream");

    private static final DescribedPredicate<JavaMethodCall> READS_LOCAL_FILES_DIRECTLY =
            new DescribedPredicate<>("调用 java.nio.file.Files 的本地文件读取 / 打开方法") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().getFullName().equals("java.nio.file.Files")
                            && FORBIDDEN_FILE_READ_METHODS.contains(call.getName());
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
    @DisplayName("gallery 托管 Bean 不得直连数据库底层")
    void galleryManagedBeansDoNotAccessRawDatabaseDirectly() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.ibatis..",
                        "top.sywyar.pixivdownload.core.schedule.db..",
                        "top.sywyar.pixivdownload.core.stats.db..")
                .because("gallery 读取核心作品事实只能经 core owned repository/service 与 plugin.api 契约，"
                        + "不得自建 JDBC/MyBatis 访问核心表")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery POM 不得依赖 PixivDownload app artifact")
    void galleryPomDoesNotDependOnAppArtifact() {
        String pom = read(repositoryRoot().resolve("pixivdownload-plugin-gallery/pom.xml"));

        assertThat(APP_ARTIFACT.matcher(pom).find()).isFalse();
    }

    @Test
    @DisplayName("gallery 生产与测试源码不得引用 app owned 类型")
    void gallerySourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path root = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(root);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes).as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        collectAppTypeReferences(root, "src/main/java", appTypes, violations);
        collectAppTypeReferences(root, "src/test/java", appTypes, violations);

        assertThat(violations)
                .as("gallery must compile and test only against stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("app FQN 扫描忽略注释并保留普通字符串与文本块字符串")
    void appTypeReferenceScannerIgnoresCommentsAndPreservesStringLiterals() {
        String appType = "example.host.AppOwnedType";
        String commentsOnly = "// example.host.AppOwnedType\n"
                + "/* example.host.AppOwnedType */\n";
        String ordinaryString = "String name = \"// example.host.AppOwnedType\";";
        String textBlockString = "String name = \"\"\"\n"
                + "/* example.host.AppOwnedType */\n"
                + "\"\"\";";

        assertThat(referencesFullyQualifiedType(stripComments(commentsOnly), appType)).isFalse();
        assertThat(referencesFullyQualifiedType(stripComments(ordinaryString), appType)).isTrue();
        assertThat(referencesFullyQualifiedType(stripComments(textBlockString), appType)).isTrue();
        assertThat(importsType(Set.of("example.host.*"), appType)).isTrue();
        assertThat(importsType(Set.of("example.other.*"), appType)).isFalse();
    }

    @Test
    @DisplayName("gallery 不得直接读取本地作品文件")
    void galleryMustUseWorkAssetServiceForSidecarAndFiles() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().callMethodWhere(READS_LOCAL_FILES_DIRECTLY)
                .because("gallery 的作品文件枚举 / 读取只能经 WorkAssetService，不得自行读本地文件")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 模块不依赖 duplicate / download-workbench / stats / novel 插件实现")
    void galleryModuleDoesNotDependOnOtherFeatureImplementations() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.gallery..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.stats..",
                        "top.sywyar.pixivdownload.novel..")
                .because("gallery 只消费核心作品事实与插件契约，不应依赖其它功能插件实现")
                .check(CLASSES);
    }

    @Test
    @DisplayName("gallery 模块应包含插件本体、PF4J 主类、配置类和业务 Bean（防空跑）")
    void galleryModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(GalleryPf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryPluginConfiguration.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryController.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryService.class.getName())).isTrue();
        assertThat(CLASSES.contain(GalleryBatchService.class.getName())).isTrue();
    }

    private static void collectAppTypeReferences(Path root,
                                                 String sourcePath,
                                                 Set<String> appTypes,
                                                 List<String> violations) throws IOException {
        Path sourceRoot = root.resolve("pixivdownload-plugin-gallery").resolve(sourcePath);
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
                + "(?![\\p{Alnum}_$])");
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
}
