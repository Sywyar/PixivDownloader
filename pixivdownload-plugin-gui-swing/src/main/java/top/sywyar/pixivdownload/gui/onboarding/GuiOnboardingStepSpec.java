package top.sywyar.pixivdownload.gui.onboarding;

import java.util.List;

/** Swing-side onboarding step contributed by a plugin. */
public record GuiOnboardingStepSpec(
        String pluginId,
        String stepId,
        String titleKey,
        String bodyKey,
        List<String> bulletKeys,
        String actionLabelKey,
        String actionHref,
        String waitingKey,
        String completionKey,
        int order,
        String i18nNamespace
) {
    public GuiOnboardingStepSpec {
        bulletKeys = List.copyOf(bulletKeys == null ? List.of() : bulletKeys);
    }

    public String title() {
        return resolve(titleKey);
    }

    public String body() {
        return resolve(bodyKey);
    }

    public List<String> bullets() {
        return bulletKeys.stream().map(this::resolve).toList();
    }

    public String actionLabel() {
        return resolve(actionLabelKey);
    }

    public String waitingText() {
        return resolve(waitingKey);
    }

    private String resolve(String key) {
        try {
            return GuiOnboardingContributionAggregator.resolve(i18nNamespace, key);
        } catch (RuntimeException ignored) {
            return key;
        }
    }
}
