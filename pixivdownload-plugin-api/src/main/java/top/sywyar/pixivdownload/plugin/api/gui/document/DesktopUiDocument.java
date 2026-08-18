package top.sywyar.pixivdownload.plugin.api.gui.document;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Toolkit-neutral description of the desktop application's root pages.
 * Page order is the rendered navigation order; providers supply only the toolkit-specific view for each page kind.
 *
 * @param pages ordered root pages
 */
public record DesktopUiDocument(List<Page> pages) {
    /** Validates and defensively copies the page tree. */
    public DesktopUiDocument {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty()) throw new IllegalArgumentException("pages must not be empty");
        var kinds = new HashSet<PageKind>();
        for (Page page : pages) {
            if (!kinds.add(page.kind())) throw new IllegalArgumentException("duplicate page kind: " + page.kind());
        }
    }

    /**
     * Root page descriptor.
     *
     * @param kind semantic page kind implemented by every desktop renderer
     * @param titleI18nKey untranslated title key
     * @param scrollPolicy toolkit-neutral scrolling policy
     */
    public record Page(PageKind kind, String titleI18nKey, ScrollPolicy scrollPolicy) {
        /** Validates the page descriptor. */
        public Page {
            kind = Objects.requireNonNull(kind, "kind");
            if (titleI18nKey == null || titleI18nKey.isBlank()) {
                throw new IllegalArgumentException("titleI18nKey must not be blank");
            }
            scrollPolicy = Objects.requireNonNull(scrollPolicy, "scrollPolicy");
        }
    }

    /** Stable semantic page kinds rendered by desktop UI providers. */
    public enum PageKind {
        WELCOME,
        STATUS,
        CONFIG,
        PLUGINS,
        TOOLS,
        SECURITY,
        ABOUT
    }

    /** Root-page scrolling policy. */
    public enum ScrollPolicy {
        NONE,
        SCROLL_PANE
    }
}
