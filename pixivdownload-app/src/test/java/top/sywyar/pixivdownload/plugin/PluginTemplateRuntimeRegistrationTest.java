package top.sywyar.pixivdownload.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.QueueOperationsCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 把仓库外置模板当作真实第三方类加载，验证宿主注册与子上下文边界。 */
@DisplayName("第三方插件模板真实宿主注册")
class PluginTemplateRuntimeRegistrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("模板只靠契约类路径注册下载扩展、运行期队列能力、route/static/i18n/schema 并组建子上下文")
    void templatesRegisterThroughRealHostContracts() throws Exception {
        long minimalGeneration = 3L;
        long downloadGeneration = 7L;
        try (LoadedTemplate minimal = compileTemplate(
                "minimal-feature-plugin",
                "com.example.pixivdownload.minimal.ExampleMinimalPlugin",
                "com.example.pixivdownload.minimal.ExampleMinimalConfiguration");
             LoadedTemplate download = compileTemplate(
                     "download-type-plugin",
                     "com.example.pixivdownload.downloadtype.ExampleDownloadPlugin",
                     "com.example.pixivdownload.downloadtype.ExampleDownloadConfiguration")) {
            PluginRegistry plugins = new PluginRegistry(List.of());
            plugins.register(new PluginRegistry.RegisteredPlugin(
                    minimal.feature(), PluginSource.EXTERNAL, minimal.classLoader(),
                    minimal.feature().id(), minimalGeneration));
            plugins.register(new PluginRegistry.RegisteredPlugin(
                    download.feature(), PluginSource.EXTERNAL, download.classLoader(),
                    download.feature().id(), downloadGeneration));

            StaticResourceRegistry staticResources = new StaticResourceRegistry(plugins);
            DownloadExtensionRegistry downloadTypes = new DownloadExtensionRegistry(
                    plugins,
                    staticResources,
                    new PluginOwnedWebAssetValidator(staticResources),
                    new WebUiSlotRegistry(plugins));
            assertThat(downloadTypes.snapshot().downloadTypes()).singleElement().satisfies(registered -> {
                assertThat(registered.owner().featurePluginId()).isEqualTo("example-download");
                assertThat(registered.owner().packageId()).isEqualTo("example-download");
                assertThat(registered.owner().generation()).isEqualTo(downloadGeneration);
                assertThat(registered.publicationId()).isPositive();
                assertThat(registered.descriptor().type()).isEqualTo("example-download");
                assertThat(registered.descriptor().cancelSupported()).isTrue();
            });

            RouteAccessRegistry routes = new RouteAccessRegistry(plugins);
            routes.register(new PluginRequestOwner("example-minimal", 0L, 1L),
                    minimal.feature().routes());
            routes.register(new PluginRequestOwner("example-download", 0L, 2L),
                    download.feature().routes());
            assertThat(routes.isDeclared("/api/example-download/queue")).isTrue();
            assertThat(routes.isDeclared("/api/example-minimal/ping")).isTrue();
            assertThat(routes.isDeclared("/example-download-gallery.html")).isTrue();
            assertThat(routes.isDeclared("/api/gallery/unified/descriptors")).isFalse();

            assertThat(staticResources.resources())
                    .extracting(StaticResourceRegistry.RegisteredStaticResource::pluginId)
                    .contains("example-minimal", "example-download");

            WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(plugins);
            assertThat(i18n.resolve("example-minimal").load(Locale.ENGLISH)).containsKey("plugin.name");
            assertThat(i18n.resolve("example-download").load(Locale.ENGLISH)).containsKey("plugin.name");

            DatabaseSchemaRegistry schema = new DatabaseSchemaRegistry(plugins);
            assertThat(schema.mergedSchema().tables()).containsKey("example_minimal_records");

            QueueOperationRegistry operations = new QueueOperationRegistry(List.of());
            ExternalCapabilityInvocationRegistry invocationRegistry =
                    new ExternalCapabilityInvocationRegistry();
            QueueOperationsCapabilityAdapter queueAdapter =
                    new QueueOperationsCapabilityAdapter(operations, invocationRegistry);
            PluginCapabilityContributionRegistrar capabilityRegistrar =
                    new PluginCapabilityContributionRegistrar(
                            List.of(queueAdapter), List.of(), List.of(queueAdapter), invocationRegistry);
            ExternalCapabilityDrain retiredDrain;
            try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
                 AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                parent.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                parent.registerBean(RequestOwnerIdentityResolver.class,
                        () -> ignored -> RequestOwnerIdentity.owner("owner-a"));
                parent.refresh();

                child.setParent(parent);
                child.setClassLoader(download.classLoader());
                child.register(download.configurationClass());
                child.refresh();

                List<QueueOperations> contributedOperations =
                        List.copyOf(child.getBeansOfType(QueueOperations.class).values());
                assertThat(contributedOperations).singleElement();
                assertThat(child.getBean("exampleDownloadController")).isNotNull();

                PluginCapabilityContributionRegistrar.PreparedOwner prepared =
                        capabilityRegistrar.allocateOwner(
                                download.feature().id(), download.feature().id(), downloadGeneration);
                capabilityRegistrar.prepareInto(prepared, child);
                ExternalCapabilityPublication capabilityPublication = capabilityRegistrar.publish(prepared);
                assertThat(capabilityPublication.owner().pluginGeneration()).isEqualTo(downloadGeneration);
                assertThat(capabilityPublication.owner().publicationId()).isPositive();

                QueueOperations raw = contributedOperations.get(0);
                QueueOperationRegistry.OwnedQueueCommands resolved = operations.resolveOwned(
                        "example-download", "example-download", "example-download", downloadGeneration)
                        .orElseThrow();
                assertThat(resolved.owner().pluginGeneration()).isEqualTo(downloadGeneration);
                assertThat(resolved.owner().capabilityPublicationId())
                        .isEqualTo(capabilityPublication.owner().publicationId())
                        .isPositive();

                raw.getClass()
                        .getMethod("complete", String.class, String.class, RequestOwnerIdentity.class)
                        .invoke(raw, "100", "A", RequestOwnerIdentity.owner("owner-a"));
                assertThat(find(raw, "100", RequestOwnerIdentity.owner("owner-a"))).isPresent();

                operations.cancel(
                        "example-download", resolved.commands(), "100", "owner-a", false);
                assertThat(find(raw, "100", RequestOwnerIdentity.owner("owner-a"))).isEmpty();

                retiredDrain = capabilityRegistrar.withdraw(capabilityPublication).orElseThrow();
                assertThat(retiredDrain.isDrained()).isTrue();
                capabilityRegistrar.retireDrained(retiredDrain);
                capabilityRegistrar.acknowledgeRetired(retiredDrain);
                assertThat(operations.resolveOwned(
                        "example-download", "example-download", "example-download", downloadGeneration))
                        .isEmpty();
            }
            assertThat(capabilityRegistrar.releaseRetirementProof(retiredDrain)).isTrue();
        }
    }

    private LoadedTemplate compileTemplate(
            String templateName,
            String featureClassName,
            String configurationClassName) throws Exception {
        Path templateRoot = repositoryRoot().resolve("plugin-templates").resolve(templateName);
        Path output = temporaryDirectory.resolve(templateName);
        Files.createDirectories(output);

        List<String> sources;
        try (Stream<Path> files = Files.walk(templateRoot.resolve("src/main/java"))) {
            sources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("tests require a JDK compiler").isNotNull();
        List<String> arguments = new ArrayList<>(List.of(
                "-encoding", "UTF-8",
                "-parameters",
                "--release", "17",
                "-classpath", System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")),
                "-d", output.toString()));
        arguments.addAll(sources);
        assertThat(compiler.run(null, null, null, arguments.toArray(String[]::new)))
                .as(templateName + " compilation")
                .isZero();

        copyTree(templateRoot.resolve("src/main/resources"), output);
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
        PixivFeaturePlugin feature = (PixivFeaturePlugin) Class
                .forName(featureClassName, true, classLoader)
                .getConstructor()
                .newInstance();
        Class<?> configuration = Class.forName(configurationClassName, true, classLoader);
        return new LoadedTemplate(feature, configuration, classLoader);
    }

    private static Optional<?> find(
            QueueOperations operations,
            String workKey,
            RequestOwnerIdentity identity) throws Exception {
        return (Optional<?>) operations.getClass()
                .getMethod("find", String.class, RequestOwnerIdentity.class)
                .invoke(operations, workKey, identity);
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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

    private record LoadedTemplate(
            PixivFeaturePlugin feature,
            Class<?> configurationClass,
            URLClassLoader classLoader) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            classLoader.close();
        }
    }
}
