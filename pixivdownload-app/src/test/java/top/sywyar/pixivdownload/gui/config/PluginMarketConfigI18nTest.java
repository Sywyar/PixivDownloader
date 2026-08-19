package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App-owned plugin-market schema text remains complete in every visible locale.
 */
@DisplayName("插件市场 / 仓库配置文案：全部可见语言键集合一致且非空")
class PluginMarketConfigI18nTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties", "messages_en.properties", "messages_ja.properties",
            "messages_ko.properties", "messages_zh-Hant.properties");

    @Test
    @DisplayName("Schema 市场配置文案在全部可见语言中键集合一致、非空")
    void guiMarketKeysMatchAcrossLocales() throws IOException {
        Set<String> expected = subset(load(BUNDLES.get(0)),
                "gui.config.market.", "gui.config.field.plugin-catalog.");
        assertThat(expected).isNotEmpty();
        for (String bundle : BUNDLES) {
            Properties messages = load(bundle);
            Set<String> actual = subset(messages,
                    "gui.config.market.", "gui.config.field.plugin-catalog.");
            assertThat(actual).as(bundle).isEqualTo(expected);
            assertNoneBlank(messages, actual);
        }
    }

    @Test
    @DisplayName("插件市场模板注释中英键集合一致、非空")
    void templateCommentKeysMatchAcrossLocales() throws IOException {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");

        Set<String> zhKeys = subset(zh, "config.template.plugin-catalog.", "gui.config.market.log.");
        Set<String> enKeys = subset(en, "config.template.plugin-catalog.", "gui.config.market.log.");

        assertThat(zhKeys).as("中英模板注释键集合应一致").isEqualTo(enKeys);
        assertThat(zhKeys).contains(
                "config.template.plugin-catalog.connect-timeout-ms.comment",
                "config.template.plugin-catalog.read-timeout-ms.comment",
                "config.template.plugin-catalog.max-manifest-bytes.comment",
                "config.template.plugin-catalog.max-package-bytes.comment",
                "config.template.plugin-catalog.repositories.comment");
        assertNoneBlank(zh, zhKeys);
        assertNoneBlank(en, enKeys);
    }

    private static Set<String> subset(Properties props, String... prefixes) {
        return props.stringPropertyNames().stream()
                .filter(key -> {
                    for (String prefix : prefixes) {
                        if (key.startsWith(prefix)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void assertNoneBlank(Properties props, Set<String> keys) {
        for (String key : keys) {
            assertThat(props.getProperty(key)).as("文案 %s 不应为空", key).isNotBlank();
        }
    }

    private static Properties load(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = PluginMarketConfigI18nTest.class.getResourceAsStream("/i18n/" + resource)) {
            assertThat(in).as("应能加载 %s", resource).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
