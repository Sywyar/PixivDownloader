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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件市场 / 仓库配置的新增文案<b>中英完整一致</b>守卫：GUI 显示文案（{@code gui.properties} / {@code gui_en.properties}）
 * 与模板注释（{@code messages.properties} / {@code messages_en.properties}）里本次新增的市场相关键，两份语言文件里
 * <b>键集合一致、各值非空</b>。涵盖 proxy-trusted 风险提示等所有新文案。
 */
@DisplayName("插件市场 / 仓库配置文案：中英键集合一致且非空")
class PluginMarketConfigI18nTest {

    @Test
    @DisplayName("GUI 市场配置文案中英键集合一致、非空（含 proxy-trusted 风险提示）")
    void guiMarketKeysMatchAcrossLocales() throws IOException {
        Properties zh = load("/i18n/gui.properties");
        Properties en = load("/i18n/gui_en.properties");

        Set<String> zhKeys = subset(zh, "gui.config.market.", "gui.config.field.plugin-catalog.");
        Set<String> enKeys = subset(en, "gui.config.market.", "gui.config.field.plugin-catalog.");

        assertThat(zhKeys).as("中英 GUI 市场文案键集合应一致").isEqualTo(enKeys);
        assertNoneBlank(zh, zhKeys);
        assertNoneBlank(en, enKeys);
        // 风险提示键必须存在于两份语言文件。
        assertThat(zhKeys).contains("gui.config.market.repo.proxy-trusted.risk", "gui.config.market.repo.risk");
    }

    @Test
    @DisplayName("插件市场模板注释中英键集合一致、非空")
    void templateCommentKeysMatchAcrossLocales() throws IOException {
        Properties zh = load("/i18n/messages.properties");
        Properties en = load("/i18n/messages_en.properties");

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
        try (InputStream in = PluginMarketConfigI18nTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("应能加载 %s", resource).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
