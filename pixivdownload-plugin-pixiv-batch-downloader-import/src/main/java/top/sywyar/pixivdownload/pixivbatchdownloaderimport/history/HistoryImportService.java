package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.PixivBatchDownloaderImportStatistics;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;

/** 单个有界预览和单次执行；原文件由宿主再次核验并登记只读引用。 */
public final class HistoryImportService implements Source, AutoCloseable {
    private static final String NAMESPACE = "pixiv-batch-downloader-import";
    private final Object lock = new Object();
    private final WorkFileImporter importer;
    private final WorkQueryService works;
    private final HistoryMetadata metadata;
    private final PluginRuntimeTaskRegistrar tasks;
    private final PixivBatchDownloaderImportStatistics statistics;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "pixiv-history-import");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Status status = status(Phase.IDLE, 0, 0, 0, 0, List.of());
    private final Path cache;
    private Plan plan;
    private Plan runningPlan;
    private Thread activeThread;
    private boolean cancelled;
    private boolean closed;

    private record Plan(String token, Path root, HistoryRecords records, int candidates) {}

    public HistoryImportService(WorkFileImporter importer, WorkQueryService works, PixivAjaxClient pixiv,
                                PluginRuntimeTaskRegistrar tasks, PixivBatchDownloaderImportStatistics statistics, Path cache) {
        this.cache = cache.toAbsolutePath().normalize();
        this.importer = importer;
        this.works = works;
        this.metadata = new HistoryMetadata(pixiv);
        this.tasks = tasks;
        this.statistics = statistics;
    }

    @Override public Description description() {
        return new Description(text("history.title"), text("history.help"));
    }

    @Override public Result<Preview> inspect(Request request) {
        synchronized (lock) {
            if (closed || status.active()) return failure("busy");
            try { clearPlan(); } catch (IOException failure) { return failure("input"); }
            cancelled = false;
            activeThread = Thread.currentThread();
            status = status(Phase.INSPECTING, 0, 0, 0, 0, List.of());
        }
        HistoryRecords parsed = null;
        try {
            Path root = Path.of(request.sourceRoot()).normalize();
            parsed = HistoryRecords.read(Path.of(request.recordFile()).normalize(), root, cache);
            HistoryRecords snapshot = parsed;
            List<Item> samples = new ArrayList<>();
            int[] counts = new int[4];
            try (var output = snapshot.candidateOutput()) {
                snapshot.forEach(work -> {
                    checkCancelled();
                    Disposition disposition;
                    counts[0]++;
                    if (works.hasWork(work.key.type(), work.key.id())) { disposition = Disposition.ALREADY_PRESENT; counts[2]++; }
                    else if (work.conflict) { disposition = Disposition.CONFLICT; counts[3]++; }
                    else if (work.missing) { disposition = Disposition.MISSING_FILES; counts[3]++; }
                    else {
                        disposition = Disposition.CANDIDATE;
                        snapshot.writeCandidate(output, work);
                        counts[1]++;
                    }
                    if (samples.size() < 100) samples.add(new Item(work.key.display(), work.files.size(), disposition));
                });
            }
            synchronized (lock) {
                checkCancelled();
                String token = UUID.randomUUID().toString();
                plan = new Plan(token, root, parsed, counts[1]);
                parsed = null;
                status = status(Phase.READY, counts[1], 0, 0, 0, List.of());
                return Result.success(new Preview(token, snapshot.records(), counts[0], counts[1], counts[2], counts[3], samples));
            }
        } catch (HistoryRecords.Invalid invalid) {
            return inspectionFailed(invalid.code);
        } catch (InterruptedIOException interrupted) {
            return inspectionFailed("cancelled");
        } catch (IOException | IllegalArgumentException invalid) {
            return inspectionFailed("input");
        } catch (RuntimeException unavailable) {
            return inspectionFailed("unavailable");
        } catch (Error fatal) {
            inspectionFailed("unavailable");
            throw fatal;
        } finally {
            if (parsed != null) cleanup(parsed);
            synchronized (lock) { if (activeThread == Thread.currentThread()) activeThread = null; }
            Thread.interrupted();
        }
    }

    private Result<Preview> inspectionFailed(String code) {
        synchronized (lock) {
            status = status(cancelled ? Phase.CANCELLED : Phase.FAILED, 0, 0, 0, 0, List.of());
            return failure(code);
        }
    }

    @Override public Result<Status> start(String token, String credential) {
        synchronized (lock) {
            if (closed || status.active()) return failure("busy");
            if (plan == null || !plan.token().equals(token)) return failure("stale");
            if (credential != null && (credential.length() > 16_384 || credential.chars().anyMatch(Character::isISOControl)))
                return failure("credential");
            Plan selected = plan;
            plan = null;
            runningPlan = selected;
            cancelled = false;
            status = status(Phase.RUNNING, selected.candidates(), 0, 0, 0, List.of());
            top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask task;
            try { task = tasks.registerOneShot(() -> execute(selected, credential)); }
            catch (RuntimeException unavailable) {
                cleanup(selected.records());
                runningPlan = null;
                status = status(Phase.FAILED, selected.candidates(), 0, 0, 0, List.of());
                return failure("unavailable");
            }
            try {
                Future<?> future = executor.submit(task);
                task.bindCancellation(future);
                return Result.success(status);
            } catch (RejectedExecutionException rejected) {
                task.discardUnsubmitted();
                cleanup(selected.records());
                runningPlan = null;
                status = status(Phase.FAILED, selected.candidates(), 0, 0, 0, List.of());
                return failure("busy");
            }
        }
    }

    private void execute(Plan selected, String credential) {
        int imported = 0, skipped = 0, failed = 0;
        List<Problem> problems = new ArrayList<>();
        Phase terminal = Phase.COMPLETED;
        synchronized (lock) { activeThread = Thread.currentThread(); }
        try (var input = HistoryRecords.input(selected.records().candidates())) {
            boolean queried = false;
            HistoryRecords.Work work;
            while ((work = selected.records().readCandidate(input)) != null) {
                checkCancelled();
                if (imported + skipped + failed >= selected.candidates()) throw new HistoryRecords.Invalid("format");
                boolean committed = false;
                try {
                    statistics.flushPending();
                    if (works.hasWork(work.key.type(), work.key.id())) {
                        skipped++;
                    } else {
                        if (queried) Thread.sleep(1000);
                        checkCancelled();
                        queried = true;
                        var request = metadata.fetch(work, selected.root(), credential);
                        checkCancelled();
                        committed = importer.importFiles(request);
                        if (committed) {
                            imported++;
                            statistics.recordImported(request);
                        } else skipped++;
                    }
                } catch (InterruptedException | InterruptedIOException interrupted) {
                    terminal = Phase.CANCELLED;
                    break;
                } catch (SQLException unavailable) {
                    if (!committed) failed++;
                    problem(problems, work, "statistics");
                    terminal = Phase.FAILED;
                    break;
                } catch (HistoryRecords.Invalid invalid) {
                    failed++;
                    problem(problems, work, invalid.code);
                } catch (IOException unavailable) {
                    checkCancelled();
                    failed++;
                    problem(problems, work, "file");
                } catch (PixivAjaxException unavailable) {
                    checkCancelled();
                    failed++;
                    problem(problems, work, "metadata");
                    if (unavailable.failure() == PixivAjaxFailure.TRANSPORT
                            || unavailable.statusCode() == 401 || unavailable.statusCode() == 403
                            || unavailable.statusCode() == 429 || unavailable.statusCode() >= 500) {
                        terminal = Phase.FAILED;
                        break;
                    }
                } catch (RuntimeException failure) {
                    checkCancelled();
                    if (committed) {
                        problem(problems, work, "statistics");
                        terminal = Phase.FAILED;
                        break;
                    }
                    failed++;
                    problem(problems, work, "metadata");
                } finally {
                    synchronized (lock) {
                        status = status(cancelled ? Phase.CANCELLING : Phase.RUNNING, selected.candidates(),
                                imported, skipped, failed, problems);
                    }
                }
            }
            if (terminal == Phase.COMPLETED && imported + skipped + failed != selected.candidates())
                throw new HistoryRecords.Invalid("format");
        } catch (InterruptedIOException interrupted) {
            terminal = Phase.CANCELLED;
        } catch (IOException failure) {
            if (problems.size() < 100) problems.add(new Problem("", text("history.error.input")));
            terminal = Phase.FAILED;
        } catch (Error failure) {
            terminal = Phase.FAILED;
            throw failure;
        } finally {
            if (!cleanup(selected.records())) {
                if (problems.size() < 100) problems.add(new Problem("", text("history.error.input")));
                terminal = Phase.FAILED;
            }
            synchronized (lock) {
                status = status(cancelled || Thread.currentThread().isInterrupted() ? Phase.CANCELLED : terminal,
                        selected.candidates(), imported, skipped, failed, problems);
                activeThread = null;
                runningPlan = null;
            }
            Thread.interrupted();
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        synchronized (lock) {
            if (cancelled || closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
        }
    }

    @Override public Status status() { return status; }
    @Override public void cancel() {
        synchronized (lock) {
            if (!status.active()) return;
            cancelled = true;
            status = new Status(Phase.CANCELLING, status.total(), status.processed(), status.imported(),
                    status.skipped(), status.failed(), status.problems());
            if (activeThread != null) activeThread.interrupt();
        }
    }
    @Override public void close() {
        synchronized (lock) {
            closed = true;
            cancel();
            if (plan != null) { cleanup(plan.records()); plan = null; }
            if (runningPlan != null && activeThread == null) { cleanup(runningPlan.records()); runningPlan = null; }
        }
        executor.shutdownNow();
    }
    private void clearPlan() throws IOException {
        if (plan != null) { plan.records().close(); plan = null; }
    }
    private boolean cleanup(HistoryRecords records) {
        try { records.close(); return true; }
        catch (IOException failure) { return false; }
    }
    private static Status status(Phase phase, int total, int imported, int skipped, int failed, List<Problem> problems) {
        return new Status(phase, total, imported + skipped + failed, imported, skipped, failed, problems);
    }
    private static void problem(List<Problem> problems, HistoryRecords.Work work, String code) {
        if (problems.size() < 100) problems.add(new Problem(work.key.display(), text("history.error." + code)));
    }
    private static <T> Result<T> failure(String code) {
        if (code.equals("limit")) return Result.failure(new DesktopUiText(NAMESPACE, "history.error.limit", "history.error.limit",
                List.of(Long.toString(HistoryRecords.MAX_BYTES / (1024 * 1024)),
                        Long.toString(HistoryRecords.MAX_TOTAL_BYTES / (1024 * 1024)), Integer.toString(HistoryRecords.MAX_FILES))));
        return Result.failure(text("history.error." + code));
    }
    private static DesktopUiText text(String key) { return DesktopUiText.plugin(NAMESPACE, key, key); }
}
