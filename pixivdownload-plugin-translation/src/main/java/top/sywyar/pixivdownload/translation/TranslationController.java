package top.sywyar.pixivdownload.translation;

import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations.*;

import java.util.List;

@KnownErrorCodes(status = "400", value = {"TRANSLATION_INVALID_REQUEST"})
@KnownErrorCodes(status = "409", value = {"TRANSLATION_CONFLICT", "TRANSLATION_CAPACITY"})
@KnownErrorCodes(status = "502", value = {"TRANSLATION_FAILED"})
@KnownErrorCodes(status = "503", value = {"NOT_ACTIVE", "UNDECLARED", "UNAVAILABLE", "AMBIGUOUS", "INVALID_VALUE", "CAPACITY", "INVOCATION_FAILED", "UNSUPPORTED"})
@KnownErrorCodes(status = "404", value = {"TRANSLATION_NOT_FOUND"})
@HttpApiGroup("translation-translation")
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
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true,
            headers = @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri-reference", example = "/api/translation/jobs/00000000-0000-4000-8000-000000000042")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/jobs") public ResponseEntity<TranslationJobs.View> submit(@RequestBody TranslationJobs.Submission submission) {
        var job = jobs.submit(submission);
        return ResponseEntity.accepted().header("Location", "/api/translation/jobs/" + job.id()).body(job);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/jobs/{id}") public TranslationJobs.View job(@PathVariable java.util.UUID id) { return jobs.get(id); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/jobs") public List<TranslationJobs.View> jobs(@RequestParam(required = false) Long novelId,
                                                              @RequestParam(required = false) Long seriesId) {
        if (seriesId != null && seriesId > 0) return jobs.find(0, seriesId);
        if (novelId != null && novelId > 0) return jobs.find(novelId, null);
        throw new IllegalArgumentException("translation.job-query-invalid");
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/jobs/{id}/resume") public TranslationJobs.View resume(@PathVariable java.util.UUID id) { return jobs.resume(id); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/jobs/{id}/pause") public TranslationJobs.View pause(@PathVariable java.util.UUID id) { return jobs.stop(id, false); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/jobs/{id}/cancel") public TranslationJobs.View cancel(@PathVariable java.util.UUID id) { return jobs.stop(id, true); }
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
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
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(allOf = Result.class),
            schemaProperties = @io.swagger.v3.oas.annotations.media.SchemaProperty(name = "status",
                    schema = @Schema(implementation = TranslationJobs.CompletedOutcome.class))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/novel") public Result translate(@RequestBody Request request) { return operations.translate(request); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/languages") public List<MachineTranslationClient.Language> languages(@RequestParam(required = false) String engine) {
        return translations.languages(engine);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/series") public SeriesResult series(@RequestBody SeriesRequest request) {
        return operations.translateSeries(request);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
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
            case "translation.job-query-invalid", "translation.unknown-engine", "translation.glossary-unavailable", "translation.language-code-required" -> failure.getMessage();
            default -> "translation.invalid-request";
        };
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("TRANSLATION_INVALID_REQUEST", messages.get(key)));
    }
}
