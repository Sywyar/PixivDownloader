package top.sywyar.pixivdownload.gui.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetMatchMode;

/**
 * Resolved GUI configuration preset metadata ready for Swing rendering.
 */
public record GuiConfigPresetSpec(
        String presetId,
        String label,
        String help,
        String cardId,
        int order,
        String matchFieldKey,
        String matchValue,
        Map<String, String> values,
        List<String> lockedFieldKeys,
        GuiConfigPresetMatchMode matchMode
) {

    public GuiConfigPresetSpec {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        lockedFieldKeys = lockedFieldKeys == null ? List.copyOf(values.keySet()) : List.copyOf(lockedFieldKeys);
        matchMode = matchMode == null ? GuiConfigPresetMatchMode.EQUALS_IGNORE_CASE : matchMode;
    }

    public GuiConfigPresetSpec(String presetId, String label, String help, String cardId,
                               int order, String matchFieldKey, String matchValue,
                               Map<String, String> values) {
        this(presetId, label, help, cardId, order, matchFieldKey, matchValue, values, null, null);
    }

    public GuiConfigPresetSpec(String presetId, String label, String help, String cardId,
                               int order, String matchFieldKey, String matchValue,
                               Map<String, String> values, List<String> lockedFieldKeys) {
        this(presetId, label, help, cardId, order, matchFieldKey, matchValue, values, lockedFieldKeys, null);
    }
}
