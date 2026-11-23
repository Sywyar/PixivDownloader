package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 数据库管理先预检再确认；普通读取不返回密码，检查不保存连接。 */
@HttpApiGroup("core-database")
@RestController
@RequestMapping("/api/settings/database")
public final class DatabaseSettingsController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DatabaseSettingsController.class);
    private static final int BODY_LIMIT = 64 * 1024;
    private final DatabaseSettingsService settings;
    private final ToolApiRequests requests;
    private final AppMessages messages;

    public DatabaseSettingsController(DatabaseSettingsService settings, ToolApiRequests requests, AppMessages messages) {
        this.settings = settings;
        this.requests = requests;
        this.messages = messages;
    }

    public enum SecretAction { KEEP, SET, CLEAR }
    public record Draft(String provider, String jdbcUrl, String username, SecretAction passwordAction, String password) {
        @Override public String toString() { return "Draft[redacted]"; }
        Map<String, String> updates() {
            var result = new LinkedHashMap<String, String>();
            if (provider != null) result.put("database.provider", provider);
            if (jdbcUrl != null) result.put("database.jdbc-url", jdbcUrl);
            if (username != null) result.put("database.username", username);
            var action = passwordAction == null ? SecretAction.KEEP : passwordAction;
            if (action != SecretAction.SET && password != null) throw new IllegalArgumentException();
            if (action == SecretAction.SET) {
                if (password == null || password.isBlank()) throw new IllegalArgumentException();
                result.put("database.password", password);
            } else if (action == SecretAction.CLEAR) result.put("database.password", "");
            return result;
        }
    }
    public record Confirm(String token) {}

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping public DatabaseSettingsService.EditableSettings settings() throws IOException { return settings.editableSettings(); }
    @GetMapping("/providers") public List<ProviderChoice> providers() { return settings.providers(); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/status") public State status() throws IOException { return settings.state(); }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/migration") public MigrationState migration() throws IOException { return settings.migrationState(); }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Draft.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/check", consumes = "application/json")
    public CheckResult check(HttpServletRequest request) throws IOException {
        return settings.check(ToolApiRequests.read(request, Draft.class, BODY_LIMIT).updates());
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Draft.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/migration-previews", consumes = "application/json")
    public MigrationPlan preview(HttpServletRequest request) throws IOException {
        return settings.previewMigration(ToolApiRequests.read(request, Draft.class, BODY_LIMIT).updates());
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Confirm.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/migrations", consumes = "application/json")
    public ResponseEntity<MigrationState> start(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Confirm.class, BODY_LIMIT);
        if (body.token() == null || body.token().isBlank() || body.token().length() > 128)
            throw new IllegalArgumentException();
        settings.startMigration(body.token());
        LOG.info("Database migration accepted through HTTP; process restart requested");
        return ResponseEntity.accepted().body(settings.migrationState());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> failed(IOException failure, Locale locale) {
        String key = DesktopDatabaseSettings.failureMessageKey(failure.getMessage());
        String code = key.endsWith(".unknown") ? "DATABASE_OPERATION_FAILED" : failure.getMessage();
        LOG.warn("Database HTTP operation failed code={} failureType={}", code, failure.getClass().getName());
        return ResponseEntity.status(key.endsWith(".unknown") ? 500 : 409)
                .body(ApiErrorResponse.of(code, messages.get(locale, key)));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
