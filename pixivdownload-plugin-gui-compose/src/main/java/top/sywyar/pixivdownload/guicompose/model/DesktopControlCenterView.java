package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 控制中心首页与自动化概览渲染。
 */
final class DesktopControlCenterView {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final String rootFolder;

    DesktopControlCenterView(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            String rootFolder
    ) {
        this.owner = owner;
        this.host = host;
        this.rootFolder = rootFolder;
    }

    DesktopUiDocument.Page homePage(Map<String, Runnable> nextActions) {
        DesktopUiHost.GuiValue snapshot = owner.controlCenterSnapshot();

        List<DesktopUiNode.HomeMetric> metrics = new ArrayList<>();
        for (DesktopUiHost.GuiValue owned : snapshot.path("cards")) {
            DesktopUiHost.GuiValue card = owned.path("card");
            String base = "home.metrics." + safeId(owned.path("owner").path("pluginId").asText("unknown"))
                    + "." + safeId(card.path("cardId").asText("unknown"));
            metrics.add(new DesktopUiNode.HomeMetric(base, guiToken(card.path("title")),
                    availability(card.path("availability").asText("UNAVAILABLE")) == DesktopControlCenterAvailability.UNAVAILABLE
                            ? TextToken.raw("—") : guiToken(card.path("primaryValue")), null,
                    guiToken(card.path("supportingText")), freshness(card)));
        }
        metrics.add(storageCard());

        List<DesktopUiNode.HomeShortcut> shortcuts = new ArrayList<>();
        for (QuickStartEntry entry : quickStartEntries(owner.currentSources())) {
            NavigationContribution navigation = entry.navigation();
            String base = "home.quick-start." + safeId(entry.owner()) + "." + safeId(navigation.id());
            String action = base + ".open";
            nextActions.put(action, () -> {
                if (quickStartEntries(owner.currentSources()).contains(entry)) owner.openWeb(navigation.href());
                else owner.rebuild();
            });
            TextToken label = token(navigation.labelNamespace(), navigation.labelI18nKey(), navigation.id());
            TextToken summary = navigation.descriptionI18nKey().isBlank() ? composeToken("home.shortcut.open")
                    : token(navigation.labelNamespace(), navigation.descriptionI18nKey(), "");
            shortcuts.add(new DesktopUiNode.HomeShortcut(
                    new DesktopUiNode.Button(base + ".button", action, label, summary,
                            ButtonStyle.NORMAL, true, quickStartIcon(navigation.icon())), navigation.icon()));
        }

        List<DesktopUiNode.HomeTask> tasks = new ArrayList<>();
        for (DesktopUiHost.GuiValue owned : snapshot.path("runningTasks")) {
            DesktopUiHost.GuiValue task = owned.path("task");
            String base = "home.running." + safeId(owned.path("owner").path("pluginId").asText("unknown"))
                    + "." + safeId(task.path("taskId").asText("unknown"));
            double progress = parseDouble(task.path("progress").asText(""), -1d);
            var available = availability(task.path("availability").asText("UNAVAILABLE"));
            tasks.add(new DesktopUiNode.HomeTask(base, guiToken(task.path("title")),
                    guiToken(task.path("supportingText")),
                    key("desktop.ui.home.task.status." + task.path("status").asText("UNKNOWN").toLowerCase(Locale.ROOT)),
                    available == DesktopControlCenterAvailability.AVAILABLE && progress >= 0 && progress <= 1
                            ? progress : null,
                    freshness(task)));
        }
        DesktopUiNode content = new DesktopUiNode.HomeOverview("home.overview", shortcuts, tasks, metrics,
                snapshot.path("runningTasks").isArray(),
                new DesktopUiNode.Text("home.system.backend", TextToken.raw(owner.backendMessage()),
                        owner.backendTextStyle(), true, false), owner.backendStartingAt(), systemStatus());
        return owner.page("home", DesktopUiIcon.HOME, content, new DesktopUiNode.Insets(0, 0, 0, 0), null);
    }

