package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Objects;

/**
 * An untrusted candidate for an initially empty, plugin-owned PATH_DIR configuration field.
 * The host validates the directory and persists it only after explicit desktop confirmation.
 * The suggestion ID must remain stable across polling and retries.
 * @param suggestionId stable owner-local ID, at most 128 characters
 * @param configurationKey declared owner configuration key, at most 256 characters
 * @param directory candidate absolute directory, at most 4096 characters
 */
public record DesktopDirectorySuggestion(String suggestionId, String configurationKey, String directory) {
    /**
     * Rejects blank, oversized and control-containing fields.
     * @param suggestionId stable owner-local ID
     * @param configurationKey declared owner configuration key
     * @param directory candidate absolute directory
     */
    public DesktopDirectorySuggestion {
        requireText(suggestionId, 128);
        requireText(configurationKey, 256);
        requireText(directory, 4096);
    }

    private static void requireText(String value, int maximum) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid directory suggestion");
        }
    }
}
