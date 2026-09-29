package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.util.Arrays;
import java.util.concurrent.Callable;

/** 插件实现媒体业务，GUI 只消费稳定的桌面命令与纯值结果。 */
public final class DesktopMediaMaintenance implements DesktopMediaTool.Source {
    private final MediaMaintenanceService maintenance;
    private final MediaCapabilityService capabilities;

    public DesktopMediaMaintenance(MediaMaintenanceService maintenance, MediaCapabilityService capabilities) {
        this.maintenance = maintenance;
        this.capabilities = capabilities;
    }

    @Override public DesktopMediaTool.Description description() {
        return new DesktopMediaTool.Description(DesktopUiText.plugin("batch", "media.tools.title", "media.tools.title"),
                "batch", MediaOutputSettings.DEFAULT_IMAGE_FORMATS, MediaOutputSettings.DEFAULT_UGOIRA_FORMATS);
    }

    @Override public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) {
        return invoke(() -> {
            MediaMaintenanceService.Preview preview;
            try {
                preview = maintenance.preview(new MediaMaintenanceService.Request(request.imageFormats(),
                        request.ugoiraFormats(), request.repairThumbnails())).get();
            } catch (InterruptedException interrupted) {
                maintenance.cancel();
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
            return new DesktopMediaTool.Preview(preview.token(), preview.files().stream()
                    .map(file -> new DesktopMediaTool.Item(file.artworkId(), file.page(), file.fileName(),
                            file.missingFormats(), file.missingThumbnail())).toList(), preview.scanned(), preview.skipped(), preview.limited());
        });
    }

    @Override public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) { return invoke(() -> status(maintenance.start(token))); }
    @Override public DesktopMediaTool.Status status() { return status(maintenance.status()); }
    @Override public void cancel() { maintenance.cancel(); }
    @Override public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() {
        return invoke(() -> {
            var report = capabilities.check();
            return new DesktopMediaTool.Report(report.command(), report.source(), report.capabilities().stream()
                    .map(value -> new DesktopMediaTool.Capability(value.name(), value.available())).toList());
        });
    }

    private static DesktopMediaTool.Status status(MediaMaintenanceService.Status status) {
        return new DesktopMediaTool.Status(status.state(), status.total(), status.completed(), status.failed(),
                status.failures().stream().map(value -> new DesktopMediaTool.Failure(value.artworkId(), value.page())).toList());
    }

    private static <T> DesktopMediaTool.Result<T> invoke(Callable<T> action) {
        try { return new DesktopMediaTool.Result<>(action.call(), null); }
        catch (java.util.concurrent.CancellationException cancelled) {
            return new DesktopMediaTool.Result<>(null, DesktopUiText.plugin("batch", "media.tools.state.cancelled", "media.tools.state.cancelled"));
        }
        catch (LocalizedException failure) {
            return new DesktopMediaTool.Result<>(null, new DesktopUiText("batch", failure.messageCode().replace("download.media.", "media.error."),
                    failure.messageCode(), Arrays.stream(failure.messageArgs()).map(String::valueOf).toList()));
        } catch (Exception failure) {
            return new DesktopMediaTool.Result<>(null, DesktopUiText.key("desktop.ui.action.failed"));
        }
    }
}
