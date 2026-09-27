package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 运行状态、连通性、FFmpeg 与应用更新。
 */
final class DesktopStatusController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopStatusController.class);

    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final int serverPort;
    private final String rootFolder;
    final DesktopUpdateController updates;

    private volatile String statusPort = "--";
    private volatile String statusMode = "--";
    private volatile String statusStartTime = "--";
    private volatile String statusProtocol = "--";
    private volatile DesktopUiHost.GuiValue controlCenterSnapshot = DesktopUiHost.GuiValue.of(Map.of());
    private volatile String connectivityDetails = "";
    private volatile boolean statusConnected;
    private volatile boolean connectivityChecking;
    private volatile long lastConnectivityCheckAt;
    private volatile boolean ffmpegInstalling;
    private volatile Optional<DesktopUiHost.FfmpegInstallation> ffmpegInstallation = Optional.empty();
    private final AtomicBoolean ffmpegChecking = new AtomicBoolean();
    private volatile boolean ffmpegChecked;
    private volatile boolean ffmpegCheckFailed;
    private volatile boolean ffmpegConfirm;
    private volatile TextToken ffmpegNotice;
    private volatile boolean ffmpegNoticeError;
    private volatile String ffmpegConfiguredPath;
    private final Map<String, String> formValues;
    private final AtomicBoolean ffmpegDirectoryOpening = new AtomicBoolean();
    private volatile double ffmpegProgress;

    DesktopStatusController(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            int serverPort,
            String rootFolder,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.formValues = formValues;
        this.updates = new DesktopUpdateController(owner, host, formValues);
    }

    DesktopUiHost.GuiValue controlCenterSnapshot() {
        return controlCenterSnapshot;
    }

    String connectivitySummary() {
        return connectivityDetails.isBlank()
                ? host.message("gui.status.pixiv-connectivity.action.check")
                : connectivityDetails;
    }

    boolean canCheckConnectivity() {
        return !owner.busy() && !connectivityChecking &&
                owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING;
    }

    boolean connected() {
        return statusConnected;
    }

    void resetConnection() {
        statusConnected = false;
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> webActions = owner.navigation.webEntryButtons(
                NavigationPlacements.GUI_STATUS_ACTIONS,
                "status.web",
                nextActions
        );
        webActions.add(
                0,
                button(
                        "status.web.batch",
                        "status.web.batch",
                        "gui.action.open-batch",
                        true,
                        nextActions,
                        () -> owner.openWeb("/pixiv-batch.html")
                )
        );
        List<DesktopUiNode> children = new ArrayList<>();
        children.add(raw(
                "status.backend.state",
                owner.backendMessage(),
                owner.backendTextStyle()
        ));
        if (!owner.statusNotice.isBlank()) children.add(new DesktopUiNode.Surface(
                "status.notice",
                DesktopUiNode.SurfaceStyle.WARNING,
                new DesktopUiNode.Insets(
                        8,
                        12,
                        8,
                        12
                ),
                true,
                status("status.notice.text", owner.statusNotice)
        ));
        children.addAll(updates.banners("status.update", nextActions));
        children.add(new DesktopUiNode.Form(
                "status.grid",
                DesktopUiNode.FormStyle.KEY_VALUE,
                null,
                List.of(
                        new DesktopUiNode.FormRow(
                                "status.port.row",
                                key("gui.status.label.port"),
                                null,
                                raw("status.port.value", statusPort, TextStyle.EMPHASIS),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "status.mode.row",
                                key("gui.status.label.mode"),
                                null,
                                raw("status.mode.value", statusMode, TextStyle.EMPHASIS),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "status.start-time.row",
                                key("gui.status.label.start-time"),
                                null,
                                raw("status.start-time.value", statusStartTime, TextStyle.EMPHASIS),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "status.https.row",
                                key("gui.status.label.https"),
                                null,
                                raw("status.https.value", statusProtocol, TextStyle.EMPHASIS),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "status.connectivity.row",
                                key("gui.status.label.pixiv-connectivity"),
                                null,
                                row(
                                        "status.connectivity.value",
                                        raw(
                                                "status.connectivity.text",
                                                connectivityDetails,
                                                TextStyle.BODY
                                        ),
                                        button(
                                                "status.connectivity.check",
                                                "status.connectivity.check",
                                                "gui.status.pixiv-connectivity.action.check",
                                                canCheckConnectivity(),
                                                nextActions,
                                                this::checkConnectivity
                                        )
                                ),
                                null
                        )
                )
        ));
        children.add(text(
                "status.web.hint",
                "gui.status.hint.web-console",
                TextStyle.CAPTION
        ));
        DesktopUiNode actions = column(
                "status.actions",
                group(
                        "status.web",
                        "gui.action.group.navigation",
                        row("status.web.actions", webActions)
                ),
                group(
                        "status.functions",
                        "gui.action.group.functions",
                        row(
                                "status.function.actions",
                                button(
                                        "status.open-folder",
                                        "status.open-folder",
                                        "gui.action.open-download-directory",
                                        !owner.busy(),
                                        nextActions,
                                        this::openDownloadDirectory
                                ),
                                button(
                                        "status.restart",
                                        "status.restart",
                                        "gui.action.restart-service",
                                        !owner.busy(),
                                        nextActions,
                                        this::requestBackendRestart
                                ),
                                button(
                                        "status.check-update",
                                        "status.check-update",
                                        "gui.update.action.check",
                                        !owner.busy(),
                                        nextActions,
                                        updates::checkUpdates
                                ),
                                button(
                                        "status.migrate-directory",
                                        "status.migrate-directory",
                                        "gui.action.migrate-directory",
                                        !owner.busy(),
                                        nextActions,
                                        owner::openDirectoryMigration
                                ),
                                button(
                                        "status.refresh",
                                        "status.refresh",
                                        "gui.plugins.action.refresh",
                                        !owner.busy(),
                                        nextActions,
                                        this::refresh
                                )
                        )
                )
        );
        return new DesktopUiNode.Dock(
                "status.root",
                12,
                null,
                scroll("status.scroll", column("status.content", children)),
                actions,
                null,
                null
        );
    }

    void refresh() {
        owner.runBusy(() -> {
            if (!ffmpegChecked) inspectFfmpeg();
            refreshSnapshot();
            owner.refreshOnboarding();
            owner.loadPluginStatus();
        });
    }

    void startPolling() {
        owner.executeAsync(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                    refreshSnapshot();
                    owner.refreshOnboarding();
                    owner.loadPluginStatus();
                    if (owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING && System.currentTimeMillis() - lastConnectivityCheckAt >= 60_000L) {
                        checkConnectivity();
                    }
                    owner.rebuildStatus();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void refreshSnapshot() {
        DesktopUiHost.GuiResponse response = host.guiGet("status", 2_000);
        statusConnected = response.successful() && response.body() != null;
        if (statusConnected) {
            DesktopUiHost.GuiValue body = response.body();
            statusPort = body.path("port").asText(Integer.toString(serverPort));
            statusMode = owner.localizedCode("gui.mode.", body.path("mode").asText("--"));
            statusStartTime = body.path("startTime").asText("--");
            statusProtocol = host.message(body.path("httpsEnabled").asBoolean(false) ? "gui.status.https.enabled" : "gui.status.https.disabled");
        } else {
            statusPort = Integer.toString(serverPort);
            statusMode = statusStartTime = statusProtocol = "--";
        }
        refreshControlCenterSnapshot();
    }

    private void refreshControlCenterSnapshot() {
        try {
            DesktopUiHost.GuiResponse response = host.controlCenterSnapshot();
            controlCenterSnapshot = response.successful() && response.body() != null && response.body().isObject() ? response.body() : DesktopUiHost.GuiValue.of(
                    Map.of());
        } catch (RuntimeException ignored) {
            controlCenterSnapshot = DesktopUiHost.GuiValue.of(Map.of());
        }
    }

    void checkConnectivity() {
        if (!canCheckConnectivity()) return;
        connectivityChecking = true;
        lastConnectivityCheckAt = System.currentTimeMillis();
        connectivityDetails = host.message("gui.status.pixiv-connectivity.checking");
        owner.rebuild();
        owner.executeAsync(() -> {
            try {
                DesktopUiHost.GuiResponse response = host.guiGet("pixiv-connectivity", 10_000);
                if (!response.reachable() || response.body() == null) {
                    connectivityDetails = host.message("gui.status.pixiv-connectivity.unavailable");
                } else {
                    DesktopUiHost.GuiValue body = response.body();
                    boolean reachable = body.path("reachable").asBoolean(false);
                    int status = body.path("statusCode").asInt(0);
                    long latency = body.path("latencyMs").asLong(0);
                    connectivityDetails = reachable ? host.message(
                            status > 0 ? "gui.status.pixiv-connectivity.reachable" : "gui.status.pixiv-connectivity.reachable-no-status",
                            status > 0 ? new Object[]{status, latency} : new Object[]{latency}
                    ) : host.message(
                            status > 0 ? "gui.status.pixiv-connectivity.unreachable-with-status" : "gui.status.pixiv-connectivity.unreachable",
                            status > 0 ? new Object[]{status, latency} : new Object[]{connectivityReason(
                                    body.path("errorType").asText(""))}
                    );
                }
            } catch (RuntimeException failure) {
                LOG.warn("Pixiv connectivity check failed", failure);
                connectivityDetails = host.message("gui.status.pixiv-connectivity.unavailable");
            } finally {
                connectivityChecking = false;
                owner.rebuild();
            }
        });
    }

    private String connectivityReason(String errorType) {
        return host.message("gui.status.pixiv-connectivity.reason." + switch (nullToEmpty(errorType)) {
            case "timeout", "interrupted", "network" -> errorType;
            default -> "unknown";
        });
    }

    DesktopUiNode.Group ffmpegPanel(Map<String, Runnable> nextActions) {
        Optional<DesktopUiHost.FfmpegInstallation> ffmpeg = ffmpegInstallation;
        List<DesktopUiNode> ffmpegNodes = new ArrayList<>();
        ffmpegNodes.add(text(
                "status.ffmpeg.intro",
                "gui.ffmpeg.panel.intro",
                TextStyle.BODY
        ));
        ffmpegNodes.add(new DesktopUiNode.Text("status.ffmpeg.state",
                ffmpegChecking.get() || !ffmpegChecked ? toolToken("checking")
                        : ffmpegCheckFailed ? toolToken("check-failed")
                        : TextToken.key(ffmpeg.isPresent() ? "gui.ffmpeg.badge.ready" : "gui.ffmpeg.badge.missing"),
                TextStyle.CAPTION, true, false));
        ffmpeg.ifPresent(value -> {
            ffmpegNodes.add(raw(
                    "status.ffmpeg.source",
                    host.message(
                            "gui.ffmpeg.source.label",
                            owner.localizedCode(
                                    "ffmpeg.source.",
                                    value.source().name().toLowerCase(Locale.ROOT)
                            )
                    ),
                    TextStyle.CAPTION
            ));
            ffmpegNodes.add(raw(
                    "status.ffmpeg.path",
                    host.message(
                            "gui.ffmpeg.path.label",
                            value.ffmpegPath() == null ? "--" : value.ffmpegPath()
                    ),
                    TextStyle.CODE
            ));
        });
        if (ffmpegNotice != null) ffmpegNodes.add(new DesktopUiNode.Text(
                "status.ffmpeg.notice", ffmpegNotice, ffmpegNoticeError ? TextStyle.ERROR : TextStyle.CAPTION, true, false));
        nextActions.put("status.ffmpeg.help", () -> owner.runBusy(() -> {
            try {
                host.openExternalUri(java.net.URI.create("https://ffmpeg.org/download.html"));
            } catch (Exception failure) {
                LOG.warn("Unable to open FFmpeg installation guide", failure);
                ffmpegNotice = TextToken.key("desktop.ui.action.failed");
                ffmpegNoticeError = true;
            }
        }));
        ffmpegNodes.add(new DesktopUiNode.Button(
                "status.ffmpeg.help", "status.ffmpeg.help", toolToken("media-help"),
                null, DesktopUiNode.ButtonStyle.NORMAL, !owner.busy()
        ));
        if (ffmpegConfirm) {
            ffmpegNodes.add(text("status.ffmpeg.confirmation", "gui.ffmpeg.dialog.install.confirm.message", TextStyle.BODY));
            ffmpegNodes.add(row("status.ffmpeg.actions",
                    button("ffmpeg.confirm.install", "ffmpeg.confirm.install", "gui.ffmpeg.action.download",
                            !owner.busy(), nextActions, () -> { ffmpegConfirm = false; installFfmpeg(); }),
                    button("ffmpeg.confirm.cancel", "ffmpeg.confirm.cancel", "desktop.ui.action.cancel",
                            !owner.busy(), nextActions, () -> { ffmpegConfirm = false; owner.rebuild(); })));
        } else {
            ffmpegNodes.add(new DesktopUiNode.Form("tools.ffmpeg.form", DesktopUiNode.FormStyle.COMPACT, null, List.of(
                    new DesktopUiNode.FormRow("tools.ffmpeg.path.row", TextToken.key("gui.config.field.ffmpeg.executable-path.label"),
                            TextToken.key("gui.config.field.ffmpeg.executable-path.help"),
                            input("tools.ffmpeg.path", "tools.ffmpeg.path", "gui.config.field.ffmpeg.executable-path.label", null,
                                    DesktopUiNode.InputKind.FILE, formValues.getOrDefault("tools.ffmpeg.path", ""),
                                    !owner.busy() && !ffmpegChecking.get()), null))));
            nextActions.put("status.ffmpeg.refresh", this::refreshFfmpeg);
            ffmpegNodes.add(new DesktopUiNode.Button("status.ffmpeg.refresh", "status.ffmpeg.refresh", toolToken("refresh"),
                    null, DesktopUiNode.ButtonStyle.NORMAL, !owner.busy() && !ffmpegChecking.get()));
            nextActions.put("status.ffmpeg.path.save", this::saveFfmpegPath);
            ffmpegNodes.add(new DesktopUiNode.Button("status.ffmpeg.path.save", "status.ffmpeg.path.save", toolToken("save-path"),
                    null, DesktopUiNode.ButtonStyle.NORMAL, !owner.busy() && !ffmpegChecking.get()));
            ffmpegNodes.add(row("status.ffmpeg.actions",
                    button("status.ffmpeg.install", "status.ffmpeg.install", "gui.ffmpeg.action.download-to-managed",
                            !owner.busy() && !ffmpegChecking.get() && host.supportsManagedFfmpegInstall(), nextActions, this::requestFfmpegInstall),
                    button("status.ffmpeg.open", "status.ffmpeg.open", "gui.ffmpeg.action.open-dir",
                            !owner.busy(), nextActions, this::openFfmpegDirectory)));
        }
        if (ffmpegInstalling) ffmpegNodes.add(new DesktopUiNode.Progress(
                "status.ffmpeg.progress",
                ffmpegProgress,
                ffmpegProgress <= 0d,
                owner.statusNotice.isBlank() ? null : TextToken.raw(owner.statusNotice)
        ));
        return group(
                "status.ffmpeg",
                "gui.ffmpeg.panel.title",
                column("status.ffmpeg.content", ffmpegNodes)
        );
    }

    private static TextToken toolToken(String key) {
        return new TextToken("gui-compose", "gui.compose.tools.workspace." + key, "", List.of());
    }

    private void refreshFfmpeg() {
        if (!ffmpegChecking.compareAndSet(false, true)) return;
        owner.executeAsync(() -> {
            try {
                inspectFfmpeg();
            } finally {
                ffmpegChecking.set(false);
                owner.rebuild();
            }
        });
        owner.rebuild();
    }

    private void inspectFfmpeg() {
        try {
            ffmpegInstallation = host.locateFfmpeg();
            ffmpegCheckFailed = false;
            String configured = host.applicationConfig().read("ffmpeg.executable-path");
            String path = configured == null ? "" : configured;
            formValues.compute("tools.ffmpeg.path", (key, draft) -> draft == null || draft.equals(ffmpegConfiguredPath) ? path : draft);
            ffmpegConfiguredPath = path;
        } catch (Exception failure) {
            ffmpegInstallation = Optional.empty();
            ffmpegCheckFailed = true;
            LOG.warn("Unable to inspect FFmpeg installation", failure);
        } finally {
            ffmpegChecked = true;
        }
    }

    private void saveFfmpegPath() {
        if (owner.busy() || ffmpegChecking.get()) return;
        String value = formValues.getOrDefault("tools.ffmpeg.path", "").trim();
        owner.runBusy(() -> {
            try {
                host.requireSafeConfigValue(value);
                host.validateCoreConfigValue("ffmpeg.executable-path", value);
                host.applicationConfig().writeAll(Map.of("ffmpeg.executable-path", value));
                owner.coreConfigValueSaved("ffmpeg.executable-path", value);
                ffmpegNotice = toolToken("path-saved");
                ffmpegNoticeError = false;
                refreshFfmpeg();
            } catch (Exception failure) {
                LOG.warn("Unable to save FFmpeg path", failure);
                ffmpegNotice = toolToken("path-failed");
                ffmpegNoticeError = true;
            }
        });
    }

    private void requestFfmpegInstall() {
        if (owner.busy() || ffmpegChecking.get()) return;
        if (!host.supportsManagedFfmpegInstall()) ffmpegNotice = TextToken.key("gui.ffmpeg.dialog.unsupported.message");
        else ffmpegConfirm = true;
        owner.rebuild();
    }

    private void installFfmpeg() {
        if (owner.busy()) return;
        ffmpegInstalling = true;
        ffmpegProgress = 0d;
        owner.runBusy(() -> {
            try {
                DesktopUiHost.FfmpegProxy proxy = owner.proxySettings();
                DesktopUiHost.FfmpegInstallation installed = host.installManagedFfmpeg(
                        proxy,
                        (stage, current, total) -> {
                            owner.statusNotice = host.message("gui.ffmpeg.install.stage." + stage.name().toLowerCase(
                                    Locale.ROOT));
                            ffmpegProgress = total > 0 ? Math.min(
                                    1d,
                                    (double) current / total
                            ) : 0d;
                            owner.rebuild();
                        }
                );
                owner.statusNotice = "";
                ffmpegInstallation = Optional.of(installed);
                ffmpegChecked = true;
                ffmpegCheckFailed = false;
                ffmpegNotice = toolToken("installed");
                ffmpegNoticeError = false;
                inspectFfmpeg();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.error("Managed FFmpeg installation was interrupted", interrupted);
                ffmpegNotice = TextToken.key("desktop.ui.ffmpeg.install-failed");
                ffmpegNoticeError = true;
            } catch (Exception failure) {
                LOG.error("Managed FFmpeg installation failed", failure);
                ffmpegNotice = TextToken.key("desktop.ui.ffmpeg.install-failed");
                ffmpegNoticeError = true;
            } finally {
                ffmpegInstalling = false;
            }
        });
    }

    void openDownloadDirectory() {
        // 打开目录是瞬时动作：走异步执行但不进入忙锁，避免一次等待把整个界面长期置忙。
        owner.executeAsync(() -> {
            try {
                Path directory = Path.of(rootFolder).toAbsolutePath().normalize();
                if (!Files.isDirectory(directory)) {
                    owner.showDialog(
                            "download-folder.missing",
                            "gui.dialog.info.title",
                            appToken("gui.status.dialog.download-folder-missing", directory),
                            DesktopUiDocument.DialogStyle.WARNING
                    );
                    return;
                }
                host.openLocalPath(directory);
            } catch (Exception failure) {
                LOG.warn("Unable to open the download directory", failure);
                owner.showDialog(
                        "download-folder.failed",
                        "gui.dialog.error.title",
                        "desktop.ui.action.failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            }
        });
    }

    private void requestBackendRestart() {
        owner.showDialog(
                "backend.restart",
                "gui.action.restart-service",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "backend.restart.content",
                        text(
                                "backend.restart.message",
                                "gui.status.dialog.restart.confirm.message",
                                TextStyle.BODY
                        ),
                        row(
                                "backend.restart.actions",
                                button(
                                        "backend.restart.confirm",
                                        "backend.restart.confirm",
                                        "gui.action.restart-service",
                                        true,
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            owner.runBusy(() -> owner.statusNotice = host.restartBackend(
                                                    this::refresh) ? host.message(
                                                    "gui.status.state.restarting") : host.message(
                                                    "gui.message.backend-busy"));
                                        }
                                ),
                                button(
                                        "backend.restart.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                500,
                0
        );
    }

    private void openFfmpegDirectory() {
        // 与下载目录一致：异步执行、不占忙锁；重解析点由宿主在打开前解析为真实目标。
        if (!ffmpegDirectoryOpening.compareAndSet(false, true)) return;
        try {
            owner.executeAsync(() -> {
                try {
                    Path directory = host.locateFfmpeg().map(DesktopUiHost.FfmpegInstallation::homeDir).filter(
                            Objects::nonNull).filter(Files::isDirectory).orElse(null);
                    host.openLocalPath(directory == null ? host.prepareManagedFfmpegDirectory() : directory);
                } catch (Exception failure) {
                    owner.statusNotice = host.message(
                            "gui.ffmpeg.dialog.open-dir-failed.message",
                            safeMessage(failure)
                    );
                } finally {
                    ffmpegDirectoryOpening.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            ffmpegDirectoryOpening.set(false);
            throw rejected;
        }
    }

    void restartApplication() {
        owner.runBusy(() -> owner.statusNotice = host.restartApplication() ? host.message(
                "gui.config.notice.process-restarting") : host.message("desktop.ui.action.failed"));
    }

}
