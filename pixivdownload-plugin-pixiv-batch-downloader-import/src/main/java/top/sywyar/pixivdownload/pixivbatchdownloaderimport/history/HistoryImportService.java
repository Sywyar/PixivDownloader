package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.PixivBatchDownloaderImportStatistics;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.ImportDiagnostics;
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
import static org.slf4j.event.Level.*;

/** 单个有界预览和单次执行；原文件由宿主再次核验并登记只读引用。 */
public final class HistoryImportService implements Source, AutoCloseable {
    private static final String NAMESPACE = "pixiv-batch-downloader-import";
    private static final int DETAIL_LIMIT = 100;
    private final ImportDiagnostics diagnostics;
    private final String diagnosticId = UUID.randomUUID().toString();
    private final Object lock = new Object();
    private final WorkFileImporter importer;
    private final WorkQueryService works;
    private final HistoryImportCompletion completions;
    private final PluginRuntimeTaskRegistrar tasks;
    private final PixivBatchDownloaderImportStatistics statistics;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "pixiv-history-import");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Status status = status(Phase.IDLE, 0, 0, 0, 0, List.of());
    private final Map<String, WorkResult> results = new LinkedHashMap<>();
    public record WorkResult(String workKey, String state, DesktopUiText error) {}
    public record Progress(Status status, List<WorkResult> results) {}
    private final Path cache;
    private Plan plan;
    private Plan runningPlan;
    private Thread activeThread;
    private boolean cancelled;
    private boolean closed;

    private record Plan(String token, Path root, HistoryRecords records, int candidates) {}

    public HistoryImportService(WorkFileImporter importer, WorkQueryService works, HistoryImportCompletion completions,
                                PluginRuntimeTaskRegistrar tasks, PixivBatchDownloaderImportStatistics statistics, Path cache,
                                ImportDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        this.cache = cache.toAbsolutePath().normalize();
        this.importer = importer;
        this.works = works;
        this.completions = completions;
        this.tasks = tasks;
        this.statistics = statistics;
    }

    @Override public Description description() {
        return new Description(text("history.title"), text("history.help"), false);
    }
    @Override public Optional<Completion> pendingCompletion() { return completions.pending(); }
    @Override public void acknowledgeCompletion(String id) { completions.acknowledge(id); }

    public String diagnosticId() { return diagnosticId; }

    @Override public Result<Preview> inspect(Request request) {
        synchronized (lock) {
            if (closed || status.active()) return failure("busy");
            try { clearPlan(); } catch (IOException failure) { return failure("input", failure); }
            cancelled = false;
            results.clear();
            activeThread = Thread.currentThread();
            status = status(Phase.INSPECTING, 0, 0, 0, 0, List.of());
            diagnostics.event(INFO, "log.history.inspect", diagnosticId);
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
                    if (samples.size() < DETAIL_LIMIT) {
                        samples.add(new Item(work.key.display(), work.files.size(), disposition));
                        if (disposition == Disposition.CONFLICT || disposition == Disposition.MISSING_FILES)
                            diagnostics.failure(WARN, "inspect", diagnosticId, work.key.display(), disposition.name(), null);
                    }
                });
            }
            synchronized (lock) {
                checkCancelled();
                String token = UUID.randomUUID().toString();
                plan = new Plan(token, root, parsed, counts[1]);
                for (Item item : samples) {
                    if (item.disposition() == Disposition.CANDIDATE)
                        results.put(item.workKey(), new WorkResult(item.workKey(), "WAITING", null));
                }
                parsed = null;
                status = status(Phase.READY, counts[1], 0, 0, 0, List.of());
                diagnostics.event(counts[3] > 0 ? WARN : INFO, "log.history.preview", diagnosticId,
                        snapshot.records(), counts[0], counts[1], counts[2], counts[3], DETAIL_LIMIT);
                return Result.success(new Preview(token, snapshot.records(), counts[0], counts[1], counts[2], counts[3], samples));
            }
        } catch (HistoryRecords.Invalid invalid) {
            return inspectionFailed(invalid.code, invalid);
        } catch (InterruptedIOException interrupted) {
            return inspectionFailed("cancelled", null);
        } catch (IOException | IllegalArgumentException invalid) {
            return inspectionFailed("input", invalid);
        } catch (RuntimeException unavailable) {
            return inspectionFailed("unavailable", unavailable);
        } catch (Error fatal) {
            inspectionFailed("unavailable", fatal);
            throw fatal;
        } finally {
            if (parsed != null) cleanup(parsed);
            synchronized (lock) { if (activeThread == Thread.currentThread()) activeThread = null; }
            Thread.interrupted();
        }
    }

    private Result<Preview> inspectionFailed(String code, Throwable cause) {
        synchronized (lock) {
            status = status(cancelled ? Phase.CANCELLED : Phase.FAILED, 0, 0, 0, 0, List.of());
            return failure(code, cause);
        }
    }

    @Override public Result<Status> start(String token, String credential) {
        synchronized (lock) {
            if (closed || status.active()) return failure("busy");
            if (plan == null || !plan.token().equals(token)) return failure("stale");
            if (credential != null && !credential.isEmpty()) return failure("credential");
            Plan selected = plan;
            plan = null;
            runningPlan = selected;
            cancelled = false;
            status = status(Phase.RUNNING, selected.candidates(), 0, 0, 0, List.of());
            diagnostics.event(INFO, "log.history.start", diagnosticId, selected.candidates());
            top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask task;
            try { task = tasks.registerOneShot(() -> execute(selected)); }
            catch (RuntimeException unavailable) {
                cleanup(selected.records());
                runningPlan = null;
                status = status(Phase.FAILED, selected.candidates(), 0, 0, 0, List.of());
                return failure("unavailable", unavailable);
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
                return failure("busy", rejected);
            }
        }
    }

    private void execute(Plan selected) {
        int imported = 0, skipped = 0, failed = 0;
        List<Problem> problems = new ArrayList<>();
        Phase terminal = Phase.COMPLETED;
        long started = System.nanoTime();
        synchronized (lock) { activeThread = Thread.currentThread(); }
        try (var input = HistoryRecords.input(selected.records().candidates())) {
            HistoryRecords.Work work;
            while ((work = selected.records().readCandidate(input)) != null) {
                checkCancelled();
                if (imported + skipped + failed >= selected.candidates()) throw new HistoryRecords.Invalid("format");
                boolean committed = false;
                String stage = "statistics";
                String outcome = "WAITING";
                DesktopUiText error = null;
                synchronized (lock) { updateResult(work, "RUNNING", null); }
                try {
                    statistics.flushPending();
                    stage = "lookup";
                    if (works.hasWork(work.key.type(), work.key.id())) {
                        skipped++;
                        outcome = "SKIPPED";
                    } else {
                        checkCancelled();
                        var request = new LocalWorkFileImportRequest(work.key.type(), work.key.id(),
                                selected.root(), work.files.values().stream().map(selected.root()::resolve).toList());
                        stage = "register";
                        committed = importer.importLocalFiles(request);
                        outcome = committed ? "IMPORTED" : "SKIPPED";
                        if (committed) {
                            imported++;
                            stage = "statistics";
                            statistics.recordImported(request.workType(), request.workId());
                        } else skipped++;
                    }
                } catch (InterruptedIOException interrupted) {
                    terminal = Phase.CANCELLED;
                    break;
                } catch (SQLException unavailable) {
                    if (!committed) failed++;
                    outcome = committed ? "IMPORTED" : "FAILED";
                    error = text("history.error.statistics");
                    problem(problems, work, "statistics", stage, unavailable);
                    terminal = Phase.FAILED;
                    break;
                } catch (HistoryRecords.Invalid invalid) {
                    failed++;
                    outcome = "FAILED";
                    error = text("history.error." + invalid.code);
                    problem(problems, work, invalid.code, stage, invalid);
                } catch (IOException unavailable) {
                    checkCancelled();
                    failed++;
                    outcome = "FAILED";
                    error = text("history.error.file");
                    problem(problems, work, "file", stage, unavailable);
                } catch (RuntimeException failure) {
                    checkCancelled();
                    outcome = committed ? "IMPORTED" : "FAILED";
                    error = text(committed ? "history.error.statistics" : "history.error.file");
                    if (committed) {
                        problem(problems, work, "statistics", stage, failure);
                        terminal = Phase.FAILED;
                        break;
                    }
                    failed++;
                    problem(problems, work, "file", stage, failure);
                } finally {
                    synchronized (lock) {
                        updateResult(work, outcome, error);
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
            if (problems.size() < DETAIL_LIMIT) problems.add(new Problem("", text("history.error.input")));
            diagnostics.failure(WARN, "execute", diagnosticId, "-", "history.error.input", failure);
            terminal = Phase.FAILED;
        } catch (RuntimeException failure) {
            terminal = Phase.FAILED;
            diagnostics.failure(ERROR, "execute", diagnosticId, "-", "history.error.unavailable", failure);
            throw failure;
        } catch (Error failure) {
            terminal = Phase.FAILED;
            diagnostics.failure(ERROR, "execute", diagnosticId, "-", "history.error.unavailable", failure);
            throw failure;
        } finally {
            if (!cleanup(selected.records())) {
                if (problems.size() < DETAIL_LIMIT) problems.add(new Problem("", text("history.error.input")));
                terminal = Phase.FAILED;
            }
            synchronized (lock) {
                Status finished = status(cancelled || Thread.currentThread().isInterrupted() ? Phase.CANCELLED : terminal,
                        selected.candidates(), imported, skipped, failed, problems);
                diagnostics.event(finished.phase() == Phase.FAILED || failed > 0 ? WARN : INFO,
                        "log.history.finish", diagnosticId, finished.phase(), finished.processed(), finished.total(),
                        imported, skipped, failed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), DETAIL_LIMIT);
                activeThread = null;
                runningPlan = null;
                status = finished;
                completions.completed(imported);
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
    public Progress progress() {
        synchronized (lock) { return new Progress(status, List.copyOf(results.values())); }
    }
    private void updateResult(HistoryRecords.Work work, String state, DesktopUiText error) {
        String key = work.key.display();
        // 只更新预览已展示的候选，不随完整历史增长。
        if (results.containsKey(key)) results.put(key, new WorkResult(key, state, error));
    }
    @Override public void cancel() {
        synchronized (lock) {
            if (!status.active() || cancelled) return;
            diagnostics.event(INFO, "log.history.cancel", diagnosticId);
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
        catch (IOException failure) {
            diagnostics.failure(WARN, "cleanup", diagnosticId, "-", "history.error.input", failure);
            return false;
        }
    }
    private static Status status(Phase phase, int total, int imported, int skipped, int failed, List<Problem> problems) {
        return new Status(phase, total, imported + skipped + failed, imported, skipped, failed, problems);
    }
    private void problem(List<Problem> problems, HistoryRecords.Work work, String code, String stage, Throwable failure) {
        if (problems.size() < DETAIL_LIMIT) {
            problems.add(new Problem(work.key.display(), text("history.error." + code)));
            diagnostics.failure(failure instanceof RuntimeException ? ERROR : WARN,
                    stage, diagnosticId, work.key.display(), "history.error." + code, failure);
        }
    }
    private <T> Result<T> failure(String code) { return failure(code, null); }
    private <T> Result<T> failure(String code, Throwable cause) {
        diagnostics.failure(code.equals("cancelled") ? INFO : code.equals("unavailable") ? ERROR : WARN,
                "history", diagnosticId, "-", "history.error." + code, cause);
        if (code.equals("limit")) return Result.failure(new DesktopUiText(NAMESPACE, "history.error.limit", "history.error.limit",
                List.of(Long.toString(HistoryRecords.MAX_BYTES / (1024 * 1024)),
                        Long.toString(HistoryRecords.MAX_TOTAL_BYTES / (1024 * 1024)), Integer.toString(HistoryRecords.MAX_FILES))));
        return Result.failure(text("history.error." + code));
    }
    private static DesktopUiText text(String key) { return DesktopUiText.plugin(NAMESPACE, key, key); }
}
