package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 运行状态、连通性、FFmpeg 与应用更新。
 */
final class DesktopStatusController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopStatusController.class);

    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final int serverPort;
    private final String rootFolder;
    private final AppDesktopUiModel.RendererContract rendererContract;
    final DesktopUpdateController updates;

    private volatile String statusPort = "--";
    private volatile String statusMode = "--";
    private volatile String statusStartTime = "--";
    private volatile String statusProtocol = "--";
    private volatile long statusLatencyMillis = -1L;
    private volatile DesktopUiHost.GuiValue controlCenterSnapshot = DesktopUiHost.GuiValue.of(Map.of());
    private volatile String connectivityDetails = "";
    private volatile boolean statusConnected;
    private volatile boolean connectivityChecking;
    private volatile long lastConnectivityCheckAt;
    private volatile boolean ffmpegInstalling;
    private volatile double ffmpegProgress;

    DesktopStatusController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            int serverPort,
            String rootFolder,
            AppDesktopUiModel.RendererContract rendererContract,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.rendererContract = rendererContract;
        this.updates = new DesktopUpdateController(owner, host, formValues);
    }

    DesktopUiHost.GuiValue controlCenterSnapshot() {
        return controlCenterSnapshot;
    }

    long latencyMillis() {
        return statusLatencyMillis;
    }

    boolean connected() {
        return statusConnected;
    }

    void resetConnection() {
        statusConnected = false;
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        Optional<DesktopUiHost.FfmpegInstallation> ffmpeg = host.locateFfmpeg();
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
                                                !owner.busy() && !connectivityChecking && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING,
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
        List<DesktopUiNode> ffmpegNodes = new ArrayList<>();
        ffmpegNodes.add(text(
                "status.ffmpeg.intro",
                "gui.ffmpeg.panel.intro",
                TextStyle.BODY
        ));
        ffmpegNodes.add(status(
                "status.ffmpeg.state",
                ffmpeg.isPresent() ? host.message("gui.ffmpeg.badge.ready") : host.message(
                        "gui.ffmpeg.badge.missing")
        ));
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
        ffmpegNodes.add(row(
                "status.ffmpeg.actions",
                button(
                        "status.ffmpeg.install",
                        "status.ffmpeg.install",
                        "gui.ffmpeg.action.download-to-managed",
                        !owner.busy() && host.supportsManagedFfmpegInstall(),
                        nextActions,
                        this::requestFfmpegInstall
                ),
                button(
                        "status.ffmpeg.open",
                        "status.ffmpeg.open",
                        "gui.ffmpeg.action.open-dir",
                        !owner.busy(),
                        nextActions,
                        this::openFfmpegDirectory
                )
        ));
        if (ffmpegInstalling) ffmpegNodes.add(new DesktopUiNode.Progress(
                "status.ffmpeg.progress",
                ffmpegProgress,
                ffmpegProgress <= 0d,
                owner.statusNotice.isBlank() ? null : TextToken.raw(owner.statusNotice)
        ));
        children.add(group(
                "status.ffmpeg",
                "gui.ffmpeg.panel.title",
                column("status.ffmpeg.content", ffmpegNodes)
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
                    if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
                        owner.loadPluginStatus();
                    }
                    if (owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING && System.currentTimeMillis() - lastConnectivityCheckAt >= 60_000L) {
                        checkConnectivity();
                    }
                    owner.rebuild();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void refreshSnapshot() {
        long startedAt = System.nanoTime();
        DesktopUiHost.GuiResponse response = host.guiGet("status", 2_000);
        statusLatencyMillis = response.reachable() ? Math.max(
                0L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        ) : -1L;
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
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
            refreshControlCenterSnapshot();
        }
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

    private void checkConnectivity() {
        if (connectivityChecking || owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING)
            return;
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

    private void requestFfmpegInstall() {
        if (!host.supportsManagedFfmpegInstall()) {
            owner.showDialog(
                    "ffmpeg.unsupported",
                    "gui.dialog.info.title",
                    "gui.ffmpeg.dialog.unsupported.message",
                    DesktopUiDocument.DialogStyle.INFO
            );
            owner.rebuild();
            return;
        }
        owner.showDialog(
                "ffmpeg.confirm",
                "gui.ffmpeg.dialog.install.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "ffmpeg.confirm.content",
                        text(
                                "ffmpeg.confirm.message",
                                "gui.ffmpeg.dialog.install.confirm.message",
                                TextStyle.BODY
                        ),
                        row(
                                "ffmpeg.confirm.actions",
                                button(
                                        "ffmpeg.confirm.install",
                                        "ffmpeg.confirm.install",
                                        "gui.ffmpeg.action.download",
                                        true,
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            installFfmpeg();
                                        }
                                ),
                                button(
                                        "ffmpeg.confirm.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                520,
                0
        );
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
                owner.showDialog(
                        "ffmpeg.success",
                        "gui.ffmpeg.dialog.install-success.title",
                        appToken(
                                "gui.ffmpeg.dialog.install-success.message",
                                owner.localizedCode(
                                        "ffmpeg.source.",
                                        installed.source().name().toLowerCase(Locale.ROOT)
                                ),
                                installed.ffmpegPath()
                        ),
                        DesktopUiDocument.DialogStyle.SUCCESS
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.error("Managed FFmpeg installation was interrupted", interrupted);
                owner.showDialog(
                        "ffmpeg.failed",
                        "gui.ffmpeg.dialog.install-failed.title",
                        "desktop.ui.ffmpeg.install-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } catch (Exception failure) {
                LOG.error("Managed FFmpeg installation failed", failure);
                owner.showDialog(
                        "ffmpeg.failed",
                        "gui.ffmpeg.dialog.install-failed.title",
                        "desktop.ui.ffmpeg.install-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } finally {
                ffmpegInstalling = false;
            }
        });
    }

    void openDownloadDirectory() {
        owner.runBusy(() -> {
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
        owner.runBusy(() -> {
            try {
                Path directory = host.locateFfmpeg().map(DesktopUiHost.FfmpegInstallation::homeDir).filter(
                        Objects::nonNull).filter(Files::isDirectory).orElseGet(host::managedFfmpegDirectory);
                host.openLocalPath(Files.createDirectories(directory));
            } catch (Exception failure) {
                owner.statusNotice = host.message(
                        "gui.ffmpeg.dialog.open-dir-failed.message",
                        safeMessage(failure)
                );
            }
        });
    }

    void restartApplication() {
        owner.runBusy(() -> owner.statusNotice = host.restartApplication() ? host.message(
                "gui.config.notice.process-restarting") : host.message("desktop.ui.action.failed"));
    }

}
