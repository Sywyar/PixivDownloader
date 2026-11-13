package top.sywyar.pixivdownload.gui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 管理员设置入口；敏感值仅写入，不通过读取或保存响应返回。 */
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
    public record Changes(List<SettingsApiService.Change> changes) {}
    public record FieldError(String code, String error, String owner, String key, String variable) implements ApiErrorResponse {}

    @GetMapping public List<SettingsApiService.Field> fields() throws IOException { return settings.fields(); }
    @GetMapping("/presets") public List<SettingsApiService.Preset> presets() throws IOException { return settings.presets(); }

    @PostMapping(value = "/validate", consumes = "application/json")
    public List<SettingsApiService.Saved> validate(HttpServletRequest request) throws IOException {
        return settings.save(ToolApiRequests.read(request, Changes.class, BODY_LIMIT).changes(), true);
    }

    @PostMapping(consumes = "application/json")
    public List<SettingsApiService.Saved> save(HttpServletRequest request) throws IOException {
        return settings.save(ToolApiRequests.read(request, Changes.class, BODY_LIMIT).changes(), false);
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
                case "SETTINGS_OWNER_CHANGED" -> "plugin.manage.error.impact-changed";
                default -> "tools.api.invalid-request";
            };
            String message = field.messageKey == null ? messages.get(locale, key)
                    : messages.get(locale, field.messageKey, "{" + field.variable + "}");
            return ResponseEntity.status(field.getMessage().equals("SETTINGS_OWNER_CHANGED") ? 409 : 400)
                    .body(new FieldError(field.getMessage(), message, field.owner, field.key, field.variable));
        }
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
