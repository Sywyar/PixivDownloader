package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

@DisplayName("社区版本投稿、许可证与市场目录")
class VersionSubmissionTest {
    @TempDir Path directory;

    @Test
    @DisplayName("只接收作者字段，复用宿主版本语法和三种构建定位")
    void validatesSubmission() throws Exception {
        for (String profile : List.of("maven-java17-v1", "gradle-java17-v1", "sbt-java17-v1")) {
            var node = fixture();
            ((ObjectNode) node.get("buildProfile")).put("id", profile);
            var parsed = read(node);
            assertThat(parsed.buildProfile().id()).isEqualTo(profile);
            parsed.verifyPreviousSource(null);
            assertThatThrownBy(() -> parsed.verifyPreviousSource("2".repeat(40))).isInstanceOf(ContractException.class);
        }
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("descriptor", "author supplied"),
                node -> node.putArray("declaredRiskSignals").add("NETWORK"),
                node -> node.put("githubAccount", "author supplied"),
                node -> node.put("version", "1.0"),
                node -> node.put("version", " 1.0.0"),
                node -> ((ObjectNode) node.get("source")).put("commit", "main"),
                node -> ((ObjectNode) node.get("source")).put("repository", "https://user:password@github.com/a/b"),
                node -> ((ObjectNode) node.get("source")).put("repository", "https://github.com/a/.."),
                node -> ((ObjectNode) node.get("source")).put("repository", "https://github.com/./b"),
                node -> ((ObjectNode) node.get("source")).put("repository", "https://github.com/../b"),
                node -> ((ObjectNode) node.get("package")).put("url", "http://example.org/plugin.jar"),
                node -> ((ObjectNode) node.get("buildProfile")).put("artifactPath", "../outside.jar"),
                node -> ((ObjectNode) node.get("buildProfile")).put("id", "maven-gradle-java17-v1"),
                node -> ((ObjectNode) node.get("market")).put("category", "unknown"),
                node -> ((ObjectNode) node.get("market")).put("defaultLocale", "ja"),
                node -> ((ObjectNode) node.get("market")).put("rating", 5),
                node -> ((ObjectNode) node.get("market").get("displayName")).put("en_US", "Bad locale"),
                node -> ((ObjectNode) node.get("market")).putArray("tags").add("ai").add("ai"),
                node -> ((ObjectNode) node.get("market")).putArray("tags").add("unknown")
        )) {
            var node = fixture();
            mutation.accept(node);
            assertThatThrownBy(() -> read(node)).isInstanceOf(ContractException.class);
        }
        var longId = fixture();
        longId.put("pluginId", "a".repeat(200));
        longId.put("version", "2.3.4-" + "a".repeat(150));
        assertThat(read(longId).pluginId()).hasSize(200);
    }

    @Test
    @DisplayName("显示文本按 Unicode 码点计数，支持宿主语言目录之外的语言并保持回退")
    void handlesUnicodeAndLocales() throws Exception {
        for (var item : Map.of("displayName", 128, "summary", 512, "description", 8192).entrySet()) {
            var node = fixture();
            var market = (ObjectNode) node.get("market");
            market.putObject(item.getKey()).put("en", "😀".repeat(item.getValue()));
            assertThatCode(() -> read(node)).doesNotThrowAnyException();
            ((ObjectNode) market.get(item.getKey())).put("en", "😀".repeat(item.getValue()) + "x");
            assertThatThrownBy(() -> read(node)).isInstanceOf(ContractException.class);
        }
        var map = new LinkedHashMap<String, String>();
        map.put("ar", "Arabic"); map.put("en", "English"); map.put("zh", "Chinese");
        assertThat(MarketMetadata.text(map, "ar-EG", "")).isEqualTo("Arabic");
        assertThat(MarketMetadata.text(map, "fr-FR", "")).isEqualTo("Chinese");
        map.remove("zh");
        assertThat(MarketMetadata.text(map, "fr", "")).isEqualTo("English");
        map.remove("en");
        assertThat(MarketMetadata.text(map, "fr", "")).isEqualTo("Arabic");
        var node = fixture();
        ((ObjectNode) node.get("market")).putObject("description").put("en", "First\nSecond");
        assertThatCode(() -> read(node)).doesNotThrowAnyException();
        ((ObjectNode) node.get("market").get("summary")).put("en", "First\nSecond");
        assertThatThrownBy(() -> read(node)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("SPDX 表达式支持目录外模板和固定自定义文本，不把语法当许可批准")
    void validatesLicenseExpressions() {
        for (String expression : List.of("MIT", "mit OR Apache-2.0", "(MIT AND BSD-2-Clause) OR MPL-2.0",
                "GPL-2.0+ WITH Classpath-exception-2.0", "Zlib", "MIT and (Zlib or ISC)")) {
            assertThatCode(() -> SpdxExpression.validate(expression, Set.of())).doesNotThrowAnyException();
        }
        assertThatCode(() -> SpdxExpression.validate("LicenseRef-Custom OR MIT", Set.of("LicenseRef-Custom"))).doesNotThrowAnyException();
        for (String expression : List.of("", "MIT OR", "MIT MIT", "(MIT OR Zlib", "MIT)",
                "MIT AND ()", "MIT WITH Unknown", "(MIT OR Zlib) WITH Classpath-exception-2.0",
                "MIT WITH Classpath-exception-2.0 WITH Classpath-exception-2.0", "MIT +", "MIT And Zlib",
                "LicenseRef-Missing", "MIT\nOR Zlib", "Unknown-License", "MITWITHClasspath-exception-2.0")) {
            assertThatThrownBy(() -> SpdxExpression.validate(expression, Set.of())).isInstanceOf(ContractException.class);
        }
        assertThatCode(() -> SpdxExpression.validate("(".repeat(4000) + "MIT" + ")".repeat(4000), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("内置模板逐份核对实际摘要且不能覆盖已有 LICENSE")
    void validatesTemplatesWithoutOverwriting() throws Exception {
        assertThat(LicenseTemplates.available()).hasSize(15);
        for (var template : LicenseTemplates.available()) {
            byte[] bytes = LicenseTemplates.bytes(template.id());
            assertThat(bytes.length).isEqualTo(template.size());
            assertThat(CommunityJson.sha256(bytes)).isEqualTo(template.sha256());
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isNotBlank();
            assertThat(template.source()).contains("/" + ControlledCatalogs.resource("spdx.json").get("sourceCommit").textValue() + "/");
        }
        Path file = directory.resolve("LICENSE");
        LicenseTemplates.create("MIT", file);
        byte[] original = Files.readAllBytes(file);
        assertThatThrownBy(() -> LicenseTemplates.create("Apache-2.0", file)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    private static ObjectNode fixture() throws Exception {
        try (var input = VersionSubmissionTest.class.getResourceAsStream("/community/v1/vectors/submission.json")) {
            return (ObjectNode) CommunityJson.parse(CommunityJson.Kind.SUBMISSION,
                    java.util.Objects.requireNonNull(input).readAllBytes()).value();
        }
    }

    @Test
    @DisplayName("从真实包提取声明与依赖快照，未知声明不混入固定社区目录")
    void extractsFrozenDescriptor() throws Exception {
        VersionSubmission submission = read(fixture());
        Path artifact = directory.resolve("plugin.jar");
        for (String declaration : List.of("", "pixiv.risk-signals=\n", "pixiv.risk-signals=FILE_WRITE,NETWORK\n")) {
            writePackage(artifact, declaration);
            var descriptor = top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader.inspect(artifact).descriptor();
            var snapshot = DescriptorSnapshot.from(descriptor, submission);
            assertThat(snapshot.riskDeclaration().present()).isEqualTo(!declaration.isEmpty());
            assertThat(snapshot.requiredSdk()).isEqualTo("1.0");
            assertThat(snapshot.dependencies()).extracting(top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef::pluginId)
                    .containsExactly("alpha", "zeta");
        }
        writePackage(artifact, "pixiv.risk-signals=FUTURE_TOKEN\n");
        var descriptor = top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader.inspect(artifact).descriptor();
        assertThat(descriptor.riskDeclaration().signals()).containsExactly("FUTURE_TOKEN");
        assertThatThrownBy(() -> DescriptorSnapshot.from(descriptor, submission)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("实际 LICENSE 字节独立于模板核对，篡改与丢失均拒绝")
    void verifiesSourceLicenseBytes() throws Exception {
        byte[] bytes = "Copyright Example 2030\nCustom legal text\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("LICENSE"), bytes);
        var reference = top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference.of("LICENSE", bytes);
        var license = new VersionSubmission.License("LicenseRef-Custom", List.of(reference), Map.of("LicenseRef-Custom", reference));
        license.validate();
        license.verifySourceFiles(directory);
        byte[] changed = bytes.clone();
        changed[0] ^= 1;
        Files.write(directory.resolve("LICENSE"), changed);
        assertThatThrownBy(() -> license.verifySourceFiles(directory)).isInstanceOf(ContractException.class);
        Files.delete(directory.resolve("LICENSE"));
        assertThatThrownBy(() -> license.verifySourceFiles(directory)).isInstanceOf(java.io.IOException.class);
    }

    private static void writePackage(Path path, String declaration) throws Exception {
        try (var zip = new java.util.jar.JarOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new java.util.jar.JarEntry("plugin.properties"));
            zip.write(("plugin.id=example-minimal\nplugin.version=2.3.4\nplugin.class=example.Plugin\n"
                    + "plugin.requires=1.0\nplugin.dependencies=zeta?@1.0,alpha@1.0\n"
                    + "pixiv.display-name-key=plugin.name\npixiv.kind=feature\n"
                    + "pixiv.execution-mode=declarative-process\n" + declaration).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
    private static VersionSubmission read(ObjectNode node) {
        return VersionSubmission.read(CommunityJson.parse(CommunityJson.Kind.SUBMISSION, CommunityJson.encode(node)));
    }
}
