package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations;
import java.util.List;
import java.util.UUID;

/** 同步兼容入口和自动翻译共用持久任务，不另建内存队列。 */
public class TranslationOperations implements NovelTranslationOperations {
    private final NovelTranslationService translations;
    private final TranslationDocuments documents;
    private final TranslationJobs jobs;
    public TranslationOperations(NovelTranslationService translations, TranslationDocuments documents, TranslationJobs jobs) {
        this.translations = translations; this.documents = documents; this.jobs = jobs;
    }
    public List<Engine> engines() { return translations.engines(); }
    public Result translate(Request request) {
        return jobs.await(jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), request, null, false, false, null)).id());
    }
    public SeriesResult translateSeries(SeriesRequest request) {
        var r = new Request(0, request.targetLanguage(), 0, false, request.langHint(), request.glossaryId(),
                false, request.translateTitle(), request.translateDescription(), request.engine());
        var result = jobs.await(jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), r,
                request.seriesId(), false, false, null, true)).id());
        return new SeriesResult(result.langCode(), result.variantKey());
    }
    public String resolveLanguage(String target, String engine) { return translations.resolveLangCode(target, engine); }
    public void submit(AutoRequest request) {
        String engine = translations.engine();
        var previous = jobs.find(request.novelId(), null).stream()
                .filter(j -> j.engine().equals(engine) && TranslationJobs.active(j.state())).findFirst();
        if (previous.isPresent()) return;
        Long glossary = engine.equals("ai") ? documents.defaultGlossary(request.novelId()) : null;
        var r = new Request(request.novelId(), request.targetLanguage(), request.segmentSize(), false,
                null, glossary, true, true, true, engine);
        jobs.submit(new TranslationJobs.Submission(UUID.randomUUID(), r, request.seriesId(), false, request.mergeAfter(), request.mergeFormat()));
    }
    public Status status(long id) {
        var found = jobs.find(id, null);
        if (found.isEmpty()) return null;
        var job = found.get(0);
        boolean done = !TranslationJobs.active(job.state());
        String phase = done ? "COMPLETED".equals(job.state()) ? "SAME_LANGUAGE".equals(job.outcome()) ? "SAME_LANGUAGE" : "DONE" : "FAILED"
                : "QUEUED".equals(job.state()) ? "QUEUED" : "TRANSLATING";
        return new Status(phase, job.elapsedSeconds(), Math.max(0, job.total() - job.completed() - 1), job.language(),
                done, done && !"COMPLETED".equals(job.state()), job.reason());
    }
}
