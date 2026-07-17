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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import[\\t ]+(?:static[\\t ]+)?([A-Za-z0-9_$.*]+)[\\t ]*;");

    private static final List<String> CONCRETE_HOST_RUNTIME_TYPES = List.of(
            "top.sywyar.pixivdownload.common.NetworkUtils",
            "top.sywyar.pixivdownload.common.UuidUtils",
            "top.sywyar.pixivdownload.config.AppRuntimePathProvider",
            "top.sywyar.pixivdownload.config.DebugConfig",
            "top.sywyar.pixivdownload.config.ProxyConfig",
            "top.sywyar.pixivdownload.config.RuntimeFiles",
            "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
            "top.sywyar.pixivdownload.core.appconfig.MultiModeConfig",
            "top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec",
            "top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain",
            "top.sywyar.pixivdownload.core.download.queue.QueueNotAcceptingException",
            "top.sywyar.pixivdownload.core.download.queue.QueueStatusRetention",
            "top.sywyar.pixivdownload.core.download.queue.QueueTaskTracker",
            "top.sywyar.pixivdownload.setup.HostRequestOwnerIdentityResolver",
            "top.sywyar.pixivdownload.setup.SetupService",
            "top.sywyar.pixivdownload.setup.guest.GuestAccessGuard",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityService");

    /**
     * These modules still consume other app-owned domain services. The allowlist is only a ceiling for that
     * existing debt; concrete configuration, path, identity, visibility and shared queue runtime types remain forbidden by
     * {@link #officialPluginsUseSharedRuntimeContracts()}.
     */
    private static final Set<String> APP_ARTIFACT_TRANSITION_ALLOWLIST = Set.of(
            "pixivdownload-plugin-download-workbench",
            "pixivdownload-plugin-novel");

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
            try (Stream<Path> sources = Files.walk(sourceRoot)) {
                sources.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> collectConcreteRuntimeViolations(
                                repositoryRoot, module, path, violations));
            }
        }

        assertThat(violations)
                .as("official plugins must consume core-api/plugin-api ports instead of app implementations")
                .isEmpty();
    }

    @Test
    @DisplayName("只有已登记的领域依赖模块可暂时依赖 app artifact")
    void appArtifactDependencyCannotSpreadToOtherOfficialPlugins() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Set<String> appConsumers = new LinkedHashSet<>();

        for (String module : officialPluginModules(repositoryRoot)) {
            Set<String> dependencies = dependencyArtifactIds(
                    repositoryRoot.resolve(module).resolve("pom.xml"));
            if (dependencies.contains("PixivDownload")) {
                appConsumers.add(module);
            }
        }

        assertThat(appConsumers).allMatch(APP_ARTIFACT_TRANSITION_ALLOWLIST::contains);
        assertThat(appConsumers)
                .doesNotContain("pixivdownload-plugin-douyin", "pixivdownload-plugin-duplicate",
                        "pixivdownload-plugin-tts");
    }

    private static void collectConcreteRuntimeViolations(Path repositoryRoot,
                                                         String module,
                                                         Path source,
                                                         List<String> violations) {
        String content = read(source);
        for (String forbiddenType : concreteRuntimeReferences(content)) {
            violations.add(module + ":" + repositoryRoot.relativize(source) + " -> " + forbiddenType);
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

    @Test
    @DisplayName("源码扫描忽略注释与字符串字面量中的宿主类名")
    void sourceScannerIgnoresCommentsAndStringLiterals() {
        String source = "package example;\n"
                + "// top.sywyar.pixivdownload.config.ProxyConfig\n"
                + "/** top.sywyar.pixivdownload.config.RuntimeFiles */\n"
                + "class Example {\n"
                + "  String name = \"top.sywyar.pixivdownload.setup.guest.GuestAccessGuard\";\n"
                + "  String block = \"\"\"\n"
                + "      top.sywyar.pixivdownload.core.appconfig.DownloadConfig\n"
                + "      \"\"\";\n"
                + "}\n";

        assertThat(concreteRuntimeReferences(source)).isEmpty();
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
                "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityService");
    }

    private static Set<String> concreteRuntimeReferences(String source) {
        String productionCode = stripCommentsAndLiterals(source);
        Set<String> imports = new LinkedHashSet<>();
        var importMatcher = IMPORT_DECLARATION.matcher(productionCode);
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
            Pattern qualifiedReference = Pattern.compile(
                    "(?<![\\p{Alnum}_$])" + Pattern.quote(forbiddenType)
                            + "(?![\\p{Alnum}_$])");
            if (imported || qualifiedReference.matcher(productionCode).find()) {
                references.add(forbiddenType);
            }
        }
        return references;
    }

    private static String stripCommentsAndLiterals(String source) {
        StringBuilder sanitized = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    appendMasked(sanitized, current);
                    appendMasked(sanitized, next);
                    index += 2;
                    while (index < source.length()
                            && source.charAt(index) != '\n'
                            && source.charAt(index) != '\r') {
                        appendMasked(sanitized, source.charAt(index++));
                    }
                    continue;
                }
                if (next == '*') {
                    appendMasked(sanitized, current);
                    appendMasked(sanitized, next);
                    index += 2;
                    while (index < source.length()) {
                        char value = source.charAt(index);
                        if (value == '*' && index + 1 < source.length()
                                && source.charAt(index + 1) == '/') {
                            appendMasked(sanitized, value);
                            appendMasked(sanitized, '/');
                            index += 2;
                            break;
                        }
                        appendMasked(sanitized, value);
                        index++;
                    }
                    continue;
                }
            }
            if (current == '"') {
                boolean textBlock = startsWithTripleQuote(source, index);
                int delimiterLength = textBlock ? 3 : 1;
                for (int i = 0; i < delimiterLength; i++) {
                    appendMasked(sanitized, source.charAt(index++));
                }
                while (index < source.length()) {
                    if (textBlock && startsWithTripleQuote(source, index)) {
                        for (int i = 0; i < 3; i++) {
                            appendMasked(sanitized, source.charAt(index++));
                        }
                        break;
                    }
                    char value = source.charAt(index++);
                    appendMasked(sanitized, value);
                    if (value == '\\' && index < source.length()) {
                        appendMasked(sanitized, source.charAt(index++));
                    } else if (!textBlock && value == '"') {
                        break;
                    }
                }
                continue;
            }
            if (current == '\'') {
                appendMasked(sanitized, current);
                index++;
                while (index < source.length()) {
                    char value = source.charAt(index++);
                    appendMasked(sanitized, value);
                    if (value == '\\' && index < source.length()) {
                        appendMasked(sanitized, source.charAt(index++));
                    } else if (value == '\'') {
                        break;
                    }
                }
                continue;
            }
            sanitized.append(current);
            index++;
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

    private static Set<String> dependencyArtifactIds(Path pom) throws IOException {
        Document document = parsePom(pom);
        Set<String> artifactIds = new LinkedHashSet<>();
        NodeList dependencyGroups = document.getElementsByTagNameNS("*", "dependencies");
        for (int groupIndex = 0; groupIndex < dependencyGroups.getLength(); groupIndex++) {
            Node dependencies = dependencyGroups.item(groupIndex);
            String parentName = localName(dependencies.getParentNode());
            if (!"project".equals(parentName) && !"profile".equals(parentName)) {
                continue;
            }
            NodeList children = dependencies.getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                Node child = children.item(childIndex);
                if (!(child instanceof Element dependency)
                        || !"dependency".equals(localName(dependency))) {
                    continue;
                }
                String artifactId = directChildText(dependency, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    artifactIds.add(artifactId.trim());
                }
            }
        }
        return artifactIds;
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
}
