package top.sywyar.pixivdownload.douyin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.download.DouyinQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Douyin 外置插件模块依赖边界")
class DouyinPluginDependencyGuardTest {

    private static final String[] HOST_PRIVATE_CLASS_RESOURCES = {
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class"
    };
    private static final String APP_PREFIX = "top.sywyar.pixivdownload.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.douyin");

    @Test
    @DisplayName("生产代码不得依赖宿主工具、配置实现或旧队列实现包")
    void productionCodeDoesNotDependOnHostImplementationPackages() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.douyin..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.common..",
                        "top.sywyar.pixivdownload.setup..",
                        "top.sywyar.pixivdownload.core.appconfig..",
                        "top.sywyar.pixivdownload.core.download.queue..")
                .because("Douyin 是外置插件，只能消费 core-api / plugin-api 中性契约")
                .check(CLASSES);
    }

    @Test
    @DisplayName("生产代码不得依赖宿主私有 HTTP 类型")
    void productionCodeDoesNotDependOnPrivateHttpTypes() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.douyin..")
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
    @DisplayName("测试类路径不得包含 app 与宿主私有 HTTP 实现")
    void testClasspathExcludesHostApplicationAndPrivateHttpStack() {
        ClassLoader classLoader = getClass().getClassLoader();
        for (String resource : HOST_PRIVATE_CLASS_RESOURCES) {
            assertThat(classLoader.getResource(resource)).as(resource).isNull();
        }
    }

    @Test
    @DisplayName("POM 与生产源码不得恢复 PixivDownload artifact 或已移除宿主类型")
    void moduleDoesNotRestoreAppArtifactOrConcreteHostImports() throws IOException {
        Path moduleRoot = moduleRoot();
        String pom = Files.readString(moduleRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).doesNotContain(
                "<artifactId>PixivDownload</artifactId>",
                "<artifactId>httpclient5</artifactId>",
                "<artifactId>pixivdownload-plugin-runtime</artifactId>",
                "<artifactId>mybatis-spring-boot-starter</artifactId>");

        String productionSource;
        try (Stream<Path> files = Files.walk(moduleRoot.resolve("src/main/java"))) {
            productionSource = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(DouyinPluginDependencyGuardTest::read)
                    .reduce("", (left, right) -> left + '\n' + right);
        }
        assertThat(productionSource).doesNotContain(
                "org.apache.hc.",
                "org.apache.http.",
                "HttpComponentsClientHttpRequestFactory",
                "DouyinRestTemplateFactory",
                "ManagedPluginRestTemplate",
                "PluginRestTemplateAdapter",
                appType("config.ProxyConfig"),
                appType("config.RuntimeFiles"),
                appType("core.appconfig.DownloadConfig"),
                appType("core.appconfig.MultiModeConfig"),
                appType("core.db.pathprefix.PathPrefixCodec"),
                appType("core.download.queue."),
                appType("common.NetworkUtils"),
                appType("common.UuidUtils"),
                appType("setup.SetupService"));
        assertThat(productionSource).contains("OutboundHttpClientFactory");
    }

    @Test
    @DisplayName("第三方独立构建只消费 SDK BOM 与 provided API")
    void standaloneBuildConsumesOnlyPublishedSdkAndProvidedApis() throws IOException {
        String pom = Files.readString(moduleRoot().resolve("third-party-pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).contains(
                "<artifactId>pixivdownload-sdk-bom</artifactId>",
                "<artifactId>pixivdownload-core-api</artifactId>",
                "<artifactId>pixivdownload-plugin-api</artifactId>",
                "<scope>provided</scope>");
        assertThat(pom).doesNotContain(
                "<parent>",
                "<relativePath>",
                "<repositories>",
                "<pluginRepositories>",
                "<artifactId>PixivDownload</artifactId>",
                "<artifactId>pixivdownload-plugin-runtime</artifactId>");
    }

    @Test
    @DisplayName("Douyin 队列操作实现非空且由插件生命周期托管")
    void queueOperationsArePluginManaged() {
        assertThat(CLASSES.contain(DouyinQueueOperations.class.getName())).isTrue();
        classes()
                .that().areAssignableTo(QueueOperations.class)
                .and().areNotInterfaces()
                .should().beAnnotatedWith(PluginManagedBean.class)
                .because("下载类型是否在场与对应队列操作必须随同一插件生命周期发布和撤回")
                .check(CLASSES);
    }

    private static Path moduleRoot() {
        Path reactorModule = Path.of("pixivdownload-plugin-douyin");
        return Files.isDirectory(reactorModule) ? reactorModule : Path.of(".");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read " + path, failure);
        }
    }

    private static String appType(String relativeName) {
        return APP_PREFIX + relativeName;
    }
}
