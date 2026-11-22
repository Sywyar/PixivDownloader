package top.sywyar.pixivdownload.gui;

import io.swagger.v3.oas.annotations.media.SchemaProperty;

import io.swagger.v3.oas.annotations.media.ExampleObject;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** 启动缓存训练的远程管理入口，共用桌面任务身份与取消语义。 */
@HttpApiGroup("core-maintenance")
@RestController
@RequestMapping("/api/tools/startup-cache")
public final class StartupCacheController {
    private static final int BODY_LIMIT = 4096;
    private final Supplier<Optional<DesktopStartupCache>> service;
    private final AppMessages messages;
    private final ToolApiRequests requests;
    @Autowired
    public StartupCacheController(AppMessages messages, ToolApiRequests requests) {
        this(() -> StartupCacheOperations.service().map(value -> value), messages, requests);
    }
    StartupCacheController(Supplier<Optional<DesktopStartupCache>> service, AppMessages messages, ToolApiRequests requests) {
        this.service = service; this.messages = messages; this.requests = requests;
    }
    public record Status(boolean available, @HttpApiNullable DesktopStartupCache.Snapshot task) {}
    public record Accepted(UUID executionId) {}
    @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.FALSE, example = "{}")
    public record Start() {}
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = Status.class), examples = @ExampleObject(value = "{\"available\":false,\"task\":null}")))
    @GetMapping
    public Status status() {
        var cache = service.get(); return new Status(cache.isPresent(), cache.map(DesktopStartupCache::snapshot).orElse(null));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Start.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = Accepted.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<?> start(HttpServletRequest request, Locale locale) throws IOException {
        ToolApiRequests.read(request, Start.class, BODY_LIMIT);
        var cache = service.get();
        if (cache.isEmpty()) return unavailable(locale);
        var id = cache.get().start();
        return id == null ? unavailable(locale) : ResponseEntity.accepted().body(new Accepted(id));
    }
    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = DesktopStartupCache.Snapshot.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "STARTUP_CACHE_NOT_RUNNING"))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id, Locale locale) {
        var cache = service.get();
        if (cache.isEmpty()) return unavailable(locale);
        if (!cache.get().cancel(id)) return ResponseEntity.status(409).body(ApiErrorResponse.of("STARTUP_CACHE_NOT_RUNNING",
                messages.get(locale, "tools.startup-cache.not-running")));
        return ResponseEntity.accepted().body(cache.get().snapshot());
    }
    private ResponseEntity<?> unavailable(Locale locale) {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("STARTUP_CACHE_UNAVAILABLE",
                messages.get(locale, "tools.startup-cache.unavailable")));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) { return requests.invalid(failure, locale, BODY_LIMIT); }
}
