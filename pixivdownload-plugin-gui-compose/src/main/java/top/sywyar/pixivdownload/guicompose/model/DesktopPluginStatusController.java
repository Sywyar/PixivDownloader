package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 插件运行状态的读取与桌面投影。
 */
final class DesktopPluginStatusController {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;

    private volatile String notice = "";
    private volatile List<PluginStatusRow> statuses = List.of();
    private volatile String observedAt = "";
    private volatile boolean recoveryMode;

    DesktopPluginStatusController(ComposeDesktopUiModel owner, DesktopUiHost host) {
        this.owner = owner;
        this.host = host;
    }

    DesktopUiNode controlCenterPage() {
        List<DesktopUiNode> cards = new ArrayList<>();
        for (PluginStatusRow plugin : statuses) cards.add(pluginCard(plugin));
        if (statuses.isEmpty() && notice.isBlank()) {
            cards.add(text("plugins.empty", "gui.plugins.state.empty", TextStyle.CAPTION));
        }
        DesktopUiNode installed = new DesktopUiNode.Surface(
                "plugins.installed",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(14),
                true,
                new DesktopUiNode.AdaptiveGrid(
                        "plugins.grid",
                        260,
                        2,
                        12,
                        12,
                        cards
                )
        );

        List<DesktopUiNode> details = new ArrayList<>();
        if (!observedAt.isBlank()) {
            details.add(new DesktopUiNode.Text(
                    "plugins.observed-at",
                    appToken("desktop.ui.plugins.observed-at", formatTimestamp(observedAt)),
                    TextStyle.CAPTION,
                    true,
                    false
            ));
        }
        if (recoveryMode) {
            details.add(new DesktopUiNode.Surface(
                    "plugins.recovery",
                    DesktopUiNode.SurfaceStyle.WARNING,
                    new DesktopUiNode.Insets(
                            8,
                            12,
                            8,
                            12
                    ),
                    true,
                    text("plugins.recovery.text", "gui.plugins.recovery", TextStyle.WARNING)
            ));
        }
        if (!notice.isBlank()) details.add(status("plugins.notice", notice));
        if (details.isEmpty())
            details.add(text(
                    "plugins.detail",
                    "desktop.ui.plugins.intro",
                    TextStyle.CAPTION
            ));
        DesktopUiNode status = new DesktopUiNode.Surface(
                "plugins.summary",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(14),
                true,
                column("plugins.summary.content", details)
        );
        return scroll(
                "plugins.scroll",
                column(
                        "plugins.read-only",
                        text("plugins.title", "desktop.ui.page.plugins", TextStyle.TITLE),
                        text("plugins.intro", "desktop.ui.plugins.intro", TextStyle.CAPTION),
                        new DesktopUiNode.AdaptiveGrid(
                                "plugins.layout",
                                300,
                                2,
                                16,
                                16,
                                List.of(installed, status)
                        )
                )
        );
    }

    private DesktopUiNode pluginCard(PluginStatusRow plugin) {
        TextStyle statusStyle = switch (nullToEmpty(plugin.statusCode())) {
            case "STARTED" -> TextStyle.SUCCESS;
            case "FAILED", "INCOMPATIBLE", "MISSING_REQUIRED", "INCOMPATIBLE_REQUIRED" ->
                    TextStyle.ERROR;
            case "DISABLED", "STOPPED", "UNLOADED" -> TextStyle.CAPTION;
            default -> TextStyle.WARNING;
        };
        DesktopUiNode header = new DesktopUiNode.Dock(
                "plugins.card." + plugin.id() + ".header",
                8,
                null,
                null,
                null,
                raw("plugins.card." + plugin.id() + ".name", plugin.name(), TextStyle.HEADING),
                raw(
                        "plugins.card." + plugin.id() + ".status",
                        localizedCode("gui.plugins.status.", plugin.statusCode()),
                        statusStyle
                )
        );
        return new DesktopUiNode.Surface(
                "plugins.card." + plugin.id(),
                DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(
                        8,
                        12,
                        8,
                        12
                ),
                true,
                column(
                        "plugins.card." + plugin.id() + ".content",
                        header,
                        raw(
                                "plugins.card." + plugin.id() + ".secondary",
                                pluginSecondary(plugin),
                                TextStyle.CAPTION
                        )
                )
        );
    }

