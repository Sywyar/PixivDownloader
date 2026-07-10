package top.sywyar.pixivdownload.core.gallery.frontend;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure descriptor for a trusted same-origin gallery frontend module.
 * The descriptor declares typed hooks and matching semantics; it never carries HTML or executable functions.
 */
public record GalleryFrontendContribution(
        String contributionId,
        String moduleUrl,
        GalleryFrontendScope scope,
        Set<GalleryFrontendHook> hooks,
        String viewHref,
        String displayNamespace,
        String displayI18nKey,
        String iconToken,
        int order
) {

    private static final Pattern CONTRIBUTION_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Pattern ICON_TOKEN = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public GalleryFrontendContribution {
        contributionId = requirePattern(contributionId, "contributionId", CONTRIBUTION_ID);
        if (moduleUrl == null || moduleUrl.isBlank()) {
            throw new IllegalArgumentException("moduleUrl must not be blank");
        }
        scope = scope == null ? GalleryFrontendScope.any() : scope;
        hooks = immutableHooks(hooks);

        boolean viewEntry = hooks.contains(GalleryFrontendHook.VIEW_ENTRY);
        if (viewEntry) {
            if (viewHref == null || viewHref.isBlank()) {
                throw new IllegalArgumentException("viewHref must not be blank for VIEW_ENTRY");
            }
            displayNamespace = requirePattern(displayNamespace, "displayNamespace", NAMESPACE);
            displayI18nKey = requireText(displayI18nKey, "displayI18nKey");
            iconToken = requirePattern(iconToken, "iconToken", ICON_TOKEN);
        } else {
            if (viewHref != null || displayNamespace != null || displayI18nKey != null || iconToken != null) {
                throw new IllegalArgumentException("view presentation fields require VIEW_ENTRY hook");
            }
        }
    }

    public boolean matches(String sourceId, String sourceWorkNamespace,
                           top.sywyar.pixivdownload.core.gallery.model.GalleryKind galleryKind,
                           top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind mediaKind) {
        return scope.matches(sourceId, sourceWorkNamespace, galleryKind, mediaKind);
    }

    private static Set<GalleryFrontendHook> immutableHooks(Set<GalleryFrontendHook> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("hooks must not be empty");
        }
        LinkedHashSet<GalleryFrontendHook> copy = new LinkedHashSet<>();
        for (GalleryFrontendHook value : values) {
            if (value == null) {
                throw new IllegalArgumentException("hooks must not contain null values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requirePattern(String value, String field, Pattern pattern) {
        String normalized = requireText(value, field);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return normalized;
    }
}
