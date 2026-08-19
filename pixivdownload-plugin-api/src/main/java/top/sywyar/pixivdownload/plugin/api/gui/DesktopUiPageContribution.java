package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One toolkit-neutral desktop page contributed by an active feature plugin.
 * The consuming host supplies the trusted owner identity, validates ids and action routes,
 * and derives renderer requirements from the declared node trees.
 *
 * @param pageId stable page id in the contributing plugin's namespace
 * @param order navigation order among contributed pages
 * @param title localized page title
 * @param content complete declarative page tree
 * @param actions action id to relative {@code /api/gui/} POST endpoint declarations
 * @param dialogs currently open declarative dialogs owned by this page
 */
public record DesktopUiPageContribution(
        String pageId,
        int order,
        DesktopUiNode.TextToken title,
        DesktopUiNode content,
        Map<String, String> actions,
        List<DesktopUiDocument.Dialog> dialogs
) {
    /**
     * Validates the owner-free page shape and defensively copies collections.
     * Owner namespaces and exact route ownership are validated when the host aggregates the page.
     *
     * @param pageId stable page id
     * @param order navigation order
     * @param title localized page title
     * @param content complete page tree
     * @param actions declared action endpoints
     * @param dialogs currently open dialogs
     */
    public DesktopUiPageContribution {
        if (pageId == null || !pageId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("pageId must be a stable id");
        }
        title = Objects.requireNonNull(title, "title");
        content = Objects.requireNonNull(content, "content");
        Map<String, String> normalizedActions = new LinkedHashMap<>();
        if (actions != null) actions.forEach((actionId, endpoint) ->
                normalizedActions.put(actionId, endpoint == null ? null : endpoint.trim()));
        actions = Map.copyOf(normalizedActions);
        dialogs = List.copyOf(dialogs == null ? List.of() : dialogs);
    }

    /**
     * Creates a page without actions or open dialogs.
     *
     * @param pageId stable page id
     * @param order navigation order
     * @param title localized page title
     * @param content complete page tree
     */
    public DesktopUiPageContribution(String pageId, int order,
                                     DesktopUiNode.TextToken title, DesktopUiNode content) {
        this(pageId, order, title, content, Map.of(), List.of());
    }
}
