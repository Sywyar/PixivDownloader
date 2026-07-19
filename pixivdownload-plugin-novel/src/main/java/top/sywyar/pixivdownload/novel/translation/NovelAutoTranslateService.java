package top.sywyar.pixivdownload.novel.translation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.NovelPlugin;

/**
 * 「新下载小说自动翻译」的服务端编排队列：下载落库后由 {@link NovelDownloadService} 提交，把新下小说整章翻译成
 * 目标语言并（系列且开启时）重生译文合订本。全程 best-effort —— 任何失败只记日志、绝不抛回调用方，绝不让小说下载失败。
 *
 * <p><b>同系列串行、跨系列并发</b>：同一 {@code seriesId} 的章节用一条 {@link CompletableFuture} 链按提交序串接，
 * 一次只译一本以保术语一致（对齐详情页系列批量翻译）；不同系列 / 独立单章并发提交到专用线程池
 * {@code novelTranslateTaskExecutor}（并发上限由 novel 插件执行设置控制）。
 *
 * <p><b>语言代码缓存</b>：每种目标语言文本只探一次（{@link NovelTranslationService#resolveLangCode}），结果缓存复用，
 * 既供 DB 跳过（langHint），也供合订选择语言变体。
 *
 * <p><b>名词映射表</b>：一律使用该小说 / 系列的默认映射表（按需创建），不支持指定其它表。
 *
 * <p>状态以 {@link StatusView} 暴露给前端轮询（{@code phase} 为原始枚举名，由前端本地化），供下载队列 / 计划队列
 * 显示「AI 翻译中 (Ns)」「等待前系列小说翻译完成，还有 n 个」。
 */
@Slf4j
@Service
public class NovelAutoTranslateService {

    /** 翻译阶段（原始枚举名透传给前端，由前端本地化文案）。{@code SAME_LANGUAGE} 为终态：原文已是目标语言、整章跳过。 */
    public enum Phase { QUEUED, WAITING_SERIES, RESOLVING, TRANSLATING, MERGING, DONE, SAME_LANGUAGE, FAILED }

    /**
     * 终态（DONE / FAILED）状态保留时长：进入终态超过此时长即惰性清理。取与前端轮询窗口同量级——
     * 比任何客户端轮询某本译文状态的时长都长，故不影响在跑 / 刚结束的展示，只界定 {@code statuses}
     * 的长期内存增长，并避免「曾翻译过的小说」的旧终态长期驻留、被后续轮次误读。
     */
    private static final long TERMINAL_RETENTION_MILLIS = Duration.ofMinutes(30).toMillis();

    /** 暴露给前端轮询的状态快照。 */
    public record StatusView(String phase, long elapsedSeconds, int seriesPending, String langCode,
                             boolean done, boolean failed, String failureReason) {}

    private final NovelTranslationService translationService;
    private final NovelGlossaryService glossaryService;
    private final NovelMergeService mergeService;
    private final AiChatClient aiChatClient;
    private final TaskExecutor executor;
    private final ScheduleCapabilityRegistry scheduleCapabilityRegistry;

    public NovelAutoTranslateService(NovelTranslationService translationService,
                                     NovelGlossaryService glossaryService,
                                     NovelMergeService mergeService,
                                     AiChatClient aiChatClient,
                                     @Qualifier("novelTranslateTaskExecutor") TaskExecutor executor,
                                     ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        this.translationService = translationService;
        this.glossaryService = glossaryService;
        this.mergeService = mergeService;
        this.aiChatClient = aiChatClient;
        this.executor = executor;
        this.scheduleCapabilityRegistry = scheduleCapabilityRegistry;
    }

    // 目标语言文本（归一化小写）→ 已解析的 BCP-47 代码；只缓存非空结果（失败可重试）。
    private final Map<String, String> langCodeCache = new ConcurrentHashMap<>();
    // 各小说的翻译状态（轻量内存，按 novelId 覆盖式写入）。
    private final Map<Long, JobStatus> statuses = new ConcurrentHashMap<>();
    // 各系列的串行链尾：同系列任务依次接龙，保证一次只译一本。
    private final Map<Long, CompletableFuture<Void>> seriesTails = new ConcurrentHashMap<>();
    // 各系列已派发 / 已完成的序号，用于推导某待译章节「前面还有几个」。
    private final Map<Long, AtomicLong> seriesSeq = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> seriesDone = new ConcurrentHashMap<>();

