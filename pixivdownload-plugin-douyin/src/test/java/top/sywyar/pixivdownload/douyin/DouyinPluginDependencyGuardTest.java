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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Douyin 外置插件模块依赖边界")
class DouyinPluginDependencyGuardTest {

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
    @DisplayName("POM 与生产源码不得恢复 PixivDownload artifact 或已移除宿主类型")
    void moduleDoesNotRestoreAppArtifactOrConcreteHostImports() throws IOException {
        Path moduleRoot = moduleRoot();
        String pom = Files.readString(moduleRoot.resolve("pom.xml"));
        assertThat(pom).doesNotContain("<artifactId>PixivDownload</artifactId>");

        String productionSource;
        try (Stream<Path> files = Files.walk(moduleRoot.resolve("src/main/java"))) {
            productionSource = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(DouyinPluginDependencyGuardTest::read)
                    .reduce("", (left, right) -> left + '\n' + right);
        }
        assertThat(productionSource).doesNotContain(
                "top.sywyar.pixivdownload.config.ProxyConfig",
                "top.sywyar.pixivdownload.config.RuntimeFiles",
                "top.sywyar.pixivdownload.core.appconfig.DownloadConfig",
                "top.sywyar.pixivdownload.core.appconfig.MultiModeConfig",
                "top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec",
                "top.sywyar.pixivdownload.core.download.queue.",
                "top.sywyar.pixivdownload.common.NetworkUtils",
                "top.sywyar.pixivdownload.common.UuidUtils",
                "top.sywyar.pixivdownload.setup.SetupService");
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
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read " + path, failure);
        }
    }
}
