package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.PixivBatchDownloaderImportStatistics;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.ImportDiagnostics;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import static org.slf4j.event.Level.*;

/** 浏览器只上传记录和单次元数据响应；文件根目录始终取本机已保存的配置。 */
public final class BrowserHistoryImport implements AutoCloseable {
    public static final int CHUNK_BYTES = 1024 * 1024;
    public static final int RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(15);
    private final WorkFileImporter importer;
    private final WorkQueryService works;
    private final PixivBatchDownloaderImportStatistics statistics;
    private final PluginRuntimeTaskRegistrar tasks;
    private final Path cache;
    private final ImportDiagnostics diagnostics;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "pixiv-browser-history");
        thread.setDaemon(true);
        return thread;
    });
    private Session session;
    private boolean closed;

    /** 只包含浏览器可读取的状态，不返回本机路径或来源站点凭证。 */
    public record View(String session, String phase, Preview preview, Status status,
                       MetadataRequest metadata, DesktopUiText error, List<HistoryImportService.WorkResult> results) {}
    public record MetadataRequest(String id, String kind, String workId) {}

    private final class Session {
        final String id = UUID.randomUUID().toString();
        final Path upload;
        final Path root;
        final long[] received = new long[HistoryRecords.MAX_FILES];
        long receivedBytes;
        final HistoryImportService service;
        long touched = System.nanoTime();
        boolean inspecting, cancelled;
        Thread inspector;
        Preview preview;
        DesktopUiText error;
        MetadataRequest metadata;
        CompletableFuture<String> response;

        Session(Path upload, Path root) {
            this.upload = upload; this.root = root;
            service = new HistoryImportService(importer, works, (uri, ignored) -> metadata(this, uri),
                    tasks, statistics, cache.resolve("plans"), diagnostics);
        }
        boolean active() { return inspecting || service.status().active(); }
    }

    public BrowserHistoryImport(WorkFileImporter importer, WorkQueryService works,
                                PixivBatchDownloaderImportStatistics statistics,
                                PluginRuntimeTaskRegistrar tasks, Path cache, ImportDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        this.importer = importer; this.works = works; this.statistics = statistics;
        this.tasks = tasks; this.cache = cache.toAbsolutePath().normalize();
        executor.setRemoveOnCancelPolicy(true);
        var task = tasks.registerPeriodic(this::expire);
        try { task.bindCancellation(executor.scheduleWithFixedDelay(task, 1, 1, TimeUnit.MINUTES)); }
        catch (RuntimeException failure) { task.discardUnsubmitted(); executor.shutdownNow(); throw failure; }
    }

    public synchronized View create(String configuredRoot) throws IOException {
        if (closed || session != null && (session.active() || !terminal(session))) reject("busy");
        if (configuredRoot == null || configuredRoot.isBlank()) reject("root");
        Path root = Path.of(configuredRoot).normalize();
        HistoryRecords.plain(root, true);
        if (session != null) dispose(session);
        Files.createDirectories(cache);
        HistoryRecords.plain(cache, true);
        Path upload = Files.createTempDirectory(cache, "upload-");
        session = new Session(upload, root);
        diagnostics.event(INFO, "log.browser.create", session.service.diagnosticId());
        return view(session);
    }

    public synchronized View upload(String id, int file, long offset, byte[] bytes) throws IOException {
        Session value = require(id);
        if (!"UPLOADING".equals(view(value).phase()) || file < 0 || file >= value.received.length
                || offset != value.received[file] || bytes.length < 1 || bytes.length > CHUNK_BYTES) reject("input");
        if (bytes.length > HistoryRecords.MAX_BYTES - offset
                || bytes.length > HistoryRecords.MAX_TOTAL_BYTES - value.receivedBytes) reject("limit");
        Path target = value.upload.resolve(file + ".json");
        HistoryRecords.plain(value.upload, true);
        if (offset == 0) Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        else {
            HistoryRecords.plain(target, false);
            Files.write(target, bytes, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        }
        value.received[file] += bytes.length;
        value.receivedBytes += bytes.length;
        return view(value);
    }

    public synchronized View inspect(String id) {
        Session value = require(id);
        if (!"UPLOADING".equals(view(value).phase()) || value.receivedBytes == 0) reject("input");
        value.inspecting = true;
        diagnostics.event(INFO, "log.browser.uploaded", value.service.diagnosticId(), value.receivedBytes);
        top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask task;
        try { task = tasks.registerOneShot(() -> inspect(value)); }
        catch (RuntimeException failure) { value.inspecting = false; value.error = text("unavailable"); throw failure; }
        try { task.bindCancellation(executor.submit(task)); }
        catch (RuntimeException failure) {
            task.discardUnsubmitted(); value.inspecting = false; value.error = text("unavailable");
            throw failure;
        }
        return view(value);
    }

    private void inspect(Session value) {
        try {
            synchronized (this) {
                if (closed || value.cancelled) return;
                value.inspector = Thread.currentThread();
            }
            var result = value.service.inspect(new Request(value.upload.toString(), value.root.toString()));
            synchronized (this) { value.preview = result.value(); value.error = result.error(); }
        } finally {
            synchronized (this) {
                value.inspecting = false;
                value.inspector = null;
                cleanupUpload(value);
                if (closed || value.cancelled) value.service.close();
            }
        }
    }

    public synchronized View start(String id, String configuredRoot) {
        Session value = require(id);
        if (!"READY".equals(view(value).phase())) reject("stale");
        if (configuredRoot == null || configuredRoot.isBlank()) reject("root");
        if (!value.root.toString().equals(Path.of(configuredRoot).normalize().toString())) reject("root-changed");
        var result = value.service.start(value.preview.token(), "");
        if (result.error() != null) value.error = result.error();
        return view(value);
    }

    public synchronized View status(String id) { return view(require(id)); }

    public synchronized View reply(String id, String requestId, int status, byte[] body) {
        Session value = require(id);
        if (value.cancelled || value.response == null || !value.metadata.id().equals(requestId)
                || body.length > RESPONSE_BYTES || status != 0 && (status < 100 || status > 599)) reject("stale");
        CompletableFuture<String> response = value.response;
        value.response = null; value.metadata = null;
        if (status == 200) response.complete(new String(body, StandardCharsets.UTF_8));
        else response.completeExceptionally(new PixivAjaxException(
                status == 0 ? PixivAjaxFailure.TRANSPORT : PixivAjaxFailure.HTTP_STATUS, status));
        return view(value);
    }

    private String metadata(Session value, URI uri) {
        CompletableFuture<String> response = new CompletableFuture<>();
        synchronized (this) {
            if (closed || value != session || value.cancelled) throw transport();
            String[] parts = uri.getPath().split("/");
            if (parts.length != 4 || !(parts[2].equals("illust") || parts[2].equals("novel"))
                    || !parts[3].matches("[1-9][0-9]{0,15}")) throw transport();
            value.metadata = new MetadataRequest(UUID.randomUUID().toString(), parts[2], parts[3]);
            value.response = response;
        }
        try { return response.get(90, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw transport(); }
        catch (TimeoutException timeout) {
            diagnostics.failure(WARN, "browser/metadata-wait", value.service.diagnosticId(), "-",
                    "METADATA_REPLY_TIMEOUT", timeout);
            throw transport();
        }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof PixivAjaxException pixiv) throw pixiv;
            throw transport();
        } finally {
            synchronized (this) {
                if (value.response == response) { value.response = null; value.metadata = null; }
            }
        }
    }

    public synchronized View cancel(String id) {
        Session value = require(id);
        cancel(value);
        return view(value);
    }

    private void cancel(Session value) {
        if (!value.cancelled && !terminal(value))
            diagnostics.event(INFO, "log.browser.cancel", value.service.diagnosticId(), view(value).phase());
        value.cancelled = true;
        if (value.inspector != null) value.inspector.interrupt();
        value.service.cancel();
        if (value.response != null) {
            value.response.completeExceptionally(transport());
            value.response = null; value.metadata = null;
        }
        if (!value.active()) dispose(value);
    }

    private Session require(String id) {
        if (closed || session == null || !session.id.equals(id)) reject("stale");
        session.touched = System.nanoTime();
        return session;
    }

    private View view(Session value) {
        var progress = value.service.progress();
        Status status = progress.status();
        String phase = value.cancelled ? (value.active() ? "CANCELLING" : "CANCELLED")
                : value.inspecting ? "INSPECTING" : value.error != null ? "FAILED"
                : status.phase() == Phase.IDLE ? "UPLOADING" : status.phase().name();
        return new View(value.id, phase, value.preview, status, value.metadata, value.error, progress.results());
    }

    private boolean terminal(Session value) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED").contains(view(value).phase());
    }

    private synchronized void expire() {
        if (session == null || System.nanoTime() - session.touched < IDLE_NANOS) return;
        if (!session.cancelled && !terminal(session))
            diagnostics.event(WARN, "log.browser.expired", session.service.diagnosticId(), view(session).phase());
        cancel(session);
        if (!session.active()) { dispose(session); session = null; }
    }

    private void cleanupUpload(Session value) {
        try {
            if (!Files.exists(value.upload, LinkOption.NOFOLLOW_LINKS)) return;
            HistoryRecords.plain(value.upload, true);
            try (var entries = Files.newDirectoryStream(value.upload)) {
                for (Path entry : entries) { HistoryRecords.plain(entry, false); Files.delete(entry); }
            }
            Files.delete(value.upload);
        } catch (IOException failure) {
            if (value.error == null || !value.error.key().equals("history.error.input"))
                diagnostics.failure(WARN, "upload-cleanup", value.service.diagnosticId(), "-", "history.error.input", failure);
            value.error = text("input");
        }
    }

    private void dispose(Session value) { value.service.close(); cleanupUpload(value); }

    @Override public synchronized void close() {
        closed = true;
        if (session != null) {
            cancel(session); session.service.close();
            if (!session.service.status().active()) cleanupUpload(session);
        }
        executor.shutdownNow();
    }

    private static PixivAjaxException transport() { return new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0); }
    private static DesktopUiText text(String code) {
        if (code.equals("limit")) return new DesktopUiText("pixiv-batch-downloader-import", "history.error.limit", "history.error.limit",
                List.of(Long.toString(HistoryRecords.MAX_BYTES / (1024 * 1024)),
                        Long.toString(HistoryRecords.MAX_TOTAL_BYTES / (1024 * 1024)), Integer.toString(HistoryRecords.MAX_FILES)));
        return DesktopUiText.plugin("pixiv-batch-downloader-import", "history.error." + code, "history.error." + code);
    }
    private static void reject(String code) { throw new OperationException(text(code)); }
}
