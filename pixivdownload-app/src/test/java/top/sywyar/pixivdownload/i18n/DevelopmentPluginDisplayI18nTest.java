package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("开发插件非活动态的展示词典")
class DevelopmentPluginDisplayI18nTest {
    @TempDir Path tempDir;

    @Test
    @DisplayName("未注册功能的开发插件仍提供名称简介，活动贡献优先且撤回后恢复展示词典")
    void inactiveDevelopmentArtifactKeepsIdentityOnly() throws Exception {
        Path classes = bundle("generation-one", "fixture", "name=开发插件\nsummary=插件简介\n");
        Files.writeString(classes.resolve("i18n/web/fixture_en.properties"),
                "name=Development plugin\nsummary=Plugin description\n", StandardCharsets.UTF_8);
        bundle("generation-one", "private-page", "title=Private page\n");
        PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        when(runtime.developmentResourceArtifacts()).thenReturn(Map.of(classes, descriptor("fixture")));
        PluginRegistry plugins = new PluginRegistry(List.of());
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(plugins, runtime);

        assertThat(plugins.plugins()).isEmpty();
        assertThat(registry.supportedNamespaces()).containsExactly("fixture");
        assertThat(registry.resolve("private-page")).isNull();
        assertThat(registry.resolve("fixture", Locale.SIMPLIFIED_CHINESE, "name")).contains("开发插件");
        assertThat(registry.resolve("fixture", Locale.SIMPLIFIED_CHINESE, "summary")).contains("插件简介");
        assertThat(registry.resolve("fixture", Locale.US, "name")).contains("Development plugin");
        assertThat(registry.resolve("fixture", Locale.US, "summary")).contains("Plugin description");

        registry.register("fixture", new ClassLoader(null) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return new ByteArrayInputStream("name=Active plugin\n".getBytes(StandardCharsets.UTF_8));
            }
        }, List.of(new I18nContribution("fixture", "i18n.web.fixture")));
        assertThat(registry.resolve("fixture", Locale.US, "name")).contains("Active plugin");
        registry.unregister("fixture");
        assertThat(registry.resolve("fixture", Locale.US, "name")).contains("Development plugin");
    }

    @Test
    @DisplayName("直接查询随物理卸载和换代更新，不继续引用旧开发资源")
    void unloadAndReplacementWithdrawOldResourcePath() throws Exception {
        Path first = bundle("generation-one", "fixture", "name=First\n");
        Path second = bundle("generation-two", "fixture", "name=Second\n");
        PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        when(runtime.developmentResourceArtifacts()).thenReturn(Map.of(first, descriptor("fixture")));
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(List.of()), runtime);
        assertThat(registry.resolve("fixture", Locale.US, "name")).contains("First");

        when(runtime.developmentResourceArtifacts()).thenReturn(Map.of());
        assertThat(registry.resolve("fixture")).isNull();
        when(runtime.developmentResourceArtifacts()).thenReturn(Map.of(second, descriptor("fixture")));
        assertThat(registry.resolve("fixture", Locale.US, "name")).contains("Second");
    }

    @Test
    @DisplayName("缺失和损坏词典保持受控缺席，不能读取开发资源目录之外的文件")
    void missingCorruptAndUnsafeResourcesRemainUnavailable() throws Exception {
        Path classes = bundle("generation-one", "fixture", "name=\\uZZZZ\n");
        PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
        when(runtime.developmentResourceArtifacts()).thenReturn(Map.of(classes, descriptor("fixture")));
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(List.of()), runtime);
        assertThatThrownBy(() -> registry.resolve("fixture").load(Locale.US))
                .isInstanceOf(MissingResourceException.class);
        Files.delete(classes.resolve("i18n/web/fixture.properties"));
        assertThatThrownBy(() -> registry.resolve("fixture").load(Locale.US))
                .isInstanceOf(MissingResourceException.class);
        Path outside = tempDir.resolve("outside");
        Files.writeString(Path.of(outside + ".properties"), "name=Private\n", StandardCharsets.UTF_8);
        var unsafe = new WebI18nBundleRegistry.RegisteredBundle("fixture",
                new I18nContribution("fixture", outside.toString()), Map.of(), classes,
                LocaleCatalog.defaultCatalog());
        assertThatThrownBy(() -> unsafe.load(Locale.SIMPLIFIED_CHINESE))
                .isInstanceOf(MissingResourceException.class);
    }

    private Path bundle(String generation, String namespace, String content) throws Exception {
        Path classes = tempDir.resolve(generation).resolve("classes");
        Files.createDirectories(classes.resolve("i18n/web"));
        Files.writeString(classes.resolve("i18n/web/" + namespace + ".properties"), content, StandardCharsets.UTF_8);
        return classes;
    }

    private static PluginDescriptor descriptor(String namespace) {
        return new PluginDescriptor("fixture", "fixture", "0.0.1", null, List.of(), null,
                namespace, "name", "summary", null, null, PluginKind.FEATURE, List.of());
    }
}
