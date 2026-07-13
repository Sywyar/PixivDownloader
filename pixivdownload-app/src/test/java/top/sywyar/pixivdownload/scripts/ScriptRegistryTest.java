package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ScriptRegistry 油猴脚本扫描与元数据解析测试")
class ScriptRegistryTest {

    @TempDir
    private Path tempDir;

    /**
     * core-only 构建不再携带下载工作台 userscript contribution；真实脚本扫描由外置
     * download-workbench 模块测试覆盖。
     */
    private static ScriptRegistry registryFromCoreOnlyContribution() {
        UserscriptRegistry userscriptRegistry =
                new UserscriptRegistry(new PluginRegistry(List.of()));
        return new ScriptRegistry(TestI18nBeans.appMessages(), userscriptRegistry);
    }

    @Test
    @DisplayName("core-only 没有 userscript contribution 时脚本列表为空")
    void getScripts_isEmptyWithoutContribution() {
        ScriptRegistry registry = registryFromCoreOnlyContribution();
        assertThat(registry.getScripts()).isEmpty();
    }

    @Test
    @DisplayName("core-only 没有 userscript contribution 时 readContent 抛 IOException")
    void readContent_withoutContribution_throwsIOException() {
        ScriptRegistry registry = registryFromCoreOnlyContribution();
        assertThatThrownBy(() -> registry.readContent("Pixiv All-in-One.user.js"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("readContent 未知文件名抛 IOException")
    void readContent_unknownFileName_throwsIOException() {
        ScriptRegistry registry = registryFromCoreOnlyContribution();
        assertThatThrownBy(() -> registry.readContent("does-not-exist.user.js"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("带 UTF-8 BOM 的多合一脚本仍可解析 name/version/description，且忽略 @description:en")
    void parseScriptMetadata_handlesBomAndLocalizedDescription() throws IOException {
        String content = """
                \uFEFF// ==UserScript==
                // @name         Pixiv All-in-One Downloader
                // @version      1.2.3
                // @description  中文描述
                // @description:en  English description
                // ==/UserScript==
                (function(){})();
                """;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            ScriptResource resource = ScriptRegistry.parseScriptMetadata("Pixiv All-in-One.user.js", reader);

            assertEquals("all-in-one", resource.id());
            assertEquals("Pixiv All-in-One Downloader", resource.displayName());
            assertEquals("1.2.3", resource.version());
            assertEquals("中文描述", resource.description());
        }
    }

    @Test
    @DisplayName("refresh 一次性读取完整 UTF-8 文本，readContent 只读快照且注销后刷新清空")
    void refreshMaterializesCompleteUtf8Content() throws Exception {
        Path directory = tempDir.resolve("static/userscripts");
        Files.createDirectories(directory);
        Path script = directory.resolve("External.user.js");
        String first = script("外置脚本", "1.0.0", "第一份内容");
        String second = script("外置脚本", "2.0.0", "第二份内容");
        Files.writeString(script, first, StandardCharsets.UTF_8);

        UserscriptRegistry sources = new UserscriptRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            sources.register("external", loader, List.of(new UserscriptContribution(
                    "external", "classpath:/static/userscripts/*.user.js")));
            ScriptRegistry registry = new ScriptRegistry(TestI18nBeans.appMessages(), sources);

            assertThat(registry.getScripts()).singleElement()
                    .satisfies(metadata -> {
                        assertThat(metadata.displayName()).isEqualTo("外置脚本");
                        assertThat(metadata.version()).isEqualTo("1.0.0");
                    });
            assertThat(registry.readContent("External.user.js")).isEqualTo(first);

            Files.writeString(script, second, StandardCharsets.UTF_8);
            assertThat(registry.readContent("External.user.js"))
                    .as("源文件变化不能绕过 refresh 改写当前快照")
                    .isEqualTo(first);
            registry.refresh();
            assertThat(registry.readContent("External.user.js")).isEqualTo(second);

            sources.unregister("external");
            registry.refresh();
            assertThat(registry.getScripts()).isEmpty();
            assertThatThrownBy(() -> registry.readContent("External.user.js"))
                    .isInstanceOf(IOException.class);
        }
    }

    private static String script(String name, String version, String marker) {
        return """
                // ==UserScript==
                // @name         %s
                // @version      %s
                // @description  测试脚本
                // ==/UserScript==
                // %s
                """.formatted(name, version, marker);
    }
}
