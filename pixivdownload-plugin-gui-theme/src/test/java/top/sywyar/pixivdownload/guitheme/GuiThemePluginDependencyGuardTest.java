package top.sywyar.pixivdownload.guitheme;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 主题插件依赖边界")
class GuiThemePluginDependencyGuardTest {

    private static final List<String> HOST_PRIVATE_CLASS_RESOURCES = List.of(
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class");
    private static final List<String> PRIVATE_HTTP_SOURCE_REFERENCES = List.of(
            "org.apache.hc.",
            "org.apache.http.",
            "HttpComponentsClientHttpRequestFactory");
    private static final Pattern JAVA_TOKEN = Pattern.compile(
            "\"\"\"(?:\\\\.|(?!\"\"\")[\\s\\S])*\"\"\""
                    + "|\"(?:\\\\.|[^\"\\\\])*\""
                    + "|'(?:\\\\.|[^'\\\\])*'"
                    + "|(?<comment>//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/)");
    private static final Pattern QUALIFIED_NAME_SEPARATOR = Pattern.compile("\\s*\\.\\s*");
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.guitheme");

    @Test
    @DisplayName("生产代码不得依赖 app 实现包")
    void guiThemePluginDoesNotDependOnAppImplementationPackages() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.guitheme..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.gui..",
                        "top.sywyar.pixivdownload.config..",
                        "top.sywyar.pixivdownload.i18n..",
                        "top.sywyar.pixivdownload.plugin.runtime..")
                .because("the theme plugin crosses the host boundary only through plugin-api and PF4J")
                .check(CLASSES);
    }

    @Test
    @DisplayName("生产代码不得依赖宿主私有 HTTP 类型")
    void productionCodeDoesNotDependOnPrivateHttpTypes() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.guitheme..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.apache.hc..", "org.apache.http..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.http.client."
                                + "HttpComponentsClientHttpRequestFactory")
                .because("主题插件只能通过稳定契约消费宿主传输能力")
                .check(CLASSES);
    }

    @Test
    @DisplayName("测试类路径与生产源码不得包含宿主私有 HTTP 实现")
    void classpathAndProductionSourcesExcludeHostPrivateHttpStack() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        for (String resource : HOST_PRIVATE_CLASS_RESOURCES) {
            assertThat(classLoader.getResource(resource)).as(resource).isNull();
        }
        assertProductionSourcesExcludePrivateHttpReferences();
    }

    private static void assertProductionSourcesExcludePrivateHttpReferences() throws IOException {
        Path moduleRoot = moduleRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(moduleRoot.resolve("src/main/java"))) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String code = normalizedSource(Files.readString(source));
                for (String forbidden : PRIVATE_HTTP_SOURCE_REFERENCES) {
                    if (code.contains(forbidden)) {
                        violations.add(moduleRoot.relativize(source) + " -> " + forbidden);
                    }
                }
            }
        }
        assertThat(violations).as("GUI 主题插件生产源码中的宿主私有 HTTP 引用").isEmpty();
    }

    private static Path moduleRoot() {
        Path reactorModule = Path.of("pixivdownload-plugin-gui-theme");
        return Files.isDirectory(reactorModule) ? reactorModule : Path.of(".");
    }

    private static String normalizedSource(String source) {
        Matcher tokens = JAVA_TOKEN.matcher(source);
        StringBuilder code = new StringBuilder(source.length());
        while (tokens.find()) {
            tokens.appendReplacement(
                    code,
                    tokens.group("comment") == null
                            ? Matcher.quoteReplacement(tokens.group())
                            : "");
        }
        return QUALIFIED_NAME_SEPARATOR
                .matcher(tokens.appendTail(code))
                .replaceAll(".");
    }
}
