package top.sywyar.pixivdownload.multimodesurvey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("多人模式去留调查外置插件契约")
class MultiModeDecisionSurveyPluginTest {

    @Test
    @DisplayName("只贡献管理员站内信资源，源码构建默认不发布站内信")
    void contributesOnlyAdminInboxSurveyAndDefaultsOff() throws Exception {
        Properties descriptor = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(input);
        }
        assertThat(descriptor.getProperty("plugin.id")).isEqualTo("multi-mode-decision-survey");
        assertThat(descriptor.getProperty("plugin.dependencies"))
                .isEqualTo("posthog@1.0,notification@1.0");
        assertThat(new MultiModeDecisionSurveyPf4jPlugin()).isInstanceOf(PixivPluginProvider.class);

        MultiModeDecisionSurveyPlugin plugin = new MultiModeDecisionSurveyPlugin();
        assertThat(plugin.routes()).allSatisfy(route ->
                assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        assertThat(plugin.routes()).extracting(route -> route.pathPattern()).containsExactly(
                "/pixiv-multi-mode-decision-survey/**",
                "/api/multi-mode-decision-survey/identity");
        assertThat(plugin.staticResources()).singleElement().satisfies(resource ->
                assertThat(resource.publicPathPrefix()).isEqualTo("/pixiv-multi-mode-decision-survey/"));
        assertThat(plugin.i18n()).singleElement().satisfies(bundle ->
                assertThat(bundle.namespace()).isEqualTo("multi-mode-decision-survey"));
        assertThat(plugin.uiSlots()).isEmpty();
    }

    @Test
    @DisplayName("调查发布者自持四个 PostHog 参数与给定问卷 ID")
    void ownsPostHogParametersAndSurveyMetadata() throws Exception {
        String script;
        try (InputStream input = getClass().getResourceAsStream(
                "/static/pixiv-multi-mode-decision-survey/survey.js")) {
            assertThat(input).isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(script).contains(
                "projectToken: 'phc_nBnHrYwgVVN6CvzAsQ5r4NxuSJyVPmceeHwwcpcgbG3k'",
                "surveyId: '019ff791-9fcf-0000-2a64-0be9f0b64dbf'",
                "apiHost: 'https://layout-survey.sywyar.top'",
                "uiHost: 'https://us.posthog.com'",
                "var QUESTION_ID = '0ac24f7c-abeb-4405-8c9c-916e4ca904ac'",
                "var CHOICES = ['Yes', 'No', 'Other']")
                .doesNotContain("snooze", "never");
    }
}
