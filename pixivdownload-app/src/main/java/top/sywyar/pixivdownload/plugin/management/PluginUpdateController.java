package top.sywyar.pixivdownload.plugin.management;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

/** 管理员更新提示入口，沿用插件管理路由的权限。 */
@RestController
@PluginManagedBean
public class PluginUpdateController {
    private final PluginUpdateService updates;

    public PluginUpdateController(PluginUpdateService updates) { this.updates = updates; }

    @GetMapping("/api/plugins/updates")
    public PluginUpdateService.Summary updates() { return updates.summary(); }
}
