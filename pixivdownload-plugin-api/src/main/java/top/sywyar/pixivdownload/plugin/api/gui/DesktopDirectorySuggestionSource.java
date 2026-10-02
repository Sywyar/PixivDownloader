package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Optional;

/**
 * Optional desktop configuration assistance, published with the source plugin's exact lifecycle.
 * Missing, failed or withdrawn sources produce no prompt and never authorize file access.
 * The source contributes at most one candidate for a declared, non-sensitive, unconditional
 * PATH_DIR field with HOT_RELOAD effect. It must read the persisted configuration before use;
 * neither observing nor displaying the candidate changes configuration.
 */
@FunctionalInterface
public interface DesktopDirectorySuggestionSource {
    /** @return the current candidate, or empty when configured or no candidate has been observed */
    Optional<DesktopDirectorySuggestion> directorySuggestion();
}
