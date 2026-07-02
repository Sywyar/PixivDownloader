package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure data declaration for a GUI configuration preset.
 *
 * @param presetId stable preset id inside the section
 * @param labelKey i18n key for the preset label
 * @param helpKey optional i18n key for help text
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param cardId optional card id for card-switcher layouts
 * @param order preset ordering hint
 * @param matchFieldKey optional field used to infer the selected preset from current values
 * @param matchValue optional value matched against {@code matchFieldKey}
 * @param values config field values applied when the preset is selected
 * @param lockedFieldKeys config field keys locked while this preset is selected; null means all value keys
 * @param matchMode comparison mode used for {@code matchFieldKey}/{@code matchValue}
 */
public record GuiConfigPresetContribution(
        String presetId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        String cardId,
        int order,
        String matchFieldKey,
        String matchValue,
        Map<String, String> values,
        List<String> lockedFieldKeys,
        GuiConfigPresetMatchMode matchMode
) {

    public GuiConfigPresetContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        cardId = blankToNull(cardId);
        matchFieldKey = blankToNull(matchFieldKey);
        matchValue = matchValue == null ? "" : matchValue;
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        lockedFieldKeys = lockedFieldKeys == null
                ? List.copyOf(values.keySet())
                : lockedFieldKeys.stream()
                .map(GuiConfigPresetContribution::blankToNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        matchMode = matchMode == null ? GuiConfigPresetMatchMode.EQUALS_IGNORE_CASE : matchMode;
    }

    public GuiConfigPresetContribution(String presetId, String labelKey, int order,
                                       Map<String, String> values) {
        this(presetId, labelKey, "", null, null, order, null, "", values, null, null);
    }

    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values) {
        this(presetId, labelKey, helpKey, i18nNamespace, null, order, matchFieldKey, matchValue,
                values, null, null);
    }

    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, String cardId, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values) {
        this(presetId, labelKey, helpKey, i18nNamespace, cardId, order, matchFieldKey, matchValue,
                values, null, null);
    }

    public GuiConfigPresetContribution(String presetId, String labelKey, String helpKey,
                                       String i18nNamespace, String cardId, int order,
                                       String matchFieldKey, String matchValue,
                                       Map<String, String> values, List<String> lockedFieldKeys) {
        this(presetId, labelKey, helpKey, i18nNamespace, cardId, order, matchFieldKey, matchValue,
                values, lockedFieldKeys, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
