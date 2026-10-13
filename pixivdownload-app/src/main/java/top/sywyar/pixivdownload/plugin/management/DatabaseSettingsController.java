package top.sywyar.pixivdownload.plugin.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 市场中的管理员数据库修复入口；核心 ADMIN 路由及 CSRF 过滤器继续生效。 */
@RestController
@RequestMapping("/api/plugins/database")
public class DatabaseSettingsController {
    static final int MAX_DRAFT_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final DesktopUiHost.ConfigFile config;
    private final DatabaseSettingsService settings;
    private final DatabaseTarget active;
    private final PluginStatusService plugins;
    private final AppMessages messages;
    private final AppLocaleResolver locales;
    private final AtomicBoolean saving = new AtomicBoolean();

    @Autowired
    public DatabaseSettingsController(PluginBootstrapSession session, DatabaseTarget active,
            ApplicationArguments arguments, AppMessages messages, AppLocaleResolver locales, PluginStatusService plugins) {
        this(new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath()).asConfigFile(), session, active,
                arguments.getSourceArgs(), messages, locales, plugins);
    }

    DatabaseSettingsController(DesktopUiHost.ConfigFile config, PluginBootstrapSession session,
            DatabaseTarget active, String[] arguments, AppMessages messages, AppLocaleResolver locales, PluginStatusService plugins) {
        this.config = config;
        this.active = active;
        this.plugins = plugins;
        this.settings = new DatabaseSettingsService(config, () -> DatabaseTarget.configurationEnvironment(arguments),
                () -> Optional.of(active), session::databaseProviders, session);
        this.messages = messages;
        this.locales = locales;
    }

    @GetMapping
    public ResponseEntity<?> read(HttpServletRequest request) {
        try { return ResponseEntity.ok(view()); }
        catch (IOException failure) { return error(request, 503, "gui.config.database.invalid"); }
    }

    @PostMapping(value = "/check", consumes = "application/json")
    public ResponseEntity<?> check(HttpServletRequest request) {
        try {
            var checked = settings.check(readDraft(request));
            return ResponseEntity.ok(new Check(checked.outcome(), checked.target(), checked.sqlState(),
                    checked.outcome() == DesktopDatabaseSettings.Outcome.FAILED ? failureKey(checked.sqlState()) : ""));
        }
        catch (DraftTooLarge failure) { return error(request, 413, "gui.config.database.draft-too-large"); }
        catch (IOException failure) { return error(request, 400, "gui.config.database.invalid"); }
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> save(HttpServletRequest request) {
        if (!saving.compareAndSet(false, true)) return error(request, 409, "gui.config.database.save-busy");
        try {
            Map<String, String> draft;
            try { draft = readDraft(request); }
            catch (DraftTooLarge failure) { return error(request, 413, "gui.config.database.draft-too-large"); }
            catch (IOException failure) { return error(request, 400, "gui.config.database.save-invalid"); }
            int[] failureStatus = {400};
            try {
                config.withWriteLock(() -> {
                    Map<String, String> prepared = settings.prepare(draft);
                    failureStatus[0] = 500;
                    config.writeAll(prepared);
                });
            } catch (IOException failure) {
                return error(request, failureStatus[0], failureStatus[0] == 400
                        ? "gui.config.database.save-invalid" : "gui.config.database.save-failed");
            }
            // 已保存不代表活动目标变化；展示读取失败也不能把成功写入误报为失败。
            return ResponseEntity.ok(new Saved(true, true));
        } finally { saving.set(false); }
    }

    @PostMapping(value = "/preview", consumes = "application/json")
    public ResponseEntity<?> preview(HttpServletRequest request) {
        try {
            var plan = settings.previewMigration(readDraft(request));
            return ResponseEntity.ok(new MigrationPlan(plan.token(), plan.ready(), plan.stores(), plan.failure(),
                    plan.ready() ? "" : failureKey(plan.failure())));
        }
        catch (DraftTooLarge failure) { return error(request, 413, "gui.config.database.draft-too-large"); }
        catch (IOException failure) { return error(request, 400, "gui.config.database.invalid"); }
    }

    @PostMapping(value = "/migrate", consumes = "application/json")
    public ResponseEntity<?> migrate(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > MAX_DRAFT_BYTES) throw new DraftTooLarge();
            byte[] bytes = request.getInputStream().readNBytes(MAX_DRAFT_BYTES + 1);
            if (bytes.length > MAX_DRAFT_BYTES) throw new DraftTooLarge();
            var confirmation = JSON.readValue(bytes, Confirmation.class);
            if (confirmation == null || confirmation.token() == null
                    || !java.util.UUID.fromString(confirmation.token()).toString().equals(confirmation.token()))
                throw new IOException("INVALID_CONFIRMATION");
            settings.startMigration(confirmation.token());
            return ResponseEntity.ok(Map.of("accepted", true));
        } catch (DraftTooLarge failure) { return error(request, 413, "gui.config.database.draft-too-large"); }
        catch (IOException | IllegalArgumentException failure) { return error(request, 409, "gui.config.database.migration.failed"); }
    }

    @GetMapping("/migration")
    public ResponseEntity<?> migration(HttpServletRequest request) {
        try {
            var state = settings.migrationState();
            return ResponseEntity.ok(new MigrationState(state.status(), state.failure(), state.stores(),
                    state.failure().isEmpty() ? "" : failureKey(state.failure())));
        }
        catch (IOException failure) { return error(request, 503, "gui.config.database.invalid"); }
    }

    private View view() throws IOException {
        String owner = active.providerOwner();
        boolean enableAllowed = plugins.recoveryGateSnapshot().safeToScan() && plugins.report().byId(owner)
                .filter(p -> p.status() == top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.DISABLED
                        && p.descriptor() != null && !p.requiredByPolicy()).isPresent();
        return new View(settings.providers(), settings.editableSettings(), settings.state(),
                settings.migrationGuide().toString(), new SelectedProvider(owner, enableAllowed));
    }

    private Map<String, String> readDraft(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_DRAFT_BYTES) throw new DraftTooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_DRAFT_BYTES + 1);
        if (bytes.length > MAX_DRAFT_BYTES) throw new DraftTooLarge();
        Draft draft = JSON.readValue(bytes, Draft.class);
        if (draft == null) throw new IOException("INVALID_DRAFT");
        var values = new LinkedHashMap<String, String>();
        if (draft.provider() != null) values.put("database.provider", draft.provider());
        if (draft.jdbcUrl() != null) values.put("database.jdbc-url", draft.jdbcUrl());
        if (draft.username() != null) values.put("database.username", draft.username());
        PasswordAction action = draft.passwordAction() == null ? PasswordAction.KEEP : draft.passwordAction();
        if (action == PasswordAction.REPLACE) {
            if (draft.password() == null || draft.password().isEmpty()) throw new IOException("PASSWORD_REQUIRED");
            values.put("database.password", draft.password());
        } else {
            if (draft.password() != null && !draft.password().isEmpty()) throw new IOException("PASSWORD_ACTION_MISMATCH");
            if (action == PasswordAction.CLEAR) values.put("database.password", "");
        }
        if (values.isEmpty()) throw new IOException("EMPTY_DRAFT");
        return ConfigFileEditor.validatedValues(values);
    }

    private ResponseEntity<?> error(HttpServletRequest request, int status, String key) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(key, messages.get(locales.resolveLocale(request), key)));
    }

    private static String failureKey(String code) {
        return DesktopDatabaseSettings.failureMessageKey(code).replace("gui.config.database.", "database.");
    }

    private static final class DraftTooLarge extends IOException {}
    public enum PasswordAction { KEEP, REPLACE, CLEAR }
    public record Draft(String provider, String jdbcUrl, String username, PasswordAction passwordAction, String password) {
        @Override public String toString() { return "DatabaseDraft"; }
    }
    public record View(List<DesktopDatabaseSettings.ProviderChoice> providers,
                       DatabaseSettingsService.EditableSettings settings, DesktopDatabaseSettings.State state, String migrationGuide, SelectedProvider selectedProvider) {}
    public record SelectedProvider(String owner, boolean enableAllowed) {}
    public record Saved(boolean saved, boolean restartRequired) {}
    public record Confirmation(String token) {}
    public record Check(DesktopDatabaseSettings.Outcome outcome, String target, String sqlState, String errorKey) {}
    public record MigrationPlan(String token, boolean ready, List<DesktopDatabaseSettings.MigrationStore> stores,
                                String failure, String failureKey) {}
    public record MigrationState(String status, String failure, List<DesktopDatabaseSettings.MigrationStore> stores,
                                 String failureKey) {}
}
