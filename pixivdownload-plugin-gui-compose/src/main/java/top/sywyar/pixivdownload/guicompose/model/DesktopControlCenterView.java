package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ProgressStyle;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        List<DesktopUiHost.GuiValue> runningTasks = values(owner.controlCenterSnapshot().path(
                "runningTasks"));
        DesktopUiNode heroContent = runningTasks.isEmpty() ? text(
                "home.hero.empty",
                "desktop.ui.home.running.empty",
                TextStyle.CAPTION
        ) : runningTask("home.hero", runningTasks.get(0));
        DesktopUiNode hero = new DesktopUiNode.Surface(
                "home.hero",
                DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(
                        18,
                        20,
                        18,
                        20
                ),
                true,
                column(
                        "home.hero.content",
                        text("home.hero.title", "desktop.ui.home.hero.title", TextStyle.HEADING),
                        heroContent
                )
        );

        long startedPlugins = owner.startedPluginCount();
        String applicationVersion = host.applicationVersion();
        String version = applicationVersion.isBlank()
                ? host.message("app.version.unknown")
                : applicationVersion;
        String connectivityAction = "home.system.connectivity.check";
        nextActions.put(connectivityAction, owner::checkPixivConnectivity);
        DesktopUiNode system = new DesktopUiNode.Surface(
                "home.system",
                DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(
                        18,
                        20,
                        18,
                        20
                ),
                true,
                column(
                        "home.system.content",
                        new DesktopUiNode.Dock(
                                "home.system.header",
                                8,
                                null,
                                null,
                                null,
                                text(
                                        "home.system.title",
                                        "desktop.ui.home.system.title",
                                        TextStyle.HEADING
                                ),
                                new DesktopUiNode.Text(
                                        "home.system.version",
                                        appToken(
                                                "gui.about.version",
                                                version
                                        ),
                                        TextStyle.CAPTION,
                                        false,
                                        false,
                                        DesktopUiNode.TextAlignment.END
                                )
                        ),
                        raw(
                                "home.system.backend",
                                owner.backendMessage(),
                                owner.backendTextStyle()
                        ),
                        new DesktopUiNode.Container(
                                "home.system.connectivity",
                                ContainerLayout.ROW,
                                1,
                                8,
                                Alignment.CENTER,
                                List.of(
                                        new DesktopUiNode.Text(
                                                "home.system.connectivity.label",
                                                key("gui.status.label.pixiv-connectivity"),
                                                TextStyle.CAPTION,
                                                true,
                                                false
                                        ),
                                        new DesktopUiNode.Link(
                                                "home.system.connectivity.value",
                                                connectivityAction,
                                                TextToken.raw(owner.pixivConnectivityDetails()),
                                                key("gui.status.pixiv-connectivity.tooltip"),
                                                owner.canCheckPixivConnectivity()
                                        )
                                )
                        ),
                        new DesktopUiNode.Text(
                                "home.system.plugins",
                                appToken(
                                        "desktop.ui.home.system.plugins",
                                        startedPlugins,
                                        owner.pluginCount()
                                ),
                                TextStyle.CAPTION,
                                true,
                                false
                        )
                )
        );

        List<DesktopUiNode> metrics = new ArrayList<>();
        for (DesktopUiHost.GuiValue owned : owner.controlCenterSnapshot().path("cards")) {
            DesktopUiHost.GuiValue card = owned.path("card");
            String owner = safeId(owned.path("owner").path("pluginId").asText("unknown"));
            String cardId = safeId(card.path("cardId").asText("unknown"));
            metrics.add(dashboardCard(
                    "home.metrics." + owner + "." + cardId,
                    card,
                    icon(card.path("icon").asText("INFO")),
                    tone(card.path("tone").asText("DEFAULT"))
            ));
        }
        metrics.add(storageCard());

        List<DesktopUiNode> quickStarts = new ArrayList<>();
        for (QuickStartEntry entry : quickStartEntries()) {
            NavigationContribution navigation = entry.navigation();
            String base = "home.quick-start." + safeId(entry.owner()) + "." + safeId(navigation.id());
            String action = base + ".open";
            nextActions.put(action, () -> owner.openWeb(navigation.href()));
            quickStarts.add(new DesktopUiNode.Container(
                    base,
                    ContainerLayout.ROW,
                    1,
                    8,
                    Alignment.CENTER,
                    List.of(
                            new DesktopUiNode.Icon(
                                    base + ".icon",
                                    quickStartIcon(navigation.icon()),
                                    DesktopUiTone.INFO,
                                    token(
                                            navigation.labelNamespace(),
                                            navigation.labelI18nKey(),
                                            navigation.id()
                                    )
                            ),
                            new DesktopUiNode.Button(
                                    base + ".button",
                                    action,
                                    token(
                                            navigation.labelNamespace(),
                                            navigation.labelI18nKey(),
                                            navigation.id()
                                    ),
                                    null,
                                    ButtonStyle.NORMAL,
                                    true
                            )
                    )
            ));
        }
        DesktopUiNode quickStartContent = quickStarts.isEmpty() ? text(
                "home.quick-start.empty",
                "desktop.ui.home.quick-start.empty",
                TextStyle.CAPTION
        ) : new DesktopUiNode.Container(
                "home.quick-start.grid",
                ContainerLayout.GRID,
                2,
                12,
                Alignment.STRETCH,
                quickStarts
        );

        List<DesktopUiNode> taskNodes = new ArrayList<>();
        for (DesktopUiHost.GuiValue task : runningTasks)
            taskNodes.add(runningTask("home.running", task));
        DesktopUiNode runningContent = taskNodes.isEmpty() ? text(
                "home.running.empty",
                "desktop.ui.home.running.empty",
                TextStyle.CAPTION
        ) : column(
                "home.running.list",
                taskNodes
        );

        int hour = LocalTime.now().getHour();
        String greeting = hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening";
        DesktopUiNode content = scroll(
                "home.scroll",
                column(
                        "home.content",
                        text(
                                "home.greeting",
                                "desktop.ui.home.greeting." + greeting,
                                TextStyle.TITLE
                        ),
                        new DesktopUiNode.AdaptiveGrid(
                                "home.overview",
                                280,
                                2,
                                14,
                                14,
                                List.of(system, hero)
                        ),
                        group(
                                "home.metrics-section",
                                "desktop.ui.home.metrics.title",
                                new DesktopUiNode.PagedRow(
                                        "home.metrics",
                                        DesktopUiNode.PagedRow.FIXED_ITEMS_PER_PAGE,
                                        12,
                                        metrics
                                )
                        ),
                        group("home.running", "desktop.ui.home.running.title", runningContent)
                )
        );
        DesktopUiNode floatingAction = column(
                "home.quick-start",
                text(
                        "home.quick-start.title",
                        "desktop.ui.home.quick-start.title",
                        TextStyle.HEADING
                ),
                quickStartContent
        );
        return owner.page(
                "home",
                DesktopUiIcon.HOME,
                content,
                new DesktopUiNode.Insets(
                        16,
                        24,
                        16,
                        24
                ),
                floatingAction
        );
    }

    private DesktopUiNode dashboardCard(
            String base,
            DesktopUiHost.GuiValue card,
            DesktopUiIcon icon,
            DesktopUiTone tone
    ) {
        DesktopControlCenterAvailability availability = availability(card.path("availability").asText(
                "UNAVAILABLE"));
        return dashboardCard(
                base,
                guiToken(card.path("title")),
                guiToken(card.path("primaryValue")),
                guiToken(card.path("supportingText")),
                icon,
                tone,
                availability
        );
    }

    DesktopUiNode automationPage() {
        DesktopUiHost.GuiValue controlCenter = owner.controlCenterSnapshot();
        List<DesktopUiNode> sources = new ArrayList<>();
        List<DesktopUiNode> tasks = new ArrayList<>();
        List<AutomationRun> runs = new ArrayList<>();
        for (DesktopUiHost.GuiValue owned : controlCenter.path("automations")) {
            String owner = safeId(owned.path("owner").path("pluginId").asText("unknown"));
            DesktopUiHost.GuiValue automation = owned.path("snapshot");
            DesktopControlCenterAvailability availability = availability(automation.path(
                    "availability").asText("UNAVAILABLE"));
            sources.add(dashboardCard(
                    "automation.source." + owner,
                    appToken("desktop.ui.automation.source.title", owner),
                    key("desktop.ui.automation.availability." + availability.name().toLowerCase(
                            Locale.ROOT)),
                    appToken(
                            "desktop.ui.automation.observed-at",
                            formatTimestamp(automation.path("observedAt").asText(""))
                    ),
                    DesktopUiIcon.AUTOMATION,
                    availability == DesktopControlCenterAvailability.AVAILABLE ? DesktopUiTone.SUCCESS : DesktopUiTone.WARNING,
                    availability
            ));
            for (DesktopUiHost.GuiValue task : automation.path("tasks")) {
                String taskId = safeId(task.path("taskId").asText("unknown"));
                tasks.add(automationTask(owner, taskId, task));
                for (DesktopUiHost.GuiValue nextRun : task.path("nextRuns")) {
                    parseInstant(nextRun.asText("")).ifPresent(at -> runs.add(new AutomationRun(
                            at,
                            owner,
                            taskId,
                            task
                    )));
                }
            }
        }
        runs.sort(Comparator.comparing(AutomationRun::at).thenComparing(AutomationRun::owner).thenComparing(
                AutomationRun::taskId));

        DesktopUiNode timeline = automationTimeline(controlCenter, runs);

        return scroll(
                "automation.scroll",
                column(
                        "automation.content",
                        text("automation.title", "desktop.ui.automation.title", TextStyle.TITLE),
                        text("automation.intro", "desktop.ui.automation.intro", TextStyle.CAPTION),
                        group(
                                "automation.sources",
                                "desktop.ui.automation.scheduler.title",
                                sources.isEmpty() ? text(
                                        "automation.sources.empty",
                                        "desktop.ui.automation.empty",
                                        TextStyle.CAPTION
                                ) : new DesktopUiNode.AdaptiveGrid(
                                        "automation.sources.grid",
                                        240,
                                        2,
                                        12,
                                        12,
                                        sources
                                )
                        ),
                        group(
                                "automation.timeline",
                                "desktop.ui.automation.timeline.title",
                                timeline
                        ),
                        group(
                                "automation.tasks",
                                "desktop.ui.automation.tasks.title",
                                tasks.isEmpty() ? text(
                                        "automation.tasks.empty",
                                        "desktop.ui.automation.empty",
                                        TextStyle.CAPTION
                                ) : column("automation.tasks.list", tasks)
                        )
                )
        );
    }

    private DesktopUiNode automationTimeline(
            DesktopUiHost.GuiValue controlCenter,
            List<AutomationRun> runs
    ) {
        Optional<Instant> startValue = parseInstant(controlCenter.path("observedAt").asText(""));
        if (startValue.isEmpty() || runs.isEmpty()) {
            return text("automation.timeline.empty", "desktop.ui.automation.timeline.empty", TextStyle.CAPTION);
        }
        Instant start = startValue.orElseThrow();
        Instant end = start.plusSeconds(24L * 60L * 60L);
        List<DesktopUiNode.ScheduleTimelineItem> items = runs.stream()
                .filter(run -> !run.at().isBefore(start) && !run.at().isAfter(end))
                .map(run -> new DesktopUiNode.ScheduleTimelineItem(
                        run.at().toEpochMilli(),
                        TextToken.raw(formatScheduleTime(run.at())),
                        guiToken(run.task().path("title")),
                        guiToken(run.task().path("triggerSummary"))
                ))
                .toList();
        if (items.isEmpty()) {
            return text("automation.timeline.empty", "desktop.ui.automation.timeline.empty", TextStyle.CAPTION);
        }
        long now = Math.max(start.toEpochMilli(), Math.min(Instant.now().toEpochMilli(), end.toEpochMilli()));
        return new DesktopUiNode.ScheduleTimeline(
                "automation.timeline.schedule",
                start.toEpochMilli(),
                now,
                end.toEpochMilli(),
                items
        );
    }

    private DesktopUiNode automationTask(
            String owner,
            String taskId,
            DesktopUiHost.GuiValue task
    ) {
        String base = "automation.task." + owner + "." + taskId;
        String status = task.path("status").asText("UNKNOWN").toLowerCase(Locale.ROOT);
        String result = task.path("lastResult").asText("UNKNOWN").toLowerCase(Locale.ROOT);
        Optional<Instant> nextRun = values(task.path("nextRuns")).stream().map(DesktopUiHost.GuiValue::asText).map(
                DesktopControlCenterView::parseInstant).flatMap(Optional::stream).min(Comparator.naturalOrder());
        return new DesktopUiNode.Surface(
                base,
                DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(
                        12,
                        14,
                        12,
                        14
                ),
                true,
                column(
                        base + ".content",
                        new DesktopUiNode.Text(
                                base + ".title",
                                guiToken(task.path("title")),
                                TextStyle.HEADING,
                                true,
                                false
                        ),
                        new DesktopUiNode.Text(
                                base + ".trigger",
                                guiToken(task.path("triggerSummary")),
                                TextStyle.CAPTION,
                                true,
                                false
                        ),
                        text(
                                base + ".status",
                                "desktop.ui.automation.status." + status,
                                automationStatusStyle(status)
                        ),
                        text(
                                base + ".last-result",
                                "desktop.ui.automation.last-result." + result,
                                "error".equals(result) ? TextStyle.ERROR : TextStyle.CAPTION
                        ),
                        new DesktopUiNode.Text(
                                base + ".next-run",
                                nextRun.<TextToken>map(at -> appToken(
                                        "desktop.ui.automation.next-run",
                                        formatTimestamp(at)
                                )).orElseGet(() -> key("desktop.ui.automation.next-run.none")),
                                TextStyle.CAPTION,
                                true,
                                false
                        ),
                        new DesktopUiNode.Text(
                                base + ".observed-at",
                                appToken(
                                        "desktop.ui.automation.observed-at",
                                        formatTimestamp(task.path("observedAt").asText(""))
                                ),
                                TextStyle.CAPTION,
                                true,
                                false
                        )
                )
        );
    }

    private static TextStyle automationStatusStyle(String status) {
        return switch (status) {
            case "running" -> TextStyle.SUCCESS;
            case "suspended", "cancel_requested" -> TextStyle.WARNING;
            case "disabled" -> TextStyle.CAPTION;
            default -> TextStyle.BODY;
        };
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

    private static String formatTimestamp(Instant value) {
        return DesktopUiNodes.formatTimestamp(value);
    }

    private static String formatScheduleTime(Instant value) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(value);
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
        return number + units[unit];
    }

    private DesktopUiNode dashboardCard(
            String base,
            TextToken title,
            TextToken primary,
            TextToken supporting,
            DesktopUiIcon icon,
            DesktopUiTone tone,
            DesktopControlCenterAvailability availability
    ) {
        return dashboardCard(
                base,
                title,
                primary,
                supporting,
                icon,
                tone,
                availability,
                null
        );
    }

    private DesktopUiNode dashboardCard(
            String base,
            TextToken title,
            TextToken primary,
            TextToken supporting,
            DesktopUiIcon icon,
            DesktopUiTone tone,
            DesktopControlCenterAvailability availability,
            DesktopUiNode summaryGraphic
    ) {
        DesktopUiNode.SurfaceStyle style = switch (availability) {
            case UNAVAILABLE -> DesktopUiNode.SurfaceStyle.MUTED;
            case STALE -> DesktopUiNode.SurfaceStyle.WARNING;
            case AVAILABLE -> switch (tone) {
                case SUCCESS -> DesktopUiNode.SurfaceStyle.SUCCESS;
                case INFO -> DesktopUiNode.SurfaceStyle.INFO;
                case WARNING -> DesktopUiNode.SurfaceStyle.WARNING;
                case ERROR -> DesktopUiNode.SurfaceStyle.ERROR;
                case DEFAULT -> DesktopUiNode.SurfaceStyle.CARD;
            };
        };
        DesktopUiNode primaryContent = new DesktopUiNode.Text(
                base + ".primary",
                primary,
                TextStyle.TITLE,
                true,
                false
        );
        if (summaryGraphic != null) {
            primaryContent = new DesktopUiNode.Container(
                    base + ".summary",
                    ContainerLayout.ROW,
                    1,
                    12,
                    Alignment.CENTER,
                    List.of(summaryGraphic, primaryContent)
            );
        }
        return new DesktopUiNode.Surface(
                base,
                style,
                new DesktopUiNode.Insets(
                        18,
                        20,
                        18,
                        20
                ),
                true,
                column(
                        base + ".content",
                        new DesktopUiNode.Dock(
                                base + ".header",
                                8,
                                null,
                                null,
                                null,
                                new DesktopUiNode.Text(
                                        base + ".title",
                                        title,
                                        TextStyle.CAPTION,
                                        true,
                                        false
                                ),
                                new DesktopUiNode.Icon(
                                        base + ".icon",
                                        icon,
                                        tone,
                                        title
                                )
                        ),
                        primaryContent,
                        new DesktopUiNode.Text(
                                base + ".supporting",
                                supporting,
                                availability == DesktopControlCenterAvailability.AVAILABLE ? TextStyle.CAPTION : TextStyle.WARNING,
                                true,
                                false
                        )
                )
        );
    }

    private DesktopUiNode storageCard() {
        try {
            Path path = Path.of(rootFolder).toAbsolutePath().normalize();
            while (path != null && !Files.exists(path)) path = path.getParent();
            if (path == null) throw new IOException("no existing ancestor");
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long available = store.getUsableSpace();
            if (total <= 0L || available < 0L || available > total)
                throw new IOException("invalid file store");
            long used = total - available;
            return dashboardCard(
                    "home.storage",
                    key("desktop.ui.home.storage.title"),
                    appToken(
                            "desktop.ui.home.storage.value",
                            formatCompactBinarySize(used),
                            formatCompactBinarySize(total)
                    ),
                    key("desktop.ui.home.storage.supporting"),
                    DesktopUiIcon.STORAGE,
                    DesktopUiTone.INFO,
                    DesktopControlCenterAvailability.AVAILABLE,
                    new DesktopUiNode.Progress(
                            "home.storage.usage",
                            (double) used / total,
                            false,
                            null,
                            ProgressStyle.CIRCULAR
                    )
            );
        } catch (Exception ignored) {
            return dashboardCard(
                    "home.storage",
                    key("desktop.ui.home.storage.title"),
                    TextToken.raw("—"),
                    key("desktop.ui.home.storage.unavailable"),
                    DesktopUiIcon.STORAGE,
                    DesktopUiTone.DEFAULT,
                    DesktopControlCenterAvailability.UNAVAILABLE
            );
        }
    }

    private DesktopUiNode runningTask(
            String section,
            DesktopUiHost.GuiValue owned
    ) {
        DesktopUiHost.GuiValue task = owned.path("task");
        String base = section + "." + safeId(owned.path("owner").path("pluginId").asText("unknown")) + "." + safeId(
                task.path("taskId").asText("unknown"));
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(new DesktopUiNode.Text(
                base + ".title",
                guiToken(task.path("title")),
                TextStyle.EMPHASIS,
                true,
                false
        ));
        content.add(new DesktopUiNode.Text(
                base + ".supporting",
                guiToken(task.path("supportingText")),
                TextStyle.CAPTION,
                true,
                false
        ));
        String status = task.path("status").asText("UNKNOWN").toLowerCase(Locale.ROOT);
        content.add(text(
                base + ".status",
                "desktop.ui.home.task.status." + status,
                TextStyle.CAPTION
        ));
        double progress = parseDouble(task.path("progress").asText(""), -1d);
        if (progress >= 0d && progress <= 1d) {
            content.add(new DesktopUiNode.Progress(
                    base + ".progress",
                    progress,
                    false,
                    appToken("desktop.ui.home.task.progress", Math.round(progress * 100d))
            ));
        }
        return new DesktopUiNode.Surface(
                base,
                DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(
                        10,
                        12,
                        10,
                        12
                ),
                true,
                column(base + ".content", content)
        );
    }

    private List<QuickStartEntry> quickStartEntries() {
        List<QuickStartEntry> entries = new ArrayList<>();
        for (DesktopUiPluginSnapshot source : owner.currentSources()) {
            try {
                List<WebRouteContribution> routes = source.routes();
                for (NavigationContribution navigation : source.navigation()) {
                    if (navigation != null && navigation.placements().contains(NavigationPlacements.DESKTOP_QUICK_START) && navigation.visibleTo() != null && navigation.visibleTo().supportsUiVisibility() && validQuickStartRoute(
                            navigation,
                            routes
                    )) {
                        entries.add(new QuickStartEntry(source.id(), navigation));
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

    private record AutomationRun(
            Instant at,
            String owner,
            String taskId,
            DesktopUiHost.GuiValue task
    ) {
    }

    private record QuickStartEntry(
            String owner,
            NavigationContribution navigation
    ) {
    }
}
