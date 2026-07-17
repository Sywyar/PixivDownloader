package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Repository guard for copyable third-party templates that are intentionally outside the product reactor. */
@DisplayName("第三方插件模板结构与依赖边界")
class PluginTemplateBoundaryTest {

    private static final List<String> TEMPLATE_NAMES = List.of(
            "minimal-feature-plugin", "download-type-plugin");
    private static final Map<String, Set<String>> EXPECTED_DEPENDENCIES = Map.of(
            "minimal-feature-plugin", Set.of(
                    "org.springframework.boot:spring-boot-dependencies:import",
                    "top.sywyar.lovepopup:pixivdownload-plugin-api:provided",
                    "org.pf4j:pf4j:provided",
                    "org.springframework:spring-context:provided",
                    "org.springframework:spring-web:provided",
                    "org.springframework:spring-webmvc:provided",
                    "org.junit.jupiter:junit-jupiter:test"),
            "download-type-plugin", Set.of(
                    "org.springframework.boot:spring-boot-dependencies:import",
                    "top.sywyar.lovepopup:pixivdownload-plugin-api:provided",
                    "org.pf4j:pf4j:provided",
                    "org.springframework:spring-context:provided",
                    "org.springframework:spring-web:provided",
                    "org.springframework:spring-webmvc:provided",
                    "jakarta.servlet:jakarta.servlet-api:provided",
                    "com.fasterxml.jackson.core:jackson-databind:provided",
                    "org.junit.jupiter:junit-jupiter:test"));
    private static final Pattern HOST_IMPORT = Pattern.compile(
            "^import\\s+(top\\.sywyar\\.pixivdownload\\.[^;]+);",
            Pattern.MULTILINE);
    private static final Pattern PF4J_IMPORT = Pattern.compile(
            "^import\\s+org\\.pf4j\\.[^;]+;",
            Pattern.MULTILINE);

    @Test
    @DisplayName("模板只依赖 plugin-api 与宿主提供的规范依赖")
    void templatesUseOnlyStableProvidedDependencies() throws Exception {
        for (String templateName : TEMPLATE_NAMES) {
            Path template = repositoryRoot().resolve("plugin-templates").resolve(templateName);
            assertThat(template).isDirectory();
            var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(template.resolve("pom.xml").toFile());
            assertThat(document.getElementsByTagName("parent").getLength())
                    .as(templateName + " must not inherit the repository parent")
                    .isZero();

            List<String> coordinates = new ArrayList<>();
            NodeList dependencies = document.getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String groupId = text(dependency, "groupId");
                String artifactId = text(dependency, "artifactId");
                String scope = text(dependency, "scope");
                coordinates.add(groupId + ":" + artifactId + ":" + scope);
            }
            assertThat(coordinates)
                    .as(templateName + " dependency coordinates")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_DEPENDENCIES.get(templateName));
        }
    }

    @Test
    @DisplayName("模板生产代码不导入宿主实现且 PF4J 仅出现在入口类")
    void templateSourcesDoNotImportHostImplementations() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String templateName : TEMPLATE_NAMES) {
            Path sourceRoot = repositoryRoot().resolve("plugin-templates")
                    .resolve(templateName).resolve("src/main/java");
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> inspectSource(path, violations));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("模板只示范插件自有独立画廊页且不暴露已过时的 unified 接口")
    void templatesDoNotConsumeDeprecatedUnifiedGalleryApi() throws IOException {
        Path templates = repositoryRoot().resolve("plugin-templates");
        List<String> violations = new ArrayList<>();
        List<String> deprecatedMarkers = List.of(
                "/api/gallery/unified/",
                "unifiedGallery(",
                "gallery.unified-deprecated");
        for (String templateName : TEMPLATE_NAMES) {
            Path productionRoot = templates.resolve(templateName).resolve("src/main");
            try (Stream<Path> files = Files.walk(productionRoot)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    String content = read(path);
                    for (String marker : deprecatedMarkers) {
                        if (content.contains(marker)) {
                            violations.add(relative(path) + " -> " + marker);
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("模板描述符必填项完整且模板不进入产品构建和分发")
    void descriptorsAreCompleteAndTemplatesStayOutsideDistribution() throws IOException {
        Path root = repositoryRoot();
        for (String templateName : TEMPLATE_NAMES) {
            Path descriptorPath = root.resolve("plugin-templates")
                    .resolve(templateName).resolve("src/main/resources/plugin.properties");
            Properties descriptor = new Properties();
            try (var input = Files.newInputStream(descriptorPath)) {
                descriptor.load(input);
            }
            assertThat(descriptor)
                    .containsKeys("plugin.id", "plugin.version", "plugin.requires", "plugin.class");
            assertThat(descriptor.getProperty("plugin.id")).matches("[a-z][a-z0-9-]*");

            var runtimeDescriptor = PluginPackageReader.inspectDescriptor(descriptorPath);
            assertThat(runtimeDescriptor.externalValidationErrors()).isEmpty();
            assertThat(runtimeDescriptor.isApiCompatible()).isTrue();
            assertThat(runtimeDescriptor.id()).isEqualTo(descriptor.getProperty("plugin.id"));
        }

        assertThat(read(root.resolve("pom.xml")))
                .doesNotContain("<module>plugin-templates</module>")
                .doesNotContain("<module>plugin-templates/");
        assertThat(read(root.resolve("pixivdownload-official-plugins/pom.xml")))
                .doesNotContain("example-minimal-plugin")
                .doesNotContain("example-download-plugin");
        assertThat(read(root.resolve("scripts/plugin-distribution-common.ps1")))
                .doesNotContain("example-minimal")
                .doesNotContain("example-download");
    }

    @Test
    @DisplayName("应用运行期类路径不包含模板类和模板静态资源")
    void applicationClasspathExcludesTemplates() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(canLoad(loader,
                "com.example.pixivdownload.minimal.ExampleMinimalPf4jPlugin")).isFalse();
        assertThat(canLoad(loader,
                "com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin")).isFalse();
        assertThat(loader.getResource("static/example-minimal.html")).isNull();
        assertThat(loader.getResource("static/example-download-gallery.html")).isNull();
        assertThat(loader.getResource("i18n/web/example-download.properties")).isNull();
    }

    private static void inspectSource(Path path, List<String> violations) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            Matcher hostImports = HOST_IMPORT.matcher(source);
            while (hostImports.find()) {
                if (!hostImports.group(1).startsWith("top.sywyar.pixivdownload.plugin.api.")) {
                    violations.add(relative(path) + " imports " + hostImports.group(1));
                }
            }
            if (PF4J_IMPORT.matcher(source).find()
                    && !path.getFileName().toString().endsWith("Pf4jPlugin.java")) {
                violations.add(relative(path) + " imports PF4J outside its entry point");
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String text(Element parent, String tagName) {
        NodeList values = parent.getElementsByTagName(tagName);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().trim();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
    }

    private static String relative(Path path) {
        return repositoryRoot().relativize(path.toAbsolutePath().normalize()).toString();
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("pixivdownload-app"))
                    && Files.isDirectory(current.resolve("plugin-templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
