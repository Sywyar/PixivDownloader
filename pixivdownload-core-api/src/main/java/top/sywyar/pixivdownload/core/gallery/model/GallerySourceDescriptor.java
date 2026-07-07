package top.sywyar.pixivdownload.core.gallery.model;

import java.util.List;
import java.util.Set;

public record GallerySourceDescriptor(
        String providerId,
        String sourceId,
        Set<GalleryKind> kinds,
        String displayNamespace,
        String displayI18nKey,
        int order,
        List<GalleryFieldStrategy> fieldStrategies
) {

    public GallerySourceDescriptor {
        providerId = blankToNull(providerId);
        sourceId = blankToNull(sourceId);
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        displayNamespace = blankToNull(displayNamespace);
        displayI18nKey = blankToNull(displayI18nKey);
        fieldStrategies = fieldStrategies == null ? List.of() : List.copyOf(fieldStrategies);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
