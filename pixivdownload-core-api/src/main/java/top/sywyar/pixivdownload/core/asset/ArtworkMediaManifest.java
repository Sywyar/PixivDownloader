package top.sywyar.pixivdownload.core.asset;

import java.util.List;
import java.util.Set;

/** 按作品页号持久化的媒体事实，区分原图与转换副本。 */
public record ArtworkMediaManifest(String originalExtension, List<String> extensions, boolean originalRetained) {
    private static final Set<String> FORMATS = Set.of("png", "jpg", "jpeg", "webp", "gif", "apng", "mp4", "zip");

    public ArtworkMediaManifest {
        if (originalExtension == null || !FORMATS.contains(originalExtension) || extensions == null || extensions.isEmpty()
                || extensions.size() > FORMATS.size() || extensions.stream().anyMatch(value -> value == null || !FORMATS.contains(value))
                || Set.copyOf(extensions).size() != extensions.size()) {
            throw new IllegalArgumentException("Invalid media manifest");
        }
        extensions = List.copyOf(extensions);
        if (originalRetained && !extensions.contains(originalExtension)) throw new IllegalArgumentException("Original file is not an output");
    }

    public ArtworkMediaManifest(String originalExtension, List<String> extensions) {
        this(originalExtension, extensions, extensions != null && extensions.contains(originalExtension));
    }

}
