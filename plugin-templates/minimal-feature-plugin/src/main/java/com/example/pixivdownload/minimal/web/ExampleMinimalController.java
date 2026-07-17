package com.example.pixivdownload.minimal.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/** Admin-only example endpoint; access is declared separately by the feature plugin route contribution. */
@PluginManagedBean
@RestController
@RequestMapping("/api/example-minimal")
public final class ExampleMinimalController {

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse("example-minimal.ready", "status.ready");
    }

    /** Machine code and i18n key cross the HTTP boundary; localized prose stays in plugin resources. */
    public record StatusResponse(String code, String messageKey) {
    }
}
