package top.sywyar.pixivdownload.gui.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved GUI configuration preset metadata ready for Swing rendering.
 */
public record GuiConfigPresetSpec(
        String presetId,
        String label,
        String help,
        int order,
        String matchFieldKey,
        String matchValue,
        Map<String, String> values
) {

    public GuiConfigPresetSpec {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