    private String pluginSecondary(PluginStatusRow plugin) {
        List<String> parts = new ArrayList<>();
        String source = switch (nullToEmpty(plugin.source())) {
            case "built-in" -> "built-in";
            case "external" -> "external";
            case "not-installed" -> "not-installed";
            default -> "unknown";
        };
        parts.add(host.message("gui.plugins.source." + source));
        if (plugin.required()) parts.add(host.message("gui.plugins.tag.required"));
        if (plugin.managed() && !nullToEmpty(plugin.phaseCode()).isBlank()) {
            parts.add(localizedCode("gui.plugins.phase.", plugin.phaseCode()));
        }
        if (!nullToEmpty(plugin.version()).isBlank()) {
            parts.add(host.message("gui.plugins.version", plugin.version()));
        }
        if (!nullToEmpty(plugin.verificationStatus()).isBlank()) {
            parts.add(localizedCode(
                    "gui.plugins.verification.",
                    plugin.verificationStatus()
            ));
        }
        if (!nullToEmpty(plugin.verificationDiagnosticCode()).isBlank()) {
            parts.add(host.message(
                    "desktop.ui.plugins.diagnostic",
                    plugin.verificationDiagnosticCode()
            ));
        }
        if (!nullToEmpty(plugin.lastVerifiedAt()).isBlank()) {
            parts.add(host.message(
                    "desktop.ui.plugins.last-verified-at",
                    formatTimestamp(plugin.lastVerifiedAt())
            ));
        }
        return String.join("  ·  ", parts);
    }

    String localizedCode(String prefix, String code) {
        if (code == null || code.isBlank()) return "";
        String key = prefix + code;
        String localized = host.message(key);
        return localized.equals(key) ? code : localized;
    }

    private void refreshPlugins() {
        owner.runBusy(this::load);
    }

    void load() {
        DesktopUiHost.GuiResponse response = host.guiGet("plugins/status", 5_000);
        if (!response.reachable()) {
            notice = host.message("gui.plugins.state.offline");
            statuses = List.of();
            observedAt = "";
            return;
        }
        if (!response.is2xx() || response.body() == null) {
            notice = response.status() == 403 ? host.message("gui.plugins.state.forbidden") : host.message(
                    "gui.plugins.state.error");
            statuses = List.of();
            observedAt = "";
            return;
        }
        recoveryMode = response.body().path("recoveryMode").asBoolean(false);
        observedAt = response.body().path("observedAt").asText("");
        List<PluginStatusRow> rows = new ArrayList<>();
        for (DesktopUiHost.GuiValue plugin : response.body().path("plugins")) {
            String id = plugin.path("id").asText("unknown");
            rows.add(new PluginStatusRow(
                    safeId(id),
                    plugin.path("name").asText(id),
                    nullableText(plugin, "source"),
                    nullableText(plugin, "status"),
                    nullableText(plugin, "runtimePhase"),
                    plugin.path("managed").asBoolean(false),
                    plugin.path("required").asBoolean(false),
                    nullableText(plugin, "version"),
                    nullableText(plugin.path("verification"), "status"),
                    nullableText(plugin.path("verification"), "diagnosticCode"),
                    nullableText(plugin.path("verification"), "lastVerifiedAt")
            ));
        }
        statuses = List.copyOf(rows);
        notice = rows.isEmpty() ? host.message("gui.plugins.state.empty") : "";
    }

    private static String nullableText(DesktopUiHost.GuiValue value, String field) {
        DesktopUiHost.GuiValue child = value == null ? null : value.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    long startedCount() {
        return statuses.stream().filter(plugin -> "STARTED".equals(plugin.statusCode())).count();
    }

    int count() {
        return statuses.size();
    }

    private record PluginStatusRow(
            String id,
            String name,
            String source,
            String statusCode,
            String phaseCode,
            boolean managed,
            boolean required,
            String version,
            String verificationStatus,
            String verificationDiagnosticCode,
            String lastVerifiedAt
    ) {
    }
}
