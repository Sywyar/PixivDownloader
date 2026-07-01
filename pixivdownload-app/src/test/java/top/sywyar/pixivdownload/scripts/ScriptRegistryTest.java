package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ScriptRegistry 油猴脚本扫描与元数据解析测试")
class ScriptRegistryTest {

    /**
     * 经真实的 {@code UserscriptContribution} 来源构建 ScriptRegistry：下载工作台插件声明
     * {@code classpath:/static/userscripts/*.user.js}，{@link UserscriptRegistry} 由
     * {@link BuiltInPlugins} 收集、{@link ScriptRegistry} 经声明方 ClassLoader 扫描——不 mock、
     * 不覆写 {@code loadScriptContent}，断言的是 registry 走完整 contribution 链路的真实行为。
     * <p>
     * <b>构建约束</b>：本组用例依赖 Maven {@code generate-resources} 阶段的 {@code copy-userscripts}
     * execution 把仓库根目录的 6 个独立 {@code *.user.js} 与 {@code build/generated-userscripts/}
     * 下由 {@code generate-sources} 生成的多合一 {@code *.user.js}（1 个）一并复制到
     * {@code target/classes/static/userscripts/}，registry 才能扫到全部 7 个脚本。用
     * {@code mvn test} 运行时这两个阶段都先于 test 自动执行；脱离 Maven 直接在 IDE 跑前需先执行
     * 一次 {@code mvn generate-resources}（或完整构建）。
     */
    private static ScriptRegistry registryFromRealContribution() {
        UserscriptRegistry userscriptRegistry =
                new UserscriptRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        return new ScriptRegistry(TestI18nBeans.appMessages(), userscriptRegistry);
    }

    @Test
    @DisplayName("经下载工作台插件 userscript contribution 扫到 7 个脚本，含 all-in-one")
    void getScripts_scansAllUserscriptsFromContribution() {
        ScriptRegistry registry = registryFromRealContribution();
        assertThat(registry.getScripts()).hasSize(7);
        assertThat(registry.getScripts())
                .extracting(ScriptResource::id)
                .contains("all-in-one");
    }

    @Test
    @DisplayName("readContent 读多合一脚本得到含 ==UserScript== / @name / @version 的真实脚本内容")
    void readContent_returnsRealAllInOneScriptBody() throws IOException {
        ScriptRegistry registry = registryFromRealContribution();
        String content = registry.readContent("Pixiv All-in-One.user.js");
        assertThat(content)
                .contains("// ==UserScript==")
                .contains("@name")
                .contains("@version");
    }

    @Test
    @DisplayName("readContent 未知文件名抛 IOException")
    void readContent_unknownFileName_throwsIOException() {
        ScriptRegistry registry = registryFromRealContribution();
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
}
