package top.sywyar.pixivdownload.config;

import java.nio.file.Path;

/**
 * Runtime paths exposed to optional plugins without depending on app utilities.
 */
public interface RuntimePathProvider {

    Path resolveEdgeTtsVersionPath();
}
