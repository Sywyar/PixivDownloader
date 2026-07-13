package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.util.regex.Pattern;

/**
 * Pure host identity for one exact external capability publication.
 *
 * <p>The feature id, physical package id, loaded plugin generation and per-start publication id are
 * deliberately separate. None of them carries a plugin instance, child context or classloader.
 */
public record ExternalCapabilityOwner(
        String pluginId,
        String packageId,
        long pluginGeneration,
        long publicationId
) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public ExternalCapabilityOwner {
        pluginId = requireId(pluginId, "plugin id");
        packageId = requireId(packageId, "package id");
        if (pluginGeneration < 0L) {
            throw new IllegalArgumentException("external capability plugin generation must not be negative");
        }
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("external capability publication id must be positive");
        }
    }

    private static String requireId(String value, String label) {
        if (value == null || !ID_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("invalid external capability " + label + ": " + value);
        }
        return value.trim();
    }
}
