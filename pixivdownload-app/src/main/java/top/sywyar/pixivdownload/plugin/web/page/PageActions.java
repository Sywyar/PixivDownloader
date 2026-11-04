package top.sywyar.pixivdownload.plugin.web.page;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;

/** 草稿、正式发布和解析器共享同一关系；执行权仍由正式 Hook 绑定决定。 */
public final class PageActions {
    private PageActions() { }

    public static List<PageInteraction.Action> read(String document) {
        try { return read(WebPublicationStore.JSON.readTree(document)); }
        catch (IOException failure) { throw new IllegalArgumentException("PAGE_ACTION_INVALID"); }
    }

    public static List<PageInteraction.Action> read(JsonNode document) {
        var actions = document.path("actions");
        if (actions.isMissingNode()) return List.of();
        if (!actions.isArray() || actions.size() > 128) throw new IllegalArgumentException("PAGE_ACTION_CAPACITY");
        try { return WebPublicationStore.JSON.readerForListOf(PageInteraction.Action.class).readValue(actions); }
        catch (IOException failure) { throw new IllegalArgumentException("PAGE_ACTION_INVALID"); }
    }

    public static EditablePage apply(EditablePage page, JsonNode document, PageContributionRegistry registry) {
        return page.withActions(read(document), registry.components().stream().map(value -> value.definition().participation())
                .filter(java.util.Objects::nonNull).map(PageInteraction.Participant::protocol).distinct().toList());
    }
}
