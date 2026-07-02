package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure data declaration for a GUI configuration preset.
 *
 * @param presetId stable preset id inside the section
 * @param labelKey i18n key for the preset label
 * @param helpKey optional i18n key for help text
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param order preset ordering hint
 * @param matchFieldKey optional field used to infer the selected preset from current values
 * @param matchValue optional value matched against {@code matchFieldKey}
 * @param values config field values applied when the preset is selected
 */
public record GuiConfigPresetContribution(
        String presetId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        int order,
        String matchFieldKey,
        String matchValue,
        Map<String, String> values
) {

    public GuiConfigPresetContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        matchFieldKey = blankToNull(matchFieldKey);
        matchValue = matchValue == null ? "" : matchValue;
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public GuiConfigPresetContribution(String presetId, String labelKey, int order,
                                       Map<String, String> values) {
        this(presetId, labelKey, "", null, order, null, "", values);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
