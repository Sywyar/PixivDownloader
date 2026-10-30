package top.sywyar.pixivdownload.plugin.web.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 登录引导只公开登录验证槽位，不扩大其它页面贡献的匿名可达面。 */
    @GetMapping("/api/web/login-verification-slots")
    public List<UiSlotView> loginVerificationSlots() {
        return uiSlots("login.verification").stream()
                .filter(slot -> slot.target().equals("login.verification")).toList();
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
                            slot.metadata(), slot.i18nNamespace(), entry.publication());
                })
                .toList();
    }

    public record UiSlotView(String slotId, String target, String moduleUrl, int order,
                             Map<String, String> metadata, String i18nNamespace, String publication) {
    }
}