    private DesktopUiNode.HomeSystem systemStatus() {
        TextToken proxy = composeToken("home.freshness.unavailable");
        TextToken endpoint = null;
        try {
            Map<String, String> config = host.applicationConfig().readAll(
                    List.of("proxy.enabled", "proxy.host", "proxy.port"));
            boolean enabled = Boolean.parseBoolean(config.getOrDefault("proxy.enabled", "false"));
            if (enabled) {
                String address = config.getOrDefault("proxy.host", host.defaultProxyHost());
                String port = config.getOrDefault("proxy.port", Integer.toString(host.defaultProxyPort()));
                if (address.contains(":") && !address.startsWith("[")) address = "[" + address + "]";
                endpoint = TextToken.raw(address + ":" + port);
            }
            proxy = composeToken(enabled ? "home.proxy.enabled" : "home.proxy.disabled");
        } catch (Exception ignored) {
            // 无法读取配置时保留未知状态，不把读取失败解释为已关闭代理。
        }
        return new DesktopUiNode.HomeSystem(proxy, endpoint, owner.pluginSummary());
    }

    private static TextToken freshness(DesktopUiHost.GuiValue fact) {
        var state = availability(fact.path("availability").asText("UNAVAILABLE"));
        return state == DesktopControlCenterAvailability.AVAILABLE ? null
                : new TextToken("gui-compose", "gui.compose.home.freshness." + state.name().toLowerCase(Locale.ROOT),
                        "", List.of(formatTimestamp(fact.path("observedAt").asText(""))));
    }

    DesktopUiNode automationPage(Map<String, Runnable> nextActions) {
        DesktopUiHost.GuiValue snapshot = owner.controlCenterSnapshot();
        List<DesktopUiNode.AutomationPlan> plans = new ArrayList<>();
        List<DesktopUiNode.AutomationSource> sources = new ArrayList<>();
        List<DesktopUiNode.Button> management = new ArrayList<>();
        Map<String, String> actions = new java.util.HashMap<>();
        for (QuickStartEntry entry : quickStartEntries(owner.currentSources())) {
            if (quickStartIcon(entry.navigation().icon()) != DesktopUiIcon.DOWNLOAD) continue;
            String action = "automation.manage." + safeId(entry.owner());
            if (actions.putIfAbsent(entry.owner(), action) != null) continue;
            nextActions.put(action, () -> owner.openWeb(entry.navigation().href()));
            management.add(new DesktopUiNode.Button(action, action,
                    composeToken("automation.manage"), null, ButtonStyle.NORMAL, true, DesktopUiIcon.OPEN));
        }
        for (DesktopUiHost.GuiValue owned : snapshot.path("automations")) {
            String plugin = owned.path("owner").path("pluginId").asText("unknown");
            DesktopUiHost.GuiValue automation = owned.path("snapshot");
            String available = availability(automation.path("availability").asText("UNAVAILABLE")).name();
            sources.add(new DesktopUiNode.AutomationSource(plugin, available,
                    parseInstant(automation.path("observedAt").asText("")).map(Instant::toEpochMilli).orElse(null)));
            for (DesktopUiHost.GuiValue task : automation.path("tasks")) {
                String taskId = task.path("taskId").asText("unknown");
                List<Long> runs = values(task.path("nextRuns")).stream()
                        .map(DesktopUiHost.GuiValue::asText).map(DesktopControlCenterView::parseInstant)
                        .flatMap(Optional::stream).map(Instant::toEpochMilli).distinct().sorted().toList();
                plans.add(new DesktopUiNode.AutomationPlan(
                        "automation.plan." + safeId(plugin) + "." + safeId(taskId), plugin, taskId,
                        guiToken(task.path("title")), guiToken(task.path("triggerSummary")),
                        task.path("status").asText("UNKNOWN"), task.path("lastResult").asText("UNKNOWN"),
                        runs, parseInstant(task.path("observedAt").asText("")).map(Instant::toEpochMilli).orElse(null),
                        available, actions.get(plugin)));
            }
        }
        return new DesktopUiNode.AutomationOverview("automation.overview",
                parseInstant(snapshot.path("observedAt").asText("")).map(Instant::toEpochMilli).orElse(0L),
                snapshot.path("automations").isArray(), plans, sources, management);
    }

    private static TextToken composeToken(String key) {
        return new TextToken("gui-compose", "gui.compose." + key, "", List.of());
    }

