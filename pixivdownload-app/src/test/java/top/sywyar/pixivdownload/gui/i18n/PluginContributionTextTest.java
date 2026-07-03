package top.sywyar.pixivdownload.gui.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginContributionText")
class PluginContributionTextTest {

    @TempDir
    private Path tempDir;

    @AfterEach
    void clearLocale() {
        GuiMessages.clearLocaleOverride();
    }

    @Test
    @DisplayName("bundle 首 key 带 UTF-8 BOM 时仍按规范 key 解析")
    void resolvesBomPrefixedKeys() throws Exception {
        Path bundleDir = tempDir.resolve("i18n/web");
        Files.createDirectories(bundleDir);
        Files.writeString(
                bundleDir.resolve("bom.properties"),
                "\uFEFFplugin.name=BOM Name\nplugin.summary=BOM Summary\n",
                StandardCharsets.UTF_8);

        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            String text = PluginContributionText.resolve(
                    List.of(new I18nContribution("bom", "i18n.web.bom")),
                    classLoader,
                    "bom",
                    "plugin.name");

            assertThat(text).isEqualTo("BOM Name");
        }
    }
}
