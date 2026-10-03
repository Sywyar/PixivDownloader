package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.PluginEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** 插件运行状态的读取与桌面投影。 */
final class DesktopPluginStatusController {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile String noticeKey = "";
    private volatile List<PluginEntry> statuses = List.of();
    private volatile String observedAt = "";
    private volatile boolean recoveryMode;
    private volatile DesktopUiHost.GuiValue buildInfo = DesktopUiHost.GuiValue.of(Map.of());

    DesktopPluginStatusController(ComposeDesktopUiModel owner, DesktopUiHost host) {
        this.owner = owner;
        this.host = host;
    }

    DesktopUiNode.PluginOverview controlCenterPage(Map<String, Runnable> nextActions) {
        return new DesktopUiNode.PluginOverview("plugins.overview", statuses, observedAt, noticeKey, recoveryMode,
                button("plugins.refresh", "plugins.refresh", "gui.plugins.action.refresh",
                        !owner.busy(), nextActions, () -> owner.runBusy(this::load)),
                button("plugins.manage", "plugins.manage", "gui.plugins.action.open-web",
                        !owner.busy(), nextActions, () -> owner.openWeb("/plugin-manage.html")));
    }

    String localizedCode(String prefix, String code) {
        if (code == null || code.isBlank()) return "";
        String key = prefix + code;
        String localized = host.message(key);
        return localized.equals(key) ? code : localized;
    }

    void load() {
        if (!loading.compareAndSet(false, true)) return;
        try {
            readStatus();
        } finally {
            loading.set(false);
        }
    }

    private void readStatus() {
        // 状态读取会复验磁盘中的插件包；过短超时会遗留仍在执行的请求。
        DesktopUiHost.GuiResponse response = host.guiGet("plugins/status", 60_000);
        if (!response.reachable() || !response.is2xx() || response.body() == null) {
            noticeKey = !response.reachable() ? "gui.plugins.state.offline"
                    : response.status() == 403 ? "gui.plugins.state.forbidden" : "gui.plugins.state.error";
            statuses = List.of();
            observedAt = "";
            recoveryMode = false;
            buildInfo = DesktopUiHost.GuiValue.of(Map.of());
            return;
        }
        recoveryMode = response.body().path("recoveryMode").asBoolean(false);
        buildInfo = response.body();
        observedAt = response.body().path("observedAt").asText("");
        List<PluginEntry> rows = new ArrayList<>();
        for (DesktopUiHost.GuiValue plugin : response.body().path("plugins")) {
            String id = plugin.path("id").asText("unknown");
            rows.add(new PluginEntry(id, plugin.path("name").asText(id),
                    value(plugin, "description"), value(plugin, "iconKey"), value(plugin, "colorToken"),
                    value(plugin, "source"), value(plugin, "status"), value(plugin, "runtimePhase"),
                    plugin.path("managed").asBoolean(false), plugin.path("required").asBoolean(false),
                    value(plugin, "version"), value(plugin.path("verification"), "status"),
                    value(plugin.path("verification"), "diagnosticCode"),
                    value(plugin.path("verification"), "lastVerifiedAt")));
        }
        statuses = List.copyOf(rows);
        noticeKey = rows.isEmpty() ? "gui.plugins.state.empty" : "";
    }

    private static String value(DesktopUiHost.GuiValue node, String field) {
        return node.path(field).asText("");
    }

    DesktopUiHost.GuiValue buildInfo() { return buildInfo; }

    String noticeKey() { return noticeKey; }

    long startedCount() {
        return statuses.stream().filter(plugin -> "STARTED".equals(plugin.statusCode())).count();
    }

    int count() { return statuses.size(); }

    DesktopUiNode.TextToken summary() {
        return observedAt.isBlank()
                ? new DesktopUiNode.TextToken("gui-compose", "gui.compose.home.freshness.unavailable", "", List.of())
                : new DesktopUiNode.TextToken("gui-compose", "gui.compose.home.plugins-count", "",
                        List.of(Long.toString(startedCount()), Integer.toString(count())));
    }
}
