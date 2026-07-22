package top.sywyar.pixivdownload.plugin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

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

    @GetMapping("/api/web/ui-slots")
    public List<UiSlotView> uiSlots(@RequestParam(name = "targetPrefix", required = false) String targetPrefix) {
        String prefix = targetPrefix == null ? "" : targetPrefix;
        return webUiSlotRegistry.slots().stream()
                .map(WebUiSlotRegistry.RegisteredUiSlot::slot)
                .filter(slot -> prefix.isBlank() || slot.target().startsWith(prefix))
                .sorted(Comparator.comparingInt(WebUiSlotContribution::order)
                        .thenComparing(WebUiSlotContribution::slotId))
                .map(slot -> new UiSlotView(
                        slot.slotId(), slot.target(), slot.moduleUrl(), slot.order(), Map.of()))
                .toList();
    }

    public record UiSlotView(String slotId, String target, String moduleUrl, int order,
                             Map<String, String> metadata) {
    }
}
