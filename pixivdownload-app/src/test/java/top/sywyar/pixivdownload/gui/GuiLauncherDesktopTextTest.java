package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuiLauncherDesktopTextTest {
    @TempDir
    private Path tempDir;
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void resolvesTargetFallbackSourceAndArgumentsThroughOneChain() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "source.only=Source\n",
                "fixture_en.properties", "fallback.only=English\nformat=Hello, {0}!\n",
                "fixture_zh-Hant.properties", "target.only=繁體\nformat=你好，{0}！\n"));

        Locale.setDefault(Locale.forLanguageTag("zh-Hant"));
        assertThat(resolve(registry, "target.only", "fallback")).isEqualTo("繁體");
        assertThat(resolve(registry, "format", "fallback", "世界")).isEqualTo("你好，世界！");

        Locale.setDefault(Locale.JAPAN);
        assertThat(resolve(registry, "fallback.only", "fallback")).isEqualTo("English");
        assertThat(resolve(registry, "source.only", "fallback")).isEqualTo("Source");
    }

    @Test
    void normalizesBomAndUsesTokenFallbackForMissingKeys() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "\uFEFFbom.key=BOM value\n"));

        assertThat(resolve(registry, "bom.key", "fallback")).isEqualTo("BOM value");
        assertThat(resolve(registry, "missing.key", "Missing {0}", "value"))
                .isEqualTo("Missing value");
    }

    @Test
    void readsTheCurrentLocaleForEveryResolution() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "value=来源\n",
                "fixture_en.properties", "value=English\n",
                "fixture_zh-Hant.properties", "value=繁體\n"));

        Locale.setDefault(Locale.US);
        assertThat(resolve(registry, "value", "fallback")).isEqualTo("English");
        Locale.setDefault(Locale.forLanguageTag("zh-Hant"));
        assertThat(resolve(registry, "value", "fallback")).isEqualTo("繁體");
    }

    @Test
    void keepsLiteralNamedPlaceholderExamplesWhenNoArgumentsWereSupplied() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "literal=JSON {title} and {voice_id}\n"));

        assertThat(resolve(registry, "literal", "fallback"))
                .isEqualTo("JSON {title} and {voice_id}");
    }

    private WebI18nBundleRegistry registry(Map<String, String> resources) throws Exception {
        Path directory = tempDir.resolve("i18n/web");
        Files.createDirectories(directory);
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            Files.writeString(directory.resolve(resource.getKey()), resource.getValue(), StandardCharsets.UTF_8);
        }
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            registry.register("fixture", loader,
                    List.of(new I18nContribution("fixture", "i18n.web.fixture")));
        }
        return registry;
    }

    private static String resolve(WebI18nBundleRegistry registry, String key,
                                  String fallback, String... arguments) {
        return GuiLauncher.resolveDesktopText(new DesktopUiText(
                "fixture", key, fallback, List.of(arguments)), () -> registry);
    }
}
