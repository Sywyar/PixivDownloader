package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Pure data contribution for one GUI onboarding guide step.
 *
 * @param pluginId       owner plugin id
 * @param stepId         stable step id
 * @param i18nNamespace  namespace used to resolve all text keys
 * @param titleKey       title text key
 * @param bodyKey        body text key
 * @param bulletKeys     bullet text keys
 * @param actionLabelKey action button text key
 * @param actionHref     action target href
 * @param waitingKey     waiting hint text key
 * @param completionKey  backend completion signal key
 * @param order          display order among contributed steps
 */
public record GuiOnboardingStepContribution(
        String pluginId,
        String stepId,
        String i18nNamespace,
        String titleKey,
        String bodyKey,
        List<String> bulletKeys,
        String actionLabelKey,
        String actionHref,
        String waitingKey,
        String completionKey,
        int order
) {
    public GuiOnboardingStepContribution {
        bulletKeys = bulletKeys == null ? List.of() : List.copyOf(bulletKeys);
    }
}
