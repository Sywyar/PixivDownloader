package top.sywyar.pixivdownload.translation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.translation.api.*;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.Kind;
import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** 后端拥有执行与恢复；浏览器连接只负责提交命令、读取状态。 */
@Slf4j
public final class TranslationJobs {
    public record Submission(UUID requestId, Request request, Long seriesId, boolean allChapters,
                             boolean mergeAfter, String mergeFormat, boolean metadataOnly) {
        public Submission(UUID requestId, Request request, Long seriesId, boolean allChapters, boolean mergeAfter, String mergeFormat) {
            this(requestId, request, seriesId, allChapters, mergeAfter, mergeFormat, false);
        }
    }
    public record View(UUID id, String state, String reason, String engine, String language, String variantKey,
                       int completed, int total, int savedSteps, int ok, int skipped, String outcome,
                       String runId, long elapsedSeconds) {}
    public static final class Job {
        public UUID id;
        public Submission submission;
        public String engine, fingerprint, aiFingerprint, state, reason, language, runId, outcome, queueKey;
        public List<Long> novelIds = new ArrayList<>();
        public int cursor, savedSteps, ok, skipped;
        public long startedAt, updatedAt;
        public boolean metadataDone, merged, hasTranslation;
        public Job() {}
    }
    private record Running(Job job, QueueTaskTracker.Task task) {}
    private final TranslationJobStore store;
    private final NovelTranslationService translations;
    private final TranslationDocuments documents;
    private final TaskExecutor executor;
    private final QueueTaskTracker tracker;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private boolean recovered;
    public TranslationJobs(TranslationJobStore store, NovelTranslationService translations, TranslationDocuments documents,
                           TaskExecutor executor, QueueTaskTracker tracker) {
        this.store = store; this.translations = translations; this.documents = documents;
        this.executor = executor; this.tracker = tracker;
    }
    private synchronized void recover() {
        if (recovered) return;
        for (Job job : store.all()) if (active(job.state)) {
            job.state = "PAUSED"; job.reason = "translation.interrupted"; store.save(job);
            log.info("Translation job recovered: jobId={}, state=PAUSED, savedSteps={}", job.id, job.savedSteps);
        }
        recovered = true;
    }
    public synchronized View submit(Submission submitted) {
        recover();
        if (!tracker.isAccepting()) throw new IllegalStateException("translation.unavailable");
        validate(submitted);
        Job duplicate = store.get(submitted.requestId());
        if (duplicate != null) {
            if (!duplicate.submission.equals(submitted)) throw new IllegalStateException("translation.job-conflict");
            return view(duplicate);
        }
        Request request = submitted.request();
        String engine = translations.engine(request.engine());
        if (!translations.isAvailable(engine)) throw new IllegalStateException("translation.unavailable");
        Job job = new Job();
        job.id = submitted.requestId(); job.submission = submitted; job.engine = engine;
        job.queueKey = queueKey(submitted, documents.snapshot(engine));
        job.fingerprint = translations.executionFingerprint(engine); job.aiFingerprint = translations.fingerprint(engine);
        job.state = "QUEUED"; job.startedAt = job.updatedAt = System.currentTimeMillis();
        store.create(job);
        schedule(job);
        return view(job);
    }
    private static String queueKey(Submission submission, TranslationDocuments session) {
        Long series = submission.seriesId();
        if (!submission.allChapters() && !submission.metadataOnly()) {
            var source = session.getNovel(submission.request().novelId());
            if (source == null) throw new IllegalStateException("translation.source-changed");
            series = source.seriesId();
        }
        return series == null ? "novel:" + submission.request().novelId() : "series:" + series;
    }
    private static void validate(Submission submission) {
        if (submission == null || submission.requestId() == null || submission.request() == null)
            throw new IllegalArgumentException("translation.invalid-request");
        var r = submission.request();
        if ((!submission.allChapters() && !submission.metadataOnly() && r.novelId() <= 0)
                || ((submission.allChapters() || submission.metadataOnly()) && (submission.seriesId() == null || submission.seriesId() <= 0))
                || (submission.metadataOnly() && (submission.allChapters() || r.translateBody()))
                || r.targetLanguage() == null || r.targetLanguage().isBlank() || r.targetLanguage().length() > 100
                || r.segmentSize() < 0 || r.segmentSize() > 1_000_000
                || (!r.translateBody() && !r.translateTitle() && !r.translateDescription())
                || (r.glossaryId() != null && r.glossaryId() <= 0)
                || (submission.mergeFormat() != null && !List.of("epub", "txt").contains(submission.mergeFormat())))
            throw new IllegalArgumentException("translation.invalid-request");
    }
    private void schedule(Job job) {
        var task = tracker.prepareQueued(job.id.toString());
        running.put(job.id, new Running(job, task));
        var finished = new CompletableFuture<Void>();
        var previous = tails.get(job.queueKey);
        var tail = previous == null ? finished : previous.thenCompose(ignored -> finished);
        tails.put(job.queueKey, tail);
        tail.whenComplete((ignored, failure) -> tails.remove(job.queueKey, tail));
        task.onTermination(() -> {
            try {
                synchronized (job) {
                    if (active(job.state)) {
                        job.state = "CANCELLING".equals(job.state) ? "CANCELLED" : "PAUSED";
                        if (job.reason == null) job.reason = "translation.interrupted";
                        store.save(job);
                    }
                    log.info("Translation job stopped: jobId={}, runId={}, state={}, reason={}, completed={}, total={}, ok={}, skipped={}, savedSteps={}, durationMs={}",
                            job.id, job.runId, job.state, job.reason, job.cursor, job.novelIds.size(), job.ok,
                            job.skipped, job.savedSteps, Math.max(0, job.updatedAt - job.startedAt));
                }
            } finally { running.remove(job.id); finished.complete(null); }
        });
        task.bind(() -> execute(job, task));
        log.info("Translation job accepted: jobId={}, engine={}, state={}", job.id, job.engine, job.state);
        // 取消中间项也须等待前序退出，后续章节不能越过尚在运行的同系列任务。
        if (previous == null) dispatch(task); else previous.thenRun(() -> dispatch(task));
    }
    private void dispatch(QueueTaskTracker.Task task) {
        if (task.isCancellationRequested()) return;
        try { executor.execute(task); }
        catch (RuntimeException failure) {
            task.cancel();
            log.warn("Translation dispatch rejected: jobId={}, exceptionType={}", task.ownerKey(), failure.getClass().getSimpleName());
        }
    }
    private void execute(Job job, QueueTaskTracker.Task task) {
        try {
            NovelTranslationService attempt = translations.bind(job.engine);
            Thread worker = Thread.currentThread();
            task.onCancellation(worker::interrupt);
            synchronized (job) {
                guard(job, task);
                job.state = "RUNNING"; job.reason = null; job.runId = UUID.randomUUID().toString();
                job.startedAt = System.currentTimeMillis(); store.save(job);
            }
            log.info("Translation job started: jobId={}, runId={}, engine={}, resumedSteps={}",
                    job.id, job.runId, job.engine, job.savedSteps);
            var session = documents.snapshot(job.engine);
            if (!job.queueKey.equals(queueKey(job.submission, session)))
                throw new IllegalStateException("translation.source-changed");
            Long series = job.submission.seriesId();
            if (!job.submission.allChapters() && !job.submission.metadataOnly()) {
                var source = session.getNovel(job.submission.request().novelId());
                if (source == null) throw new IllegalStateException("translation.source-changed");
                series = source.seriesId();
            }
            guard(job, task);
            Runnable checkpointGuard = () -> {
                guard(job, task);
                if (!attempt.executionFingerprint(job.engine).equals(job.fingerprint))
                    throw new IllegalStateException("translation.configuration-changed");
            };
            var planning = new TranslationCheckpoint(store, job, "plan", session, checkpointGuard);
            if (job.novelIds.isEmpty()) {
                job.novelIds = new ArrayList<>(Arrays.asList(planning.memo("manifest", "manifest-v1", Long[].class, () -> {
                    if (job.submission.metadataOnly()) return new Long[0];
                    if (!job.submission.allChapters()) return new Long[]{job.submission.request().novelId()};
                    List<Long> ids = new ArrayList<>();
                    while (true) {
                        var page = session.seriesNovelIds(job.submission.seriesId(), ids.size(), 100);
                        if (page.isEmpty()) break;
                        if (ids.size() + page.size() > 10_000) throw new IllegalStateException("translation.job-capacity");
                        ids.addAll(page);
                    }
                    return ids.toArray(Long[]::new);
                })));
                store.save(job);
            }
            Request request = job.submission.request();
            if (job.language == null) {
                job.language = planning.memo("language", "language-v1", String.class, () ->
                        attempt.checkpointed(planning, job.aiFingerprint).resolveLangCode(request.targetLanguage(), job.engine));
                if (job.language == null || job.language.isBlank()) throw new IllegalStateException("translation.language-unsupported");
                store.save(job);
            }
            for (; job.cursor < job.novelIds.size();) {
                long id = job.novelIds.get(job.cursor);
                var checkpoint = new TranslationCheckpoint(store, job, "novel:" + id, session, checkpointGuard);
                guard(job, task);
                String outcome;
                {
                    if (!session.published(checkpoint.publication(Kind.NOVEL, id))) checkpoint.prepare(Kind.NOVEL, id, job.language);
                    var translated = attempt.checkpointed(checkpoint, job.aiFingerprint).translateChapter(id,
                            request.targetLanguage(), request.segmentSize(), request.overwrite(), job.language,
                            request.glossaryId(), request.translateBody(), request.translateTitle(), request.translateDescription(), job.engine);
                    outcome = translated.status().name();
                    if (!List.of("OK", "SKIPPED", "SAME_LANGUAGE", "EMPTY").contains(outcome))
                        throw new IllegalStateException("translation.remote-error");
                }
                synchronized (job) {
                    job.outcome = outcome;
                    if ("OK".equals(outcome)) job.ok++; else job.skipped++;
                    if ("OK".equals(outcome) || "SKIPPED".equals(outcome)) job.hasTranslation = true;
                    job.cursor++; store.advance(job, "novel:" + id);
                }
                log.info("Translation chapter checkpoint: jobId={}, runId={}, documentId={}, outcome={}, completed={}, total={}",
                        job.id, job.runId, id, outcome, job.cursor, job.novelIds.size());
            }
            if (series != null && (job.submission.metadataOnly() || job.submission.mergeAfter() && job.hasTranslation)) {
                if (!job.metadataDone && (request.translateTitle() || request.translateDescription())) {
                    var checkpoint = new TranslationCheckpoint(store, job, "series:" + series, session, checkpointGuard);
                    if (!session.published(checkpoint.publication(Kind.SERIES, series))) {
                        checkpoint.prepare(Kind.SERIES, series, job.language);
                        String result = attempt.checkpointed(checkpoint, job.aiFingerprint).translateSeriesTitle(series,
                                request.targetLanguage(), job.language, request.glossaryId(),
                                request.translateTitle(), request.translateDescription(), job.engine);
                        if (result == null || result.isBlank()) throw new IllegalStateException("translation.remote-error");
                    }
                    job.metadataDone = true;
                    if (job.submission.metadataOnly()) job.outcome = "OK";
                    store.advance(job, "series:" + series);
                }
                if (!job.merged && job.submission.mergeAfter()) {
                    guard(job, task);
                    session.mergeSeries(series, job.submission.mergeFormat() == null ? "epub" : job.submission.mergeFormat(), job.language);
                    job.merged = true; store.save(job);
                }
            }
            synchronized (job) {
                guard(job, task);
                if (job.outcome == null) job.outcome = "EMPTY";
                job.state = "COMPLETED"; job.reason = null; store.advance(job, null);
            }
        } catch (RuntimeException failure) {
            synchronized (job) {
                if (!"PAUSING".equals(job.state) && !"CANCELLING".equals(job.state)) {
                    job.state = "PAUSED"; job.reason = reason(failure); store.save(job);
                }
            }
            log.warn("Translation job paused: jobId={}, runId={}, reason={}, exceptionType={}, savedSteps={}",
                    job.id, job.runId, job.reason, failure.getClass().getSimpleName(), job.savedSteps);
        } finally {
            // 执行器复用线程，取消信号不得污染下一项任务。
            Thread.interrupted();
        }
    }
    private void guard(Job job, QueueTaskTracker.Task task) {
        if (task.isCancellationRequested() || !tracker.isAccepting() || Thread.currentThread().isInterrupted()
                || "PAUSING".equals(job.state) || "CANCELLING".equals(job.state))
            throw new IllegalStateException("translation.interrupted");
        if (!translations.executionFingerprint(job.engine).equals(job.fingerprint))
            throw new IllegalStateException("translation.configuration-changed");
    }
    public synchronized View get(UUID id) {
        recover();
        var active = running.get(id);
        Job job = active == null ? require(id) : active.job;
        synchronized (job) { return view(job); }
    }
    public synchronized List<View> find(long novelId, Long seriesId) {
        recover();
        return store.all().stream().filter(j -> seriesId != null ? Objects.equals(j.submission.seriesId(), seriesId)
                : j.submission.request().novelId() == novelId || j.novelIds.contains(novelId))
                .sorted(Comparator.comparingLong((Job j) -> j.startedAt).reversed()).map(j -> {
                    var execution = running.get(j.id);
                    Job current = execution == null ? active(j.state) ? require(j.id) : j : execution.job;
                    synchronized (current) { return view(current); }
                }).toList();
    }
    public synchronized View resume(UUID id) {
        recover();
        if (running.containsKey(id)) return get(id);
        Job job = require(id);
        if (!List.of("PAUSED", "CANCELLED").contains(job.state)) return view(job);
        if (!tracker.isAccepting()) throw new IllegalStateException("translation.unavailable");
        if (!translations.executionFingerprint(job.engine).equals(job.fingerprint))
            throw new IllegalStateException("translation.configuration-changed");
        job.state = "QUEUED"; job.reason = null; store.save(job); schedule(job);
        return view(job);
    }
    public synchronized View stop(UUID id, boolean cancel) {
        recover();
        var active = running.get(id);
        Job job = active == null ? require(id) : active.job;
        synchronized (job) {
            if ("COMPLETED".equals(job.state)) return view(job);
            job.state = active == null ? cancel ? "CANCELLED" : "PAUSED" : cancel ? "CANCELLING" : "PAUSING";
            job.reason = cancel ? "translation.cancelled" : "translation.paused";
            store.save(job);
        }
        if (active != null) active.task.cancel();
        return get(id);
    }
    public synchronized void discard(UUID id) {
        recover();
        if (running.containsKey(id)) throw new IllegalStateException("translation.job-active");
        require(id); store.discard(id);
    }
    Result await(UUID id) {
        while (true) {
            View view = get(id);
            if ("COMPLETED".equals(view.state())) return new Result(view.outcome(), view.language(), "", false, view.variantKey());
            if (!active(view.state())) throw new IllegalStateException(view.reason());
            try { Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("translation.interrupted", e); }
        }
    }
    private Job require(UUID id) {
        Job job = store.get(id);
        if (job == null) throw new IllegalArgumentException("translation.job-not-found");
        // 退出状态落盘失败后，数据库恢复时不能把无执行者的旧状态继续投影为运行中。
        if (active(job.state) && !running.containsKey(id)) {
            job.state = "PAUSED"; job.reason = "translation.interrupted"; store.save(job);
        }
        return job;
    }
    private static View view(Job job) {
        return new View(job.id, job.state, job.reason, job.engine, job.language,
                job.language == null || job.language.isBlank() ? null : new TranslationVariant(job.language, job.engine).key(),
                job.cursor, job.novelIds.size(), job.savedSteps, job.ok, job.skipped, job.outcome, job.runId,
                Math.max(0, ((active(job.state) ? System.currentTimeMillis() : job.updatedAt) - job.startedAt) / 1000));
    }
    static boolean active(String state) { return List.of("QUEUED", "RUNNING", "PAUSING", "CANCELLING").contains(state); }
    private static String reason(RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        return switch (message) {
            case "translation.configuration-changed", "translation.configuration-unavailable", "translation.source-changed",
                    "translation.target-changed", "translation.incomplete-response", "translation.job-capacity",
                    "translation.job-storage", "translation.checkpoint-conflict", "translation.interrupted",
                    "translation.invalid-response", "translation.language-changed", "translation.language-unsupported" -> message;
            default -> NovelTranslationService.errorKey(failure);
        };
    }
}
