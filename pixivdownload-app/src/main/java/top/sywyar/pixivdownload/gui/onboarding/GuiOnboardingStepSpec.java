package top.sywyar.pixivdownload.gui.onboarding;

import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.ArrayList;
import java.util.List;

/**
 * Swing-side onboarding step contributed by a plugin.
 */
public record GuiOnboardingStepSpec(
        String pluginId,
        String stepId,
        String title,
        String titleKey,
        String body,
        String bodyKey,
        List<String> bullets,
        List<String> bulletKeys,
        String actionLabel,
        String actionLabelKey,
        String actionHref,
        String waitingText,
        String waitingKey,
        String completionKey,
        int order,
        String i18nNamespace,
        List<I18nContribution> i18n,
        ClassLoader classLoader
) {
    public GuiOnboardingStepSpec {
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
        bulletKeys = bulletKeys == null ? List.of() : List.copyOf(bulletKeys);
        i18n = i18n == null ? List.of() : List.copyOf(i18n);
    }

    @Override
    public String title() {
        return text(title, titleKey);
    }

    @Override
    public String body() {
        return text(body, bodyKey);
    }

    @Override
    public List<String> bullets() {
        if (i18nNamespace == null || i18n.isEmpty() || classLoader == null) {
            return bullets;
        }
        List<String> resolved = new ArrayList<>();
        for (int i = 0; i < bulletKeys.size(); i++) {
            String fallback = i < bullets.size() ? bullets.get(i) : bulletKeys.get(i);
            resolved.add(text(fallback, bulletKeys.get(i)));
        }
        return List.copyOf(resolved);
    }

    @Override
    public String actionLabel() {
        return text(actionLabel, actionLabelKey);
    }

    @Override
    public String waitingText() {
        return text(waitingText, waitingKey);
    }

    private String text(String fallback, String key) {
        if (i18nNamespace == null || key == null || i18n.isEmpty() || classLoader == null) {
            return fallback;
        }
        return PluginContributionText.resolve(i18n, classLoader, i18nNamespace, key);
    }
}
