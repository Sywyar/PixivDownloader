package top.sywyar.pixivdownload.translation;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations.*;
import java.util.List;

@RestController
@RequestMapping("/api/translation")
public class TranslationController {
    private final NovelTranslationService translations;
    private final TranslationOperations operations;
    private final PluginServices services;
    private final MessageResolver messages;
    private final TranslationJobs jobs;
    public TranslationController(NovelTranslationService translations, TranslationOperations operations,
                                 PluginServices services, MessageResolver messages, TranslationJobs jobs) {
        this.translations = translations;
        this.operations = operations;
        this.services = services;
        this.messages = messages;
        this.jobs = jobs;
    }
    @PostMapping("/jobs") public ResponseEntity<TranslationJobs.View> submit(@RequestBody TranslationJobs.Submission submission) {
        var job = jobs.submit(submission);
        return ResponseEntity.accepted().header("Location", "/api/translation/jobs/" + job.id()).body(job);
    }
    @GetMapping("/jobs/{id}") public TranslationJobs.View job(@PathVariable java.util.UUID id) { return jobs.get(id); }
    @GetMapping("/jobs") public List<TranslationJobs.View> jobs(@RequestParam(defaultValue = "0") long novelId,
                                                              @RequestParam(required = false) Long seriesId) {
        if (novelId <= 0 && (seriesId == null || seriesId <= 0)) throw new IllegalArgumentException("translation.invalid-request");
        return jobs.find(novelId, seriesId);
    }
    @PostMapping("/jobs/{id}/resume") public TranslationJobs.View resume(@PathVariable java.util.UUID id) { return jobs.resume(id); }
    @PostMapping("/jobs/{id}/pause") public TranslationJobs.View pause(@PathVariable java.util.UUID id) { return jobs.stop(id, false); }
    @PostMapping("/jobs/{id}/cancel") public TranslationJobs.View cancel(@PathVariable java.util.UUID id) { return jobs.stop(id, true); }
    @DeleteMapping("/jobs/{id}") public ResponseEntity<Void> discard(@PathVariable java.util.UUID id) {
        jobs.discard(id); return ResponseEntity.noContent().build();
    }
    public record EngineStatus(boolean configured, String engine, boolean glossary, List<Engine> engines,
                               int defaultSegmentSize, int segmentLimit) {}
    public record LanguageRequest(String targetLanguage, String engine) {}
    public record LanguageResult(String code, boolean valid) {}

    @GetMapping("/status") public EngineStatus status() {
        boolean configured = services.find(TranslationDocuments.KEY).isPresent() && translations.isAvailable();
        return new EngineStatus(configured, translations.engine(), translations.supportsGlossary(), translations.engines(),
                translations.defaultSegmentSize(), translations.segmentLimit());
    }
    @PostMapping("/novel") public Result translate(@RequestBody Request request) { return operations.translate(request); }
    @GetMapping("/languages") public List<MachineTranslationClient.Language> languages(@RequestParam(required = false) String engine) {
        return translations.languages(engine);
    }
    @PostMapping("/series") public SeriesResult series(@RequestBody SeriesRequest request) {
        return operations.translateSeries(request);
    }
    @PostMapping("/language") public LanguageResult language(@RequestBody LanguageRequest request) {
        String code = operations.resolveLanguage(request.targetLanguage(), request.engine());
        return new LanguageResult(code, code != null && !code.isBlank());
    }
    @ExceptionHandler(ServiceAccessException.class) public ResponseEntity<?> unavailable(ServiceAccessException failure) {
        return ResponseEntity.status(503).body(ApiErrorResponse.of(failure.reason().name(), messages.get("translation.unavailable")));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> failed(IllegalStateException failure) {
        if (java.util.Set.of("translation.configuration-changed", "translation.job-conflict", "translation.job-active").contains(failure.getMessage()))
            return ResponseEntity.status(409).body(ApiErrorResponse.of("TRANSLATION_CONFLICT", messages.get(failure.getMessage())));
        if ("translation.job-capacity".equals(failure.getMessage()))
            return ResponseEntity.status(409).body(ApiErrorResponse.of("TRANSLATION_CAPACITY", messages.get(failure.getMessage())));
        return ResponseEntity.status(502).body(ApiErrorResponse.of("TRANSLATION_FAILED", messages.get(NovelTranslationService.errorKey(failure))));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException failure) {
        if ("translation.job-not-found".equals(failure.getMessage()))
            return ResponseEntity.status(404).body(ApiErrorResponse.of("TRANSLATION_NOT_FOUND", messages.get(failure.getMessage())));
        String key = switch (String.valueOf(failure.getMessage())) {
            case "translation.unknown-engine", "translation.glossary-unavailable", "translation.language-code-required" -> failure.getMessage();
            default -> "translation.invalid-request";
        };
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("TRANSLATION_INVALID_REQUEST", messages.get(key)));
    }
}
