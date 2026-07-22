package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.TestGalleryPlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebI18nService 读取注册期物化的插件 bundle")
class WebI18nServiceTest {

    private final WebI18nService service = new WebI18nService(
            new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll())));
    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("核心 namespace common 从物化快照读取真实 properties（非空键值，行为不变）")
    void loadsCoreNamespaceFromMaterializedSnapshot() {
        I18nBundleResponse response = service.loadBundle("common", Locale.SIMPLIFIED_CHINESE);
        assertThat(response.getNamespace()).isEqualTo("common");
        assertThat(response.getMessages()).isNotEmpty();
    }

    @Test
    @DisplayName("gallery 不再由 app 内置 registry 提供，缺席时 namespace 不可解析")
    void galleryNamespaceAbsentFromBuiltInRegistry() {
        assertThatThrownBy(() -> service.loadBundle("gallery", Locale.SIMPLIFIED_CHINESE))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("gallery 作为外置插件贡献 namespace 时读取注册期物化消息")
    void loadsGalleryNamespaceFromExternalContribution() {
        WebI18nService galleryService = new WebI18nService(
                new WebI18nBundleRegistry(new PluginRegistry(List.of(new TestGalleryPlugin()))));

        I18nBundleResponse response = galleryService.loadBundle("gallery", Locale.SIMPLIFIED_CHINESE);
        assertThat(response.getNamespace()).isEqualTo("gallery");
        assertThat(response.getMessages()).containsEntry("gui.action.open", "本地画廊");
    }

    @Test
    @DisplayName("未注册的 namespace 抛 LocalizedException（400）")
    void unsupportedNamespaceThrows() {
        assertThatThrownBy(() -> service.loadBundle("nope", Locale.SIMPLIFIED_CHINESE))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("bundle 首 key 带 UTF-8 BOM 时对外仍暴露规范 key")
    void normalizesBomPrefixedKeys() throws Exception {
        Path bundleDir = tempDir.resolve("i18n/web");
        Files.createDirectories(bundleDir);
        Files.writeString(
                bundleDir.resolve("bom.properties"),
                "\uFEFFplugin.name=BOM Name\nplugin.summary=BOM Summary\n",
                StandardCharsets.UTF_8);

        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            registry.register("bom-plugin", classLoader, List.of(new I18nContribution("bom", "i18n.web.bom")));
            I18nBundleResponse response = new WebI18nService(registry)
                    .loadBundle("bom", Locale.SIMPLIFIED_CHINESE);

            assertThat(response.getMessages()).containsEntry("plugin.name", "BOM Name");
            assertThat(response.getMessages()).doesNotContainKey("\uFEFFplugin.name");
        }
    }

    @Test
    @DisplayName("安装态但未进入活动快照的外置插件也能按 canonical namespace 解析 i18n")
    void loadsInstalledOnlyPluginBundle() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectories(plugins);
        writeInstalledPluginJar(
                plugins.resolve("mail-1.0.0.jar"),
                "mail",
                "邮件通知",
                "Mail Notifications");
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins)) {
            installer.recoverPendingTransactions();
            WebI18nBundleRegistry registry = new WebI18nBundleRegistry(
                    new PluginRegistry(List.of()),
                    installer::listInstalled);

            I18nBundleResponse zh = new WebI18nService(registry)
                    .loadBundle("mail", Locale.SIMPLIFIED_CHINESE);
            I18nBundleResponse en = new WebI18nService(registry)
                    .loadBundle("mail", Locale.ENGLISH);

            assertThat(zh.getMessages()).containsEntry("plugin.name", "邮件通知");
            assertThat(en.getMessages()).containsEntry("plugin.name", "Mail Notifications");
        }
    }

    private static void writeInstalledPluginJar(Path path, String pluginId, String zhName, String enName)
            throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            writeEntry(jar, "plugin.properties", """
                    plugin.id=%s
                    plugin.version=1.0.0
                    plugin.class=com.example.%sPlugin
                    plugin.requires=1.0
                    pixiv.display-namespace=%s
                    pixiv.display-name-key=plugin.name
                    pixiv.description-key=plugin.summary
                    pixiv.icon-key=mail
                    pixiv.color-token=green
                    """.formatted(pluginId, pluginId, pluginId));
            writeEntry(jar, "i18n/web/%s.properties".formatted(pluginId),
                    "plugin.name=%s\nplugin.summary=中文简介\n".formatted(zhName));
            writeEntry(jar, "i18n/web/%s_en.properties".formatted(pluginId),
                    "plugin.name=%s\nplugin.summary=English summary\n".formatted(enName));
        }
    }

    private static void writeEntry(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
