package top.sywyar.pixivdownload.multimodesurvey;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.notification.SurveyInboxMessage;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/** Inbox-only publisher for the official multi-user-mode decision survey. */
public class MultiModeDecisionSurveyPlugin implements PixivFeaturePlugin {

    public static final String ID = "multi-mode-decision-survey";
    private static final String PUBLICATION_RESOURCE =
            "static/pixiv-multi-mode-decision-survey/release-publication.properties";
    private static final String SURVEY_EMBED_URL = "/pixiv-multi-mode-decision-survey/embed.html"
            + "?pixivBridgeGet=/api/i18n/meta"
            + "&pixivBridgeGet=/api/i18n/messages/posthog"
            + "&pixivBridgeGet=/api/i18n/messages/multi-mode-decision-survey"
            + "&pixivBridgeGet=/api/multi-mode-decision-survey/identity"
            + "&pixivBridgeRead=pixiv_theme"
            + "&pixivBridgeRead=pixiv:multi-mode-decision-survey:state:v1"
            + "&pixivBridgeWrite=pixiv:multi-mode-decision-survey:state:v1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "users";
    }

    @Override
    public String colorToken() {
        return "amber";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.admin("/pixiv-multi-mode-decision-survey/embed.html"),
                WebRouteContribution.publicRoute("/pixiv-multi-mode-decision-survey/survey.css"),
                WebRouteContribution.publicRoute("/pixiv-multi-mode-decision-survey/release-activation.js"),
                WebRouteContribution.publicRoute("/pixiv-multi-mode-decision-survey/posthog-config.js"),
                WebRouteContribution.publicRoute("/pixiv-multi-mode-decision-survey/survey.js"),
                WebRouteContribution.admin("/api/multi-mode-decision-survey/identity"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                "classpath:/static/pixiv-multi-mode-decision-survey/",
                "/pixiv-multi-mode-decision-survey/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.multi-mode-decision-survey", 18));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        if (!officialRelease()) {
            return List.of();
        }
        return List.of(new SurveyInboxMessage(
                "multi-mode-decision-survey.inbox",
                MultiModeDecisionSurveyIdentityController.CAMPAIGN_VERSION,
                SURVEY_EMBED_URL,
                ID,
                "inbox-title",
                "inbox-body",
                20).toUiSlotContribution());
    }

    private static boolean officialRelease() {
        Properties properties = new Properties();
        try (InputStream input = MultiModeDecisionSurveyPlugin.class.getClassLoader()
                .getResourceAsStream(PUBLICATION_RESOURCE)) {
            if (input == null) {
                return false;
            }
            properties.load(input);
            return "true".equalsIgnoreCase(properties.getProperty("officialReleaseEnabled"));
        } catch (IOException ignored) {
            return false;
        }
    }

}
