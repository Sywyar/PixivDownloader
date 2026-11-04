package top.sywyar.pixivdownload.plugin.web.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.web.WebUiSlotRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Generic Web UI slot manifest for host pages outside the download workbench.
 * Host pages declare stable {@code data-qt-slot} anchors and load only active
 * plugin modules reported here, so disabled plugins leave no UI entry behind.
 */
@RestController
@RequiredArgsConstructor
public class WebUiSlotController {

    private final WebUiSlotRegistry webUiSlotRegistry;
    private final top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog pages;
    private final top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest publications;

    /** 只有 owner 显式公开的页面目标可以匿名取得固定呈现，普通业务槽位仍走原鉴权。 */
    @GetMapping("/api/web/page-slots/{page}/{target}")
    public org.springframework.http.ResponseEntity<List<UiSlotView>> pageSlots(@PathVariable String page,
            @PathVariable String target, jakarta.servlet.http.HttpServletRequest request) {
        var declaration = pages.pages().stream().filter(entry -> entry.definition().id().equals(page)
                        && entry.definition().publicPage()).flatMap(entry -> entry.definition().targets().stream())
                .filter(value -> value.id().equals(target)).findFirst();
        if (declaration.isEmpty()) return org.springframework.http.ResponseEntity.notFound().build();
        try {
            String revision = publications.revision(request);
            if (!revision.equals(request.getParameter("revision")))
                return org.springframework.http.ResponseEntity.status(409).build();
            var slots = uiSlots(target).stream().filter(slot -> slot.target().equals(target) && slot.participation() != null)
                    .filter(slot -> declaration.get().protocols().stream().anyMatch(protocol ->
                            top.sywyar.pixivdownload.plugin.security.OperationPolicies.compatible(protocol, slot.participation().protocol()))).toList();
            return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(slots);
        } catch (IllegalStateException unavailable) { return org.springframework.http.ResponseEntity.status(503).build(); }
    }

    @GetMapping("/api/web/ui-slots")
    public List<UiSlotView> uiSlots(@RequestParam(name = "targetPrefix", required = false) String targetPrefix) {
        String prefix = targetPrefix == null ? "" : targetPrefix;
        return webUiSlotRegistry.slots().stream()
                .filter(entry -> prefix.isBlank() || entry.slot().target().startsWith(prefix))
                .sorted(Comparator.comparingInt((WebUiSlotRegistry.RegisteredUiSlot entry) -> entry.slot().order())
                        .thenComparing(entry -> entry.slot().slotId()))
                .map(entry -> {
                    var slot = entry.slot();
                    return new UiSlotView(slot.slotId(), slot.target(), slot.moduleUrl(), slot.order(),
                            slot.metadata(), slot.i18nNamespace(), entry.publication(), slot.participation());
                })
                .toList();
    }

    public record UiSlotView(String slotId, String target, String moduleUrl, int order,
                             Map<String, String> metadata, String i18nNamespace, String publication,
                             top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction.Participant participation) {
    }
}
