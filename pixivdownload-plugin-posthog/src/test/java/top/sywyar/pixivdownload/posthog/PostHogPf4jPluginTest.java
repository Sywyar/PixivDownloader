package top.sywyar.pixivdownload.posthog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostHog 外置插件契约")
class PostHogPf4jPluginTest {

    @Test
    @DisplayName("描述符与贡献只公开 PostHog 适配器和 SDK 资源")
    void descriptorAndContributionsExposeOnlyPostHogResources() throws Exception {
        Properties descriptor = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(in).isNotNull();
            descriptor.load(in);
        }
        assertThat(descriptor.getProperty("plugin.id")).isEqualTo("posthog");
        assertThat(descriptor.getProperty("plugin.class"))
                .isEqualTo(PostHogPf4jPlugin.class.getName());
        assertThat(new PostHogPf4jPlugin()).isInstanceOf(PixivPluginProvider.class);

        PostHogPlugin plugin = new PostHogPlugin();
        assertThat(plugin.routes()).allSatisfy(route -> assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.VISITOR));
        assertThat(plugin.routes()).extracting(route -> route.pathPattern())
                .containsExactly("/pixiv-posthog/**", "/vendor/posthog-js/**");
        assertThat(plugin.staticResources()).hasSize(2);
        assertThat(plugin.i18n()).hasSize(1);
    }

    @Test
    @DisplayName("适配器固定 SDK 版本但不持有调查发布参数")
    void adapterOwnsPinnedSdkWithoutSurveyParameters() throws Exception {
        String adapter;
        try (InputStream in = getClass().getResourceAsStream("/static/pixiv-posthog/pixiv-posthog.js")) {
            assertThat(in).isNotNull();
            adapter = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(adapter)
                .contains("var SDK_VERSION = '1.409.5'")
                .contains("var SDK_URL = '/vendor/posthog-js/' + SDK_VERSION + '/array.full.js'")
                .contains("ownerKey", "options.posthog", "createSurveyClient")
                .doesNotContain("phc_nBnHrYwgVVN6CvzAsQ5r4NxuSJyVPmceeHwwcpcgbG3k")
                .doesNotContain("surveyId: '")
                .doesNotContain("https://layout-survey.sywyar.top")
                .doesNotContain("download-workbench.layout-feedback")
                .contains("bootstrap = {distinctID: options.distinctId, isIdentifiedID: false}")
                .doesNotContain("personalApiKey", "serviceAccountToken");
        assertThat(getClass().getResource("/static/vendor/posthog-js/1.409.5/array.full.js")).isNotNull();
        assertThat(getClass().getResource("/META-INF/licenses/posthog-js/LICENSE")).isNotNull();
        assertThat(getClass().getResource("/META-INF/licenses/posthog-js/SOURCE.txt")).isNotNull();
        assertThat(getClass().getResource("/META-INF/licenses/posthog-js/INTEGRITY.txt")).isNotNull();
    }
}
