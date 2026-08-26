package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 应用更新检查、提示、下载与安装流程。
 */
final class DesktopUpdateController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopUpdateController.class);

    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> formValues;

    private volatile PendingInstall pendingOfficialUpdate;
    private volatile PendingInstall pendingNightlyUpdate;
    private volatile boolean updateInstalling;
    private volatile boolean downloadingNightly;
    private volatile long updateReceivedBytes;
    private volatile long updateTotalBytes;

    DesktopUpdateController(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.formValues = formValues;
    }

    List<DesktopUiNode> banners(String prefix, Map<String, Runnable> nextActions) {
        List<DesktopUiNode> banners = new ArrayList<>(2);
        if (pendingOfficialUpdate != null) {
            banners.add(updateBanner(
                    prefix,
                    "official",
                    pendingOfficialUpdate,
                    false,
                    nextActions
            ));
        }
        if (pendingNightlyUpdate != null) {
            banners.add(updateBanner(
                    prefix,
                    "nightly",
                    pendingNightlyUpdate,
                    true,
                    nextActions
            ));
        }
        return List.copyOf(banners);
    }

    private DesktopUiNode updateBanner(
            String id,
            PendingInstall update,
            boolean nightly,
            Map<String, Runnable> nextActions
    ) {
        return updateBanner(
                "status.update",
                id,
                update,
                nightly,
                nextActions
        );
    }

    private DesktopUiNode updateBanner(
            String prefix,
            String id,
            PendingInstall update,
            boolean nightly,
            Map<String, Runnable> nextActions
    ) {
        String base = prefix + "." + id;
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(raw(
                base + ".text",
                host.message(
                        nightly ? "gui.update.banner.nightly-text" : "gui.update.banner.text",
                        host.applicationVersion(),
                        update.latestVersion()
                ),
                TextStyle.HEADING
        ));
        if (updateInstalling && downloadingNightly == nightly) {
            double progress = updateTotalBytes > 0 ? Math.min(
                    1d,
                    (double) updateReceivedBytes / updateTotalBytes
            ) : 0d;
            TextToken label = updateTotalBytes > 0 ? appToken(
                    "gui.update.banner.progress.label",
                    formatSize(updateReceivedBytes),
                    formatSize(updateTotalBytes),
                    Math.round(progress * 100d)
            ) : TextToken.raw(formatSize(updateReceivedBytes));
            content.add(new DesktopUiNode.Progress(
                    base + ".progress",
                    progress,
                    updateTotalBytes <= 0,
                    label
            ));
        }
        content.add(row(
                base + ".actions",
                button(
                        base + ".notes",
                        base + ".notes",
                        nightly ? "gui.update.banner.view-diff" : "gui.update.banner.view-log",
                        !updateInstalling,
                        nextActions,
                        () -> showUpdateNotes(update, nightly)
                ),
                button(
                        base + ".install",
                        base + ".install",
                        nightly ? "gui.update.banner.install.nightly" : "gui.update.banner.install",
                        !owner.busy() && !updateInstalling,
                        nextActions,
                        () -> requestUpdateInstall(update, nightly)
                ),
                button(
                        base + ".dismiss",
                        base + ".dismiss",
                        "gui.update.banner.dismiss",
                        !updateInstalling,
                        nextActions,
                        () -> dismissUpdate(nightly)
                )
        ));
        return new DesktopUiNode.Surface(
                base,
                nightly ? DesktopUiNode.SurfaceStyle.INFO : DesktopUiNode.SurfaceStyle.SUCCESS,
                new DesktopUiNode.Insets(
                        8,
                        12,
                        8,
                        12
                ),
                true,
                column(base + ".content", content)
        );
    }

    void checkUpdates() {
        owner.runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiGet(
                    "update/check?force=true",
                    30_000
            );
            if (!response.is2xx() || response.body() == null) {
                LOG.warn(
                        "Desktop update check failed: reachable={}, status={}",
                        response.reachable(),
                        response.status()
                );
                owner.showDialog(
                        "update.check-failed",
                        "gui.dialog.error.title",
                        "gui.update.dialog.check-failed.message",
                        DesktopUiDocument.DialogStyle.WARNING
                );
                return;
            }
            DesktopUiHost.GuiValue result = response.body();
            applyUpdateResult(result);
            if (!result.path("enabled").asBoolean(false)) {
                owner.showDialog(
                        "update.disabled",
                        "gui.dialog.info.title",
                        "gui.update.dialog.disabled.message",
                        DesktopUiDocument.DialogStyle.INFO
                );
            } else if (!result.path("checkSucceeded").asBoolean(false)) {
                LOG.warn(
                        "Desktop update check rejected: {}",
                        result.path("error").asText("unknown")
                );
                owner.showDialog(
                        "update.check-failed",
                        "gui.dialog.error.title",
                        "gui.update.dialog.check-failed.message",
                        DesktopUiDocument.DialogStyle.WARNING
                );
            } else if (pendingOfficialUpdate == null && pendingNightlyUpdate == null) {
                owner.showDialog(
                        "update.up-to-date",
                        "gui.dialog.info.title",
                        appToken(
                                "gui.update.dialog.up-to-date.message",
                                result.path("currentVersion").asText("--")
                        ),
                        DesktopUiDocument.DialogStyle.INFO
                );
            }
        });
    }

    void scheduleInitialUpdateLookup() {
        owner.executeAsync(() -> {
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                for (int attempt = 0; attempt < 24; attempt++) {
                    DesktopUiHost.GuiResponse response = host.guiGet("update/last", 5_000);
                    if (response.is2xx() && response.body() != null) {
                        applyUpdateResult(response.body());
                        owner.rebuild();
                        return;
                    }
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2_500L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void applyUpdateResult(DesktopUiHost.GuiValue result) {
        pendingOfficialUpdate = result.path("updateAvailable").asBoolean(false) ? pendingInstall(
                result) : null;
        DesktopUiHost.GuiValue nightly = result.path("nightlyAlternative");
        pendingNightlyUpdate = nightly.path("updateAvailable").asBoolean(false) ? pendingInstall(
                nightly) : null;
    }

    private static PendingInstall pendingInstall(DesktopUiHost.GuiValue value) {
        return new PendingInstall(
                value.path("assetUrl").asText(""),
                value.path("assetSizeBytes").asLong(0L),
                value.path("releaseNotes").asText(""),
                value.path("releaseNotesUrl").asText(""),
                value.path("latestVersion").asText("")
        );
    }

    private void dismissUpdate(boolean nightly) {
        if (nightly) pendingNightlyUpdate = null;
        else pendingOfficialUpdate = null;
        owner.rebuild();
    }

    private void showUpdateNotes(PendingInstall update, boolean nightly) {
        String notes = update.releaseNotes().isBlank() ? host.message(nightly ? "gui.update.dialog.view-diff.empty" : "gui.update.dialog.view-log.empty") : update.releaseNotes();
        owner.showDialog(
                "update.notes",
                nightly ? "gui.update.dialog.view-diff.title" : "gui.update.dialog.view-log.title",
                DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> new DesktopUiNode.Dock(
                        "update.notes.layout",
                        12,
                        null,
                        scroll(
                                "update.notes.scroll",
                                new DesktopUiNode.Text(
                                        "update.notes.text",
                                        TextToken.raw(notes),
                                        TextStyle.BODY,
                                        true,
                                        true
                                )
                        ),
                        row(
                                "update.notes.actions",
                                button(
                                        "update.notes.close",
                                        dismissAction,
                                        "desktop.ui.action.close",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        ),
                        null,
                        null
                ),
                640,
                420
        );
    }

    private void requestUpdateInstall(PendingInstall update, boolean nightly) {
        if (updateInstalling) return;
        if (!host.launchedFromExecutable()) {
            owner.showDialog(
                    "update.jar",
                    "gui.update.dialog.install.title",
                    DesktopUiDocument.DialogStyle.QUESTION,
                    (nextActions, dismissAction, dismiss) -> column(
                            "update.jar.content",
                            new DesktopUiNode.Text(
                                    "update.jar.message",
                                    appToken(
                                            "gui.update.dialog.jar-launch.message",
                                            update.latestVersion()
                                    ),
                                    TextStyle.BODY,
                                    true,
                                    true
                            ),
                            row(
                                    "update.jar.actions",
                                    button(
                                            "update.jar.open",
                                            "update.jar.open",
                                            "desktop.ui.action.open",
                                            true,
                                            nextActions,
                                            () -> {
                                                owner.closeDialog();
                                                owner.openUri(update.releaseNotesUrl().isBlank() ? host.releasesUrl() : update.releaseNotesUrl());
                                            }
                                    ),
                                    button(
                                            "update.jar.cancel",
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
            return;
        }
        if (nightly) {
            persistCheckNightly(true);
            startUpdateDownload(update, true);
            return;
        }
        formValues.put("update.keep-nightly", "true");
        owner.showDialog(
                "update.confirm",
                "gui.update.dialog.install.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> nodes = new ArrayList<>();
                    nodes.add(new DesktopUiNode.Text(
                            "update.confirm.message",
                            appToken(
                                    "gui.update.dialog.install.confirm.message",
                                    update.latestVersion(),
                                    formatSize(update.size())
                            ),
                            TextStyle.BODY,
                            true,
                            true
                    ));
                    if (host.currentVersionNightly()) {
                        nodes.add(toggle(
                                "update.confirm.keep-nightly",
                                "update.keep-nightly",
                                "gui.update.dialog.install.confirm.check-nightly-checkbox",
                                boolForm("update.keep-nightly", true),
                                true
                        ));
                    }
                    nodes.add(row(
                            "update.confirm.actions",
                            button(
                                    "update.confirm.install",
                                    "update.confirm.install",
                                    "gui.update.banner.install",
                                    true,
                                    nextActions,
                                    () -> {
                                        if (host.currentVersionNightly()) {
                                            persistCheckNightly(boolForm(
                                                    "update.keep-nightly",
                                                    true
                                            ));
                                        }
                                        owner.closeDialog();
                                        startUpdateDownload(update, false);
                                    }
                            ),
                            button(
                                    "update.confirm.cancel",
                                    dismissAction,
                                    "desktop.ui.action.cancel",
                                    true,
                                    nextActions,
                                    dismiss
                            )
                    ));
                    return column("update.confirm.content", nodes);
                },
                520,
                0
        );
    }

    private void startUpdateDownload(PendingInstall update, boolean nightly) {
        if (updateInstalling) return;
        owner.closeDialog();
        updateInstalling = true;
        downloadingNightly = nightly;
        updateReceivedBytes = 0L;
        updateTotalBytes = update.size();
        owner.rebuild();
        owner.executeAsync(() -> {
            try {
                DesktopUiHost.GuiResponse started = host.guiForm(
                        "POST",
                        "update/download?channel=" + (nightly ? "nightly" : "official"),
                        null,
                        10_000
                );
                if (!started.is2xx() || started.body() != null && started.body().hasNonNull("error")) {
                    throw new IllegalStateException("download start rejected: " + started.status());
                }
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.TimeUnit.SECONDS.sleep(1L);
                    DesktopUiHost.GuiResponse response = host.guiGet(
                            "update/download/progress",
                            5_000
                    );
                    if (!response.is2xx() || response.body() == null) continue;
                    DesktopUiHost.GuiValue progress = response.body();
                    updateReceivedBytes = progress.path("received").asLong(updateReceivedBytes);
                    updateTotalBytes = progress.path("total").asLong(updateTotalBytes);
                    owner.rebuild();
                    if (progress.path("failed").asBoolean(false) || progress.hasNonNull("error")) {
                        throw new IllegalStateException(progress.path("error").asText(
                                "download failed"));
                    }
                    if (progress.path("done").asBoolean(false)) break;
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                DesktopUiHost.GuiResponse install = host.guiForm(
                        "POST",
                        "update/install",
                        "",
                        10_000
                );
                if (!install.is2xx() || install.body() != null && install.body().hasNonNull("error")) {
                    throw new IllegalStateException("installer launch rejected: " + install.status());
                }
                updateInstalling = false;
                owner.showDialog(
                        "update.launched",
                        "gui.dialog.info.title",
                        "gui.update.dialog.installer-launched.message",
                        DesktopUiDocument.DialogStyle.SUCCESS
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                updateInstalling = false;
            } catch (Exception failure) {
                LOG.error("Desktop update install failed", failure);
                updateInstalling = false;
                owner.showDialog(
                        "update.failed",
                        "gui.dialog.error.title",
                        "desktop.ui.update.download-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } finally {
                owner.rebuild();
            }
        });
    }

    private void persistCheckNightly(boolean enabled) {
        try {
            host.applicationConfig().write(
                    "update.check-nightly",
                    Boolean.toString(enabled)
            );
        } catch (Exception failure) {
            LOG.warn("Unable to persist update.check-nightly", failure);
        }
    }

    private String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    private boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }

    private record PendingInstall(
            String assetUrl,
            long size,
            String releaseNotes,
            String releaseNotesUrl,
            String latestVersion
    ) {
    }
}
