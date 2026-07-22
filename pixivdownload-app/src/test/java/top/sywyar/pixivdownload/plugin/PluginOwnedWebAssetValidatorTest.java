package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.provenance.OwnerOnlyAssetPlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PluginOwnedWebAssetValidator 插件自有前端资源校验")
class PluginOwnedWebAssetValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同 owner 静态目录内真实存在的 JavaScript 可以注册")
    void ownedExistingJavaScriptAccepted() {
        PluginRegistry.RegisteredPlugin owner = owner(new AssetPlugin("asset-owner", true));
        PluginOwnedWebAssetValidator validator = validatorFor(owner);

        assertThatCode(() -> validator.validateOwnedJavaScript(
                owner, "/test-download/module.js", "测试模块"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/module.js",
            "//example.test/module.js",
            "/test-download/module.js?generation=1",
            "/test-download/module.js#fragment",
            "/test-download/%2e%2e/module.js",
            "/test-download/../module.js",
            "/test-download/file:C:/escape.js",
            "/test-download/http:escape.js",
            "/test-download\\module.js",
            "/test-download/module.css"
    })
    @DisplayName("非规范同源 JavaScript 路径一律拒绝")
    void unsafeModulePathsRejected(String moduleUrl) {
        PluginRegistry.RegisteredPlugin owner = owner(new AssetPlugin("asset-owner", true));
        PluginOwnedWebAssetValidator validator = validatorFor(owner);

        assertThatThrownBy(() -> validator.validateOwnedJavaScript(owner, moduleUrl, "测试模块"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("跨 owner 路径与声明内缺失的资源都拒绝")
    void crossOwnerAndMissingResourcesRejected() {
        PluginRegistry.RegisteredPlugin withoutMapping = owner(new AssetPlugin("asset-owner", false));
        PluginOwnedWebAssetValidator withoutMappingValidator = validatorFor(withoutMapping);
        assertThatThrownBy(() -> withoutMappingValidator.validateOwnedJavaScript(
                withoutMapping, "/test-download/module.js", "测试模块"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not covered");

        PluginRegistry.RegisteredPlugin owner = owner(new AssetPlugin("asset-owner", true));
        PluginOwnedWebAssetValidator validator = validatorFor(owner);
        assertThatThrownBy(() -> validator.validateOwnedJavaScript(
                owner, "/test-download/missing.js", "测试模块"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("父 classloader 同路径资源不能冒充 owner 包资源，开发 classes 目录内自有资源可以解析")
    void parentCollisionRejectedAndOwnerDirectoryAccepted() throws Exception {
        assertThatCode(() -> {
            if (PluginOwnedWebAssetValidatorTest.class.getClassLoader()
                    .getResource("test-download/module.js") == null) {
                throw new AssertionError("父 classloader 测试资源缺失");
            }
        }).doesNotThrowAnyException();

        byte[] classBytes = ownerPluginClassBytes();
        Path ownerRoot = tempDir.resolve("owner-classes");
        writeEntry(ownerRoot, ownerClassEntry(), classBytes);
        PluginRegistry.RegisteredPlugin owner = isolatedOwner(ownerRoot.toUri().toURL(), classBytes);
        PluginOwnedWebAssetValidator validator = validatorFor(owner);

        assertThatThrownBy(() -> validator.validateOwnedJavaScript(
                owner, "/owner-only/module.js", "测试模块"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");

        writeEntry(ownerRoot, "test-download/module.js", "export {};".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validateOwnedJavaScript(
                owner, "/owner-only/module.js", "测试模块"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("普通插件 JAR 内的自有 JavaScript 可由直接 archive 根解析")
    void ordinaryJarOwnerResourceAccepted() throws Exception {
        byte[] classBytes = ownerPluginClassBytes();
        Path jar = tempDir.resolve("owner-plugin.jar");
        writeJar(jar, Map.of(
                ownerClassEntry(), classBytes,
                "test-download/module.js", "export {};".getBytes(StandardCharsets.UTF_8)));
        PluginRegistry.RegisteredPlugin owner = isolatedOwner(jar.toUri().toURL(), classBytes);

        assertThatCode(() -> validatorFor(owner).validateOwnedJavaScript(
                owner, "/owner-only/module.js", "测试模块"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Spring Boot 可执行 JAR 的 BOOT-INF/classes 自有资源根可解析")
    void springBootExecutableJarOwnerResourceAccepted() throws Exception {
        byte[] classBytes = ownerPluginClassBytes();
        Path jar = tempDir.resolve("boot-app.jar");
        writeJar(jar, Map.of(
                "BOOT-INF/classes/" + ownerClassEntry(), classBytes,
                "BOOT-INF/classes/test-download/module.js",
                "export {};".getBytes(StandardCharsets.UTF_8)));
        PluginRegistry.RegisteredPlugin owner = isolatedOwner(jar.toUri().toURL(), classBytes);

        assertThatCode(() -> validatorFor(owner).validateOwnedJavaScript(
                owner, "/owner-only/module.js", "测试模块"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("普通 Spring AOP 代理按最终 target class 校验，不误判为错误 classloader")
    void springProxyUsesUltimateTargetClass() {
        AssetPlugin target = new AssetPlugin("proxy-owner", true);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        PixivFeaturePlugin proxy = (PixivFeaturePlugin) factory.getProxy(target.getClass().getClassLoader());
        PluginRegistry.RegisteredPlugin owner = new PluginRegistry.RegisteredPlugin(
                proxy, PluginSource.BUILT_IN, target.getClass().getClassLoader());

        assertThatCode(() -> validatorFor(owner).validateOwnedJavaScript(
                owner, "/test-download/module.js", "测试模块"))
                .doesNotThrowAnyException();
    }

    private static PluginRegistry.RegisteredPlugin owner(PixivFeaturePlugin plugin) {
        return new PluginRegistry(List.of(plugin)).registeredPlugins().get(0);
    }

    private static PluginOwnedWebAssetValidator validatorFor(
            PluginRegistry.RegisteredPlugin owner) {
        PluginRegistry plugins = new PluginRegistry(List.of());
        plugins.register(owner);
        StaticResourceRegistry resources = new StaticResourceRegistry(plugins);
        return new PluginOwnedWebAssetValidator(resources);
    }

    private static PluginRegistry.RegisteredPlugin isolatedOwner(
            URL codeSource, byte[] classBytes) throws Exception {
        String className = OwnerOnlyAssetPlugin.class.getName();
        IsolatedOwnerClassLoader loader = new IsolatedOwnerClassLoader(
                className, classBytes, codeSource,
                PluginOwnedWebAssetValidatorTest.class.getClassLoader());
        PixivFeaturePlugin plugin = (PixivFeaturePlugin) loader.loadClass(className)
                .getConstructor().newInstance();
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, loader, "owner-only-asset", 7L);
    }

    private static byte[] ownerPluginClassBytes() throws IOException {
        try (InputStream input = OwnerOnlyAssetPlugin.class.getClassLoader()
                .getResourceAsStream(ownerClassEntry())) {
            if (input == null) {
                throw new IOException("owner-only test plugin class bytes not found");
            }
            return input.readAllBytes();
        }
    }

    private static String ownerClassEntry() {
        return OwnerOnlyAssetPlugin.class.getName().replace('.', '/') + ".class";
    }

    private static void writeEntry(Path root, String entry, byte[] bytes) throws IOException {
        Path target = root.resolve(entry);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                output.putNextEntry(new JarEntry(item.getKey()));
                output.write(item.getValue());
                output.closeEntry();
            }
        }
    }

    private static final class IsolatedOwnerClassLoader extends SecureClassLoader {
        private final String ownerClassName;
        private final byte[] ownerClassBytes;
        private final CodeSource codeSource;

        private IsolatedOwnerClassLoader(
                String ownerClassName, byte[] ownerClassBytes, URL codeSource, ClassLoader parent) {
            super(parent);
            this.ownerClassName = ownerClassName;
            this.ownerClassBytes = ownerClassBytes;
            this.codeSource = new CodeSource(codeSource, (java.security.cert.Certificate[]) null);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(ownerClassName)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = defineClass(name, ownerClassBytes, 0, ownerClassBytes.length, codeSource);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private record AssetPlugin(String id, boolean declareResources) implements PixivFeaturePlugin {
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return declareResources
                    ? List.of(new StaticResourceContribution(
                    "classpath:/test-download/", "/test-download/"))
                    : List.of();
        }
    }
}
