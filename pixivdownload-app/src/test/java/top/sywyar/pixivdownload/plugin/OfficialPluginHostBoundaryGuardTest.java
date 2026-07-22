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
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*package[\\t ]+([A-Za-z0-9_$.]+)[\\t ]*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^[\\t ]*import[\\t ]+(?:static[\\t ]+)?([A-Za-z0-9_$.*]+)[\\t ]*;");
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
            "top.sywyar.pixivdownload.setup.guest.GuestInviteSession",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeArgumentResolver",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityService",
            "top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityWebConfiguration");

    /** Remaining module still consuming app-owned domain services. */
    private static final Set<String> APP_ARTIFACT_TRANSITION_ALLOWLIST = Set.of(
            "pixivdownload-plugin-download-workbench");

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
    @DisplayName("非过渡官方插件生产源码不得引用 app owned 类型")
    void officialPluginSourcesDoNotReferenceAppOwnedTypes() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Set<String> appTypes = appOwnedTypes(repositoryRoot);
        List<String> violations = new ArrayList<>();

        assertThat(appTypes).as("app owned production FQN set must be non-vacuous")
                .hasSizeGreaterThan(400);
        for (String module : officialPluginModules(repositoryRoot)) {
            if (!APP_ARTIFACT_TRANSITION_ALLOWLIST.contains(module)) {
                collectAppTypeReferences(repositoryRoot, module, appTypes, violations);
            }
        }

        assertThat(violations)
                .as("official plugin production sources must use stable shared contracts")
                .isEmpty();
    }

    @Test
    @DisplayName("所有权扫描忽略注释并保留字符串类名")
    void appOwnedTypeScannerIgnoresCommentsAndPreservesStrings() {
        String appType = "example.host.AppOwnedType";
        String commentsOnly = "// example.host.AppOwnedType\n"
                + "/* example.host.AppOwnedType */\n";
        String reflectionString = "Class.forName(\"example.host.AppOwnedType$Nested\");";

        assertThat(referencesFullyQualifiedType(stripComments(commentsOnly), appType)).isFalse();
        assertThat(referencesFullyQualifiedType(stripComments(reflectionString), appType)).isTrue();
        assertThat(importsType(Set.of("example.host.*"), appType)).isTrue();
    }

    @Test
    @DisplayName("只有 download-workbench 可暂时依赖 app artifact")
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

        assertThat(appConsumers)
                .containsExactlyInAnyOrderElementsOf(APP_ARTIFACT_TRANSITION_ALLOWLIST);
        assertThat(appConsumers)
                .doesNotContain("pixivdownload-plugin-douyin", "pixivdownload-plugin-duplicate",
                        "pixivdownload-plugin-novel", "pixivdownload-plugin-tts");
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
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String code = stripComments(read(source));
                String packageName = packageName(code);
                Set<String> imports = importedNames(code);
                for (String appType : appTypes) {
                    if (referencesFullyQualifiedType(code, appType)
                            || importsType(imports, appType)
                            || samePackageSimpleReference(code, packageName, appType)) {
                        violations.add(module + ":" + repositoryRoot.relativize(source) + " -> " + appType);
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
                + "(?![\\p{Alnum}_])");
    }

    private static String packageName(String code) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Set<String> appOwnedTypes(Path repositoryRoot) throws IOException {
        Path appSourceRoot = repositoryRoot.resolve("pixivdownload-app/src/main/java");
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
        String productionCode = stripComments(source);
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
            if (imported || identifierReference(forbiddenType).matcher(productionCode).find()) {
                references.add(forbiddenType);
            }
        }
        return references;
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
