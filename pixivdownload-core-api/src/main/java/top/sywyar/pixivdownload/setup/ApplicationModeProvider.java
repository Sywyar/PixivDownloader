package top.sywyar.pixivdownload.setup;

/**
 * Supplies the configured application mode without exposing setup persistence or authentication services.
 */
public interface ApplicationModeProvider {

    /**
     * Returns {@code "solo"}, {@code "multi"}, or {@code null} before setup is complete.
     */
    String getMode();
}