    /** 单本小说的可变翻译状态。 */
    private static final class JobStatus {
        volatile Phase phase = Phase.QUEUED;
        volatile long phaseStartedAtMillis = System.currentTimeMillis();
        final Long seriesId;
        final long seriesSeq;
        volatile String langCode = "";
        volatile boolean done;
        volatile boolean failed;
        volatile String failureReason;

        JobStatus(Long seriesId, long seriesSeq) {
            this.seriesId = seriesId;
            this.seriesSeq = seriesSeq;
        }

        void enter(Phase next) {
            this.phase = next;
            this.phaseStartedAtMillis = System.currentTimeMillis();
        }
    }

    /**
     * 提交一本小说的自动翻译。
     *
     * @param novelId        小说 ID
     * @param seriesId       所属系列 ID（{@code null} / {@code <=0} 表示独立单章）
     * @param targetLanguage 用户填写的目标语言自由文本（如「english」「简体中文」）
     * @param segmentSize    分段字数阈值（{@code <=0} 整章一次性翻译）
     * @param mergeAfter     系列译完后是否重生译文合订本
     * @param mergeFormat    合订本格式（{@code epub}/{@code txt}/{@code html}）
     * @return 该小说翻译尝试完成（成功或失败）时 complete 的 future；web 端可丢弃，计划任务可 join 后再合订
     */
    public CompletableFuture<Void> submit(long novelId, Long seriesId, String targetLanguage, int segmentSize,
                                          boolean mergeAfter, String mergeFormat) {
        pruneExpiredTerminal(System.currentTimeMillis());
        Long series = seriesId != null && seriesId > 0 ? seriesId : null;
        long seq = series == null ? 0
                : seriesSeq.computeIfAbsent(series, k -> new AtomicLong()).incrementAndGet();
        JobStatus status = new JobStatus(series, seq);
        if (series != null) {
            status.enter(Phase.WAITING_SERIES);
        }
        statuses.put(novelId, status);

        AtomicBoolean jobStarted = new AtomicBoolean();
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> ownerLease = prepareOwnerLease();
        if (ownerLease == null) {
            fail(status, "plugin-unavailable");
            markSeriesDone(status);
            return CompletableFuture.completedFuture(null);
        }
        try {
            if (!scheduleCapabilityRegistry.activate(ownerLease)) {
                ownerLease.close();
                fail(status, "plugin-unavailable");
                markSeriesDone(status);
                return CompletableFuture.completedFuture(null);
            }
        } catch (RuntimeException | Error activationFailure) {
            Throwable propagation = closeLease(ownerLease, activationFailure);
            rethrow(propagation);
            throw new IllegalStateException("unreachable");
        }
        CompletableFuture<Void> leaseFuture = null;
        boolean futureOwnsLease = false;
        try {
            ScheduledCancellation cancellation = ownerLease.cancellation();
            Runnable job = () -> {
                jobStarted.set(true);
                try {
                    runJob(novelId, status, targetLanguage, segmentSize,
                            mergeAfter, mergeFormat, cancellation);
                } finally {
                    markSeriesDone(status);
                }
            };
            if (series == null) {
                leaseFuture = closeLeaseWhenComplete(
                        CompletableFuture.runAsync(job, executor), ownerLease,
                        novelId, status, jobStarted);
                futureOwnsLease = true;
                return leaseFuture;
            }
            // 同系列接龙：handle 吞掉前序异常，保证后续章节仍会执行；owner lease 覆盖排队等待和实际执行。
            synchronized (seriesTails) {
                CompletableFuture<Void> prev = seriesTails.getOrDefault(
                        series, CompletableFuture.completedFuture(null));
                leaseFuture = closeLeaseWhenComplete(
                        prev.handle((ignored, ex) -> null).thenRunAsync(job, executor),
                        ownerLease, novelId, status, jobStarted);
                futureOwnsLease = true;
                seriesTails.put(series, leaseFuture);
                return leaseFuture;
            }
        } catch (RuntimeException | Error submissionFailure) {
            if (futureOwnsLease) {
                if (submissionFailure instanceof Error error) {
                    throw error;
                }
                log.warn("Auto-translate novel {} series-tail tracking failed [{}]: {}",
                        novelId, submissionFailure.getClass().getSimpleName(), submissionFailure.getMessage());
                return leaseFuture;
            }
            Throwable propagation = submissionFailure;
            if (!jobStarted.get()) {
                try {
                    markSubmissionRejected(novelId, status, submissionFailure);
                } catch (Throwable statusFailure) {
                    propagation = mergeFailure(propagation, statusFailure);
                }
            }
            propagation = closeLease(ownerLease, propagation);
            if (propagation instanceof Error error) {
                throw error;
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> prepareOwnerLease() {
        var handle = scheduleCapabilityRegistry.resolveOwner(NovelPlugin.ID).orElse(null);
        if (handle == null) {
            return null;
        }
        return scheduleCapabilityRegistry.prepareAcquire(handle).orElse(null);
    }

    private static Throwable closeLease(AutoCloseable lease, Throwable failure) {
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            return mergeFailure(failure, closeFailure);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (failureRank(failure) > failureRank(current)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        addSuppressedSafely(current, failure);
        return current;
    }

    private static int failureRank(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private CompletableFuture<Void> closeLeaseWhenComplete(
            CompletableFuture<Void> future,
            ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> ownerLease,
            long novelId,
            JobStatus status,
            AtomicBoolean jobStarted) {
        return future.handle((ignored, failure) -> {
            try {
                if (failure != null && !jobStarted.get()) {
                    markSubmissionRejected(novelId, status, failure);
                    return null;
                }
                if (failure != null) {
                    throw failure instanceof CompletionException completion
                            ? completion : new CompletionException(failure);
                }
                return null;
            } finally {
                ownerLease.close();
            }
        });
    }

    private void markSubmissionRejected(long novelId, JobStatus status, Throwable failure) {
        log.warn("Auto-translate novel {} submission rejected [{}]: {}",
                novelId, failure.getClass().getSimpleName(), failure.getMessage());
        fail(status, "executor-rejected");
        markSeriesDone(status);
    }

    private void runJob(long novelId, JobStatus status, String targetLanguage, int segmentSize,
                        boolean mergeAfter, String mergeFormat, ScheduledCancellation cancellation) {
        try {
            if (cancelled(cancellation, status)) {
                return;
            }
            if (!aiChatClient.isConfigured()) {
                fail(status, "ai-unavailable");
                return;
            }
            status.enter(Phase.RESOLVING);
            String langCode = resolveCachedLang(targetLanguage);
            if (cancelled(cancellation, status)) {
                return;
            }
            Long glossaryId = glossaryService.getOrCreateNovelDefaultId(novelId);
            if (cancelled(cancellation, status)) {
                return;
            }

            status.enter(Phase.TRANSLATING);
            NovelTranslationService.Result result = translationService.translateChapter(
                    novelId, targetLanguage, Math.max(0, segmentSize), false,
                    (langCode == null || langCode.isBlank()) ? null : langCode, glossaryId,
                    true, true, true);
            if (cancelled(cancellation, status)) {
                return;
            }
            NovelTranslationService.Status st = result.status();
            if (st == NovelTranslationService.Status.SAME_LANGUAGE) {
                // 原文已是目标语言：跳过整章翻译；若开启系列合订，仍刷新原文基准与已有译文变体，
                // 因为变体合订对缺失译文章节会回退原文，本章落库后仍可能影响最终合订内容。
                status.langCode = result.langCode() == null ? "" : result.langCode();
                if (status.seriesId != null && mergeAfter) {
                    if (cancelled(cancellation, status)) {
                        return;
                    }
                    status.enter(Phase.MERGING);
                    mergeSeriesBestEffort(status, mergeFormat);
                }
                if (cancelled(cancellation, status)) {
                    return;
                }
                status.done = true;
                status.enter(Phase.SAME_LANGUAGE);
                return;
            }
            if (st != NovelTranslationService.Status.OK && st != NovelTranslationService.Status.SKIPPED) {
                fail(status, st.name());
                return;
            }
            String resolvedLang = result.langCode() != null && !result.langCode().isBlank()
                    ? result.langCode() : langCode;
            status.langCode = resolvedLang == null ? "" : resolvedLang;

            if (status.seriesId != null) {
                // 系列名 / 系列简介翻译（best-effort，共用默认表）；失败不影响合订。
                try {
                    if (cancelled(cancellation, status)) {
                        return;
                    }
                    translationService.translateSeriesTitle(status.seriesId, targetLanguage,
                            resolvedLang, glossaryId, true, true);
                } catch (Exception e) {
                    log.debug("Auto-translate series title failed for series {}: {}",
                            status.seriesId, e.getMessage());
                }
                if (mergeAfter) {
                    if (cancelled(cancellation, status)) {
                        return;
                    }
                    status.enter(Phase.MERGING);
                    mergeSeriesBestEffort(status, mergeFormat);
                }
            }
            if (cancelled(cancellation, status)) {
                return;
            }
            status.done = true;
            status.enter(Phase.DONE);
        } catch (Exception e) {
            log.warn("Auto-translate novel {} failed [{}]: {}",
                    novelId, e.getClass().getSimpleName(), e.getMessage());
            fail(status, e.getClass().getSimpleName());
        }
    }

    private static boolean cancelled(ScheduledCancellation cancellation, JobStatus status) {
        if (!cancellation.isCancellationRequested()) {
            return false;
        }
        fail(status, "plugin-quiesced");
        return true;
    }

    private void markSeriesDone(JobStatus status) {
        if (status.seriesId != null) {
            seriesDone.computeIfAbsent(status.seriesId, key -> new AtomicLong()).incrementAndGet();
        }
    }

    private void mergeSeriesBestEffort(JobStatus status, String mergeFormat) {
        try {
            mergeService.merge(status.seriesId,
                    NovelDownloadService.NovelFormat.parse(mergeFormat));
        } catch (Exception e) {
            // 合订失败不算翻译失败：译文 / 原文已落库，仅记日志。
            log.warn("Auto-translate series merge failed for series {}: {}",
                    status.seriesId, e.getMessage());
        }
    }

    private static void fail(JobStatus status, String reason) {
        status.failed = true;
        status.failureReason = reason;
        status.enter(Phase.FAILED);
    }

    /** 每种目标语言只探一次语言代码；只缓存非空结果，失败可后续重试。 */
    private String resolveCachedLang(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return "";
        }
        String key = targetLanguage.trim().toLowerCase(Locale.ROOT);
        String cached = langCodeCache.get(key);
        if (cached != null) {
            return cached;
        }
        String code = translationService.resolveLangCode(targetLanguage);
        if (code != null && !code.isBlank()) {
            langCodeCache.put(key, code);
            return code;
        }
        return "";
    }

    /** 某小说当前的翻译状态快照；从未提交过或终态已过期清理时返回 {@code null}。 */
    public StatusView getStatus(long novelId) {
        return getStatus(novelId, System.currentTimeMillis());
    }

    /** 带显式当前时刻的状态读取（终态过期判定以此为基准），便于单测验证过期清理。 */
    StatusView getStatus(long novelId, long now) {
        JobStatus s = statuses.get(novelId);
        if (s == null) {
            return null;
        }
        if (isExpiredTerminal(s, now)) {
            // 终态已超过保留期：清掉并视为「无翻译记录」，避免旧状态被后续读取误用。
            statuses.remove(novelId, s);
            return null;
        }
        long elapsed = Math.max(0, (now - s.phaseStartedAtMillis) / 1000);
        int ahead = 0;
        if (s.phase == Phase.WAITING_SERIES && s.seriesId != null) {
            long done = seriesDone.getOrDefault(s.seriesId, new AtomicLong()).get();
            ahead = (int) Math.max(0, (s.seriesSeq - 1) - done);
        }
        return new StatusView(s.phase.name(), elapsed, ahead, s.langCode,
                s.done, s.failed, s.failureReason);
    }

    /** 惰性清理已超过保留期的终态（DONE / FAILED）状态条目，界定 {@code statuses} 的内存增长。 */
    private void pruneExpiredTerminal(long now) {
        statuses.values().removeIf(s -> isExpiredTerminal(s, now));
    }

    /** 是否为已超过保留期的终态状态（仅 DONE / FAILED 才计入过期；运行中 / 等待中的状态永不过期）。 */
    private static boolean isExpiredTerminal(JobStatus s, long now) {
        return (s.done || s.failed) && now - s.phaseStartedAtMillis > TERMINAL_RETENTION_MILLIS;
    }
}