    private static Optional<Instant> parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(
                    value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String formatTimestamp(String value) {
        return parseInstant(value).map(DesktopUiNodes::formatTimestamp).orElse("—");
    }

    static String formatCompactBinarySize(long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("bytes must not be negative");
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        String number = value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value) : String.format(
                Locale.ROOT,
                "%.1f",
                value
        );
        return number + " " + units[unit];
    }

    private DesktopUiNode.HomeMetric storageCard() {
        TextToken title = composeToken("home.storage-available");
        try {
            Path path = Path.of(rootFolder).toAbsolutePath().normalize();
            while (path != null && !Files.exists(path)) path = path.getParent();
            if (path == null) throw new IOException("no existing ancestor");
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long available = store.getUsableSpace();
            if (total <= 0L || available < 0L || available > total) throw new IOException("invalid file store");
            String[] capacity = formatCompactBinarySize(available).split(" ", 2);
            return new DesktopUiNode.HomeMetric("home.storage", title,
                    TextToken.raw(capacity[0]), TextToken.raw(capacity[1]),
                    new TextToken("gui-compose", "gui.compose.home.storage", "", List.of(rootFolder)),
                    null);
        } catch (Exception ignored) {
            return new DesktopUiNode.HomeMetric("home.storage", title, TextToken.raw("—"), null,
                    key("desktop.ui.home.storage.unavailable"), null);
        }
    }

    static List<QuickStartEntry> quickStartEntries(List<DesktopUiPluginSnapshot> sources) {
        return navigationEntries(sources, NavigationPlacements.DESKTOP_QUICK_START);
    }

    static List<QuickStartEntry> navigationEntries(List<DesktopUiPluginSnapshot> sources, String placement) {
        List<QuickStartEntry> entries = new ArrayList<>();
        for (DesktopUiPluginSnapshot source : sources) {
            try {
                List<WebRouteContribution> routes = source.routes();
                for (NavigationContribution navigation : source.navigation()) {
                    if (navigation != null && navigation.placements().contains(placement) && navigation.visibleTo() != null && navigation.visibleTo().supportsUiVisibility() && validQuickStartRoute(
                            navigation,
                            routes
                    )) {
                        entries.add(new QuickStartEntry(source.id(), navigation, source.fingerprint()));
                    }
                }
            } catch (RuntimeException ignored) {
                // 隔离可选插件条目异常。
            }
        }
        entries.sort(Comparator.comparingInt((QuickStartEntry entry) -> entry.navigation().priority()).thenComparing(
                QuickStartEntry::owner).thenComparing(entry -> entry.navigation().id()));
        return entries;
    }

    private static boolean validQuickStartRoute(
            NavigationContribution navigation,
            List<WebRouteContribution> routes
    ) {
        if (!safeHref(navigation.href())) return false;
        URI target;
        try {
            target = URI.create(navigation.href());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawPath() == null || !target.getRawPath().startsWith(
                "/")) return false;
        String path = target.getRawPath();
        return routes != null && routes.stream().filter(Objects::nonNull).anyMatch(route -> path.equals(
                route.pathPattern()) && route.acceptsMethod(HttpMethod.GET) && route.accessPolicy() != null && route.accessPolicy().supportsUiVisibility() && navigationNotBroader(
                        navigation.visibleTo(),
                        route.accessPolicy()
                ));
    }

    private static boolean navigationNotBroader(
            AccessPolicy navigation,
            AccessPolicy route
    ) {
        for (Audience audience : Audience.values()) {
            if (navigation.isVisibleTo(audience) && !route.isVisibleTo(audience)) return false;
        }
        return true;
    }

    private static DesktopUiIcon quickStartIcon(String icon) {
        return switch (nullToEmpty(icon)) {
            case "download" -> DesktopUiIcon.DOWNLOAD;
            case "chart-bar" -> DesktopUiIcon.STATISTICS;
            default -> DesktopUiIcon.OPEN;
        };
    }

    record QuickStartEntry(
            String owner,
            NavigationContribution navigation,
            DesktopUiPluginSnapshot.Fingerprint fingerprint
    ) {
    }
}
