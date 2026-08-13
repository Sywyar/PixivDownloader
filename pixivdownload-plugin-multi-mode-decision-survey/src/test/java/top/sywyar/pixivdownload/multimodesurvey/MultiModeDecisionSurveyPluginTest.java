package top.sywyar.pixivdownload.multimodesurvey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("多人模式去留调查外置插件契约")
class MultiModeDecisionSurveyPluginTest {

    @Test
    @DisplayName("只贡献管理员站内信资源并按发行激活位发布站内信")
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
        Properties publication = new Properties();
        try (InputStream input = getClass().getResourceAsStream(
                "/static/pixiv-multi-mode-decision-survey/release-publication.properties")) {
            assertThat(input).isNotNull();
            publication.load(input);
        }
        boolean officialRelease = "true".equalsIgnoreCase(
                publication.getProperty("officialReleaseEnabled"));
        var slots = plugin.uiSlots();
        assertThat(slots).hasSize(officialRelease ? 1 : 0);
        if (officialRelease) {
            byte[] config;
            try (InputStream input = getClass().getResourceAsStream(
                    "/static/pixiv-multi-mode-decision-survey/posthog-config.js")) {
                assertThat(input).isNotNull();
                config = input.readAllBytes();
            }
            assertThat(slots.get(0).metadata().get("notification.instance-key"))
                    .isEqualTo(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(config)));
        }
    }

    @Test
    @DisplayName("调查发布者自持 PostHog 参数与问卷结构")
    void ownsPostHogParametersAndSurveyMetadata() throws Exception {
        String script;
        try (InputStream input = getClass().getResourceAsStream(
                "/static/pixiv-multi-mode-decision-survey/survey.js")) {
            assertThat(input).isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String config;
        try (InputStream input = getClass().getResourceAsStream(
                "/static/pixiv-multi-mode-decision-survey/posthog-config.js")) {
            assertThat(input).isNotNull();
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(config).contains(
                "global.PixivMultiModeDecisionSurveyPostHog = Object.freeze({",
                "projectToken: 'phc_nBnHrYwgVVN6CvzAsQ5r4NxuSJyVPmceeHwwcpcgbG3k'",
                "apiHost: 'https://layout-survey.sywyar.top'",
                "uiHost: 'https://us.posthog.com'")
                .containsPattern("surveyId: '[^']+'");
        assertThat(script).contains(
                "var POSTHOG = global.PixivMultiModeDecisionSurveyPostHog || Object.freeze({})",
                "var QUESTION_ID =",
                "var CHOICES = ['Yes', 'No', 'Other']",
                "IDENTITY_URL + '?surveyId=' + encodeURIComponent(POSTHOG.surveyId)");
        assertThat(script).containsPattern("var QUESTION_ID = '[^']+'");
        assertThat(script)
                .doesNotContain("snooze", "never");

        String embed;
        try (InputStream input = getClass().getResourceAsStream(
                "/static/pixiv-multi-mode-decision-survey/embed.html")) {
            assertThat(input).isNotNull();
            embed = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(embed).contains("/pixiv-multi-mode-decision-survey/posthog-config.js");
    }
}
