package top.sywyar.pixivdownload.gui;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.common.web.HttpJsonInput;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 管理员设置入口；敏感值仅写入，不通过读取或保存响应返回。 */
@KnownErrorCodes(status = "400", value = {"INVALID_TOOL_REQUEST", "SETTINGS_REQUIRED", "INVALID_SETTINGS_VALUE"})
@KnownErrorCodes(status = "409", value = "SETTINGS_OWNER_CHANGED")
@HttpApiGroup("core-settings")
@RestController
@RequestMapping("/api/settings")
public final class SettingsController {
    private static final int BODY_LIMIT = 256 * 1024;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SettingsController.class);
    private final SettingsApiService settings;
    private final ToolApiRequests requests;
    private final AppMessages messages;

    public SettingsController(SettingsApiService settings, ToolApiRequests requests, AppMessages messages) {
        this.settings = settings;
        this.requests = requests;
        this.messages = messages;
    }
    @Schema(requiredProperties = "changes")
    public record Changes(@ArraySchema(minItems = 1) List<SettingsApiService.Change> changes) {}
    public record FieldError(String code, String error, @HttpApiNullable String owner, @HttpApiNullable String key,
                             @HttpApiNullable String variable, @HttpApiNullable String field,
                             @HttpApiNullable String in, @HttpApiNullable String reason) implements ApiErrorResponse {}

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping public List<SettingsApiService.Field> fields(HttpServletRequest request) throws IOException {
        return settings.fields().stream().filter(field -> ProgramAccess.permits(request, field.owner(), "settings.read")).toList();
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/presets") public List<SettingsApiService.Preset> presets(HttpServletRequest request) throws IOException {
        return settings.presets().stream().filter(preset -> ProgramAccess.permits(request, preset.owner(), "settings.read")).toList();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Changes.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, FieldError.class})))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, FieldError.class})))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/validate", consumes = "application/json")
    public List<SettingsApiService.Saved> validate(HttpServletRequest request) throws IOException {
        return settings.save(authorizedChanges(request), true);
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Changes.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, FieldError.class})))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, FieldError.class})))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(consumes = "application/json")
    public List<SettingsApiService.Saved> save(HttpServletRequest request) throws IOException {
        return settings.save(authorizedChanges(request), false);
    }

    private List<SettingsApiService.Change> authorizedChanges(HttpServletRequest request) throws IOException {
        var changes = ToolApiRequests.readStrictStrings(request, Changes.class, BODY_LIMIT).changes();
        if (changes == null || changes.isEmpty()) throw SettingsApiService.missingChange(null, "/changes");
        for (int i = 0; i < changes.size(); i++) {
            var change = changes.get(i);
            if (change == null)
                throw SettingsApiService.missingChange(null, "/changes/" + i);
            ProgramAccess.require(request, change.owner(), "settings.write", () -> settings.hasSettingsOwner(change.owner()));
        }
        return changes;
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> failed(IOException failure, Locale locale) {
        String key = top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.failureMessageKey(failure.getMessage());
        if (!key.equals("gui.config.database.error.unknown"))
            return ResponseEntity.status(409).body(ApiErrorResponse.of(failure.getMessage(), messages.get(locale, key)));
        LOG.warn("Settings HTTP operation failed failureType={}", failure.getClass().getName());
        return ResponseEntity.internalServerError().body(ApiErrorResponse.of("SETTINGS_SAVE_FAILED",
                messages.get(locale, "desktop.ui.action.failed")));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        if (failure instanceof SettingsApiService.InvalidField field) {
            String key = switch (field.getMessage()) {
                case "SETTINGS_REQUIRED" -> "desktop.ui.config.required";
                case "SETTINGS_OWNER_CHANGED" -> "settings.api.owner-changed";
                default -> "tools.api.invalid-request";
            };
            String message = field.messageKey == null ? messages.get(locale, key, BODY_LIMIT)
                    : messages.get(locale, field.messageKey, "{" + field.variable + "}");
            return ResponseEntity.status(field.getMessage().equals("SETTINGS_OWNER_CHANGED") ? 409 : 400)
                    .body(new FieldError(field.getMessage(), message, field.owner, field.key, field.variable, field.field,
                            field.field == null ? null : "body", field.field == null ? null : field.reason));
        }
        if (failure instanceof tools.jackson.core.JacksonException binding) {
            String pointer = HttpJsonInput.pointer(binding);
            if (pointer != null) return ResponseEntity.badRequest().body(new FieldError("INVALID_TOOL_REQUEST",
                    messages.get(locale, "tools.api.invalid-request", BODY_LIMIT), null, null, null, pointer, "body", HttpJsonInput.reason(binding)));
        }
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
