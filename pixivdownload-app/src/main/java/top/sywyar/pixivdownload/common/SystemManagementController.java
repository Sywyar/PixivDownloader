package top.sywyar.pixivdownload.common;

import io.swagger.v3.oas.annotations.media.SchemaProperty;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.RuntimeConfigReloadService;
import top.sywyar.pixivdownload.config.http.ServerAddressProvider;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 管理员业务入口，复用进程管理与账号服务，不使用本地 GUI 凭据。 */
@HttpApiGroup("core-system")
@RestController
@RequestMapping("/api/admin/system")
public final class SystemManagementController {
    private static final int BODY_LIMIT = 64 * 1024;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SystemManagementController.class);
    private final ServerStateProvider state;
    private final ServerAddressProvider address;
    private final RuntimeConfigReloadService configuration;
    private final ApplicationRestartService restart;
    private final PixivConnectivityProbe connectivity;
    private final AppMessages messages;
    private final ToolApiRequests requests;
    private final long startedAt;

    public SystemManagementController(ServerStateProvider state, ServerAddressProvider address,
                                      RuntimeConfigReloadService configuration, ApplicationRestartService restart,
                                      PixivConnectivityProbe connectivity, AppMessages messages,
                                      ToolApiRequests requests, ApplicationContext context) {
        this.state = state; this.address = address; this.configuration = configuration; this.restart = restart;
        this.connectivity = connectivity; this.messages = messages; this.requests = requests;
        this.startedAt = context.getStartupDate();
    }

    public record Status(String mode, boolean setupComplete, @io.swagger.v3.oas.annotations.media.Schema(format = "date-time") String startedAt, String address) {}
    public record Reload(List<String> changedKeys) {}
    public record Password(String oldPassword, String newPassword) {
        @Override public String toString() { return "Password[redacted]"; }
    }

    @GetMapping public Status status() {
        return new Status(state.getMode(), state.isSetupComplete(), java.time.Instant.ofEpochMilli(startedAt).toString(),
                address.baseUri().toASCIIString());
    }

    @PostMapping("/connectivity") public PixivConnectivityProbe.Result connectivity() { return connectivity.probe(); }

    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/restart") public ResponseEntity<?> restart(Locale locale) {
        LOG.info("Application restart requested through administrator HTTP");
        return restart.requestRestart() ? ResponseEntity.accepted().build()
                : failure(503, "SYSTEM_RESTART_UNAVAILABLE", "gui.message.backend-busy", locale);
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Reload.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = RuntimeConfigReloadService.ReloadResult.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/reload", consumes = "application/json")
    public ResponseEntity<?> reload(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, Reload.class, BODY_LIMIT);
        var keys = body.changedKeys() == null ? List.<String>of() : body.changedKeys();
        if (keys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 256)) throw new IllegalArgumentException();
        try {
            var result = configuration.reloadHotConfig(keys);
            LOG.info("Runtime configuration reloaded through administrator HTTP appliedFields={}", result.appliedKeys().size());
            return ResponseEntity.ok(result);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Runtime configuration HTTP reload failed failureType={}", failure.getClass().getName());
            return failure(500, "SYSTEM_RELOAD_FAILED", "gui.config.reload.failed", locale);
        }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Password.class)))
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"CURRENT_PASSWORD_REQUIRED", "WEAK_PASSWORD", "SAME_PASSWORD", ToolApiRequests.INVALID_TOOL_REQUEST}))))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "SETUP_INCOMPLETE"))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/password", consumes = "application/json")
    public ResponseEntity<?> password(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, Password.class, BODY_LIMIT);
        if (body.oldPassword() == null || body.oldPassword().isEmpty())
            return failure(400, "CURRENT_PASSWORD_REQUIRED", "gui.security.validation.current-required", locale);
        if (body.newPassword() == null || body.newPassword().length() < SetupService.MIN_PASSWORD_LENGTH)
            return failure(400, "WEAK_PASSWORD", "gui.security.validation.weak-password", locale);
        if (body.oldPassword().equals(body.newPassword()))
            return failure(400, "SAME_PASSWORD", "gui.security.validation.same-password", locale);
        try {
            state.changePassword(body.oldPassword(), body.newPassword());
            LOG.info("Administrator password changed through HTTP; existing sessions revoked");
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            return failure(401, "INVALID_CURRENT_PASSWORD", "gui.security.error.invalid-current", locale);
        } catch (IllegalStateException unavailable) {
            return failure(409, "SETUP_INCOMPLETE", "auth.setup-required", locale);
        } catch (IOException failed) { return saveFailed(failed, locale); }
    }

    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "SETUP_INCOMPLETE"))))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/logout-all") public ResponseEntity<?> logoutAll(Locale locale) {
        try {
            state.revokeAllSessions();
            LOG.info("Administrator sessions revoked through HTTP");
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException unavailable) {
            return failure(409, "SETUP_INCOMPLETE", "auth.setup-required", locale);
        } catch (IOException failed) { return saveFailed(failed, locale); }
    }

    private ResponseEntity<ApiErrorResponse> saveFailed(IOException failure, Locale locale) {
        LOG.warn("Administrator account HTTP change failed failureType={}", failure.getClass().getName());
        return failure(500, "ACCOUNT_SAVE_FAILED", "gui.security.error.save-failed", locale);
    }
    private ResponseEntity<ApiErrorResponse> failure(int status, String code, String key, Locale locale) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.get(locale, key)));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) { return requests.invalid(failure, locale, BODY_LIMIT); }
}
