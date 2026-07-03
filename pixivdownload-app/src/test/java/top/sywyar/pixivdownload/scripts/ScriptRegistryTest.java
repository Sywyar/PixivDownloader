package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ScriptRegistry 油猴脚本扫描与元数据解析测试")
class ScriptRegistryTest {

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
}
