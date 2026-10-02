package top.sywyar.pixivdownload.gui.controlcenter;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestion;
import java.io.IOException;
import java.util.Optional;

/** Host wrapper invoked under the source owner's publication lease, including configuration writes. */
public interface DirectorySuggestionCapability {
    Optional<DesktopDirectorySuggestion> suggestion();
    void confirm(String suggestionId, String directory) throws IOException;
    void dismiss(String suggestionId);
}
