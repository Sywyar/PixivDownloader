package top.sywyar.pixivdownload.plugin.api.web;

/**
 * Shared semantic markers for navigation contributions.
 * <p>
 * Markers are not slots. They let neutral consumers locate an entry with a
 * specific role without depending on a plugin id or URL path.
 */
public final class NavigationMarkers {

    private NavigationMarkers() {
    }

    /** Entry highlighted after the first successful guided download. */
    public static final String FIRST_DOWNLOAD_RESULT = "first-download-result";
}
