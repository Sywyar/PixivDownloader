package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.AppDesktopUiModel.RendererContract;
import top.sywyar.pixivdownload.gui.GuiActionResponseSafety.ActionResult;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.responseDetail;
import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.safeJsonPath;
import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.sanitizeActionText;
import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 配置字段、插件配置贡献与持久化事务的应用侧 owner。
 */
final class DesktopConfigurationController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfigurationController.class);
    private static final String APP_OWNER = "app";

    final AppDesktopUiModel owner;
    final DesktopUiHost host;
    private final Path configPath;
    final RendererContract rendererContract;
    final Map<String, String> formValues;
    final DesktopRepositorySettingsController repositories;
    final Map<FieldKey, String> values = new ConcurrentHashMap<>();
    final Map<FieldKey, String> savedValues = new ConcurrentHashMap<>();
    private final DesktopConfigurationView view;
    private final DesktopConfigurationLoader loader;

    volatile Set<FieldKey> storedCredentialFields = Set.of();
    volatile List<ConfigField> configFields = List.of();
    volatile List<ConfigSection> configSections = List.of();
    volatile boolean debugUnlocked;
    volatile Map<String, ConfigField> fieldBindings = Map.of();
    volatile String configNotice = "";
    volatile TextToken configNoticeToken;
    volatile boolean autoStartSupported;
    volatile boolean autoStartEnabled;

    DesktopConfigurationController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            Path configPath,
            RendererContract rendererContract,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.configPath = configPath;
        this.rendererContract = rendererContract;
        this.formValues = formValues;
        this.repositories = new DesktopRepositorySettingsController(
                owner,
                host,
                formValues
        );
        this.view = new DesktopConfigurationView(this);
        this.loader = new DesktopConfigurationLoader(this);
        this.autoStartSupported = host.autoStartSupported();
        this.autoStartEnabled = autoStartSupported && host.autoStartEnabled();
    }

    DesktopUiNode classicPage(
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        return view.classicPage(nextSelections, nextActions);
    }

    DesktopUiNode controlCenterPage(
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        return view.controlCenterPage(nextSelections, nextActions);
    }

    boolean acceptField(String binding, String value) {
        ConfigField field = fieldBindings.get(binding);
        if (field == null) return false;
        values.put(field.key(), value);
        return true;
    }

    boolean acceptForm(String binding, String value) {
        if (!binding.startsWith("interface.") && !binding.startsWith("config.market."))
            return false;
        formValues.put(binding, value);
        repositories.acceptForm(binding, value);
        switch (binding) {
            case "interface.language" -> applyLocale(value);
            default -> {
            }
        }
        return true;
    }

    void unlockDebug() {
        if (debugUnlocked) return;
        debugUnlocked = true;
        configNotice = "";
        configNoticeToken = key("gui.config.notice.debug-unlocked");
        owner.rebuild();
    }

    void applyPreset(ConfigPreset preset) {
        preset.spec().values().forEach((key, value) -> values.put(
                new FieldKey(preset.owner(), key),
                value
        ));
        configNotice = "";
        configNoticeToken = null;
        owner.rebuild();
    }

    void runConfigAction(ConfigAction action) {
        configNotice = "";
        configNoticeToken = action.sendingNotice() == null ? appToken(
                "gui.config.action.notice.sending",
                action.spec().actionId()
        ) : action.sendingNotice().token();
        owner.runBusy(() -> {
            try {
                Map<String, Object> payload = actionPayload(action);
                DesktopUiHost.GuiResponse response = host.guiPostJson(
                        action.spec().endpoint(),
                        payload,
                        action.readTimeoutMillis(),
                        action.owner()
                );
                configNoticeToken = actionNotice(action, response);
            } catch (Exception failure) {
                configNoticeToken = appToken(
                        "gui.config.action.notice.failed",
                        action.spec().actionId(),
                        safeMessage(failure)
                );
            }
        });
    }

    private Map<String, Object> actionPayload(ConfigAction action) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> credentials = null;
        for (GuiConfigActionPayloadField mapping : action.spec().payloadFields()) {
            String value;
            if (mapping.fieldKey() == null) {
                value = mapping.literalValue();
            } else {
                FieldKey key = new FieldKey(action.owner(), mapping.fieldKey());
                ConfigField field = view.fields.field(key);
                value = values.getOrDefault(key, "");
                if (field != null && field.spec().sensitive() && value.isBlank()) {
                    if (credentials == null) credentials = host.readCredentials(action.owner());
                    value = credentials.getOrDefault(mapping.fieldKey(), "");
                }
            }
            putPayload(
                    payload,
                    mapping.payloadPath(),
                    value,
                    mapping.valueType()
            );
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void putPayload(
            Map<String, Object> root,
            String path,
            String value,
            GuiConfigActionPayloadType type
    ) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.computeIfAbsent(
                    parts[i],
                    ignored -> new LinkedHashMap<String, Object>()
            );
            if (!(child instanceof Map<?, ?>))
                throw new IllegalArgumentException("Conflicting action payload path");
            current = (Map<String, Object>) child;
        }
        String leaf = parts[parts.length - 1];
        Object converted = switch (type) {
            case INT -> parseInt(value, 0);
            case BOOLEAN -> Boolean.parseBoolean(value);
            case STRING -> nullToEmpty(value);
        };
        if (current.putIfAbsent(leaf, converted) != null) {
            throw new IllegalArgumentException("Duplicate action payload path");
        }
    }

    private TextToken actionNotice(
            ConfigAction action,
            DesktopUiHost.GuiResponse response
    ) {
        ActionResult result = ActionResult.from(
                response,
                action.spec().resultSummary()
        );
        for (GuiConfigActionResultRule rule : action.spec().resultRules().stream().sorted(Comparator.comparingInt(
                GuiConfigActionResultRule::order)).toList()) {
            if (rule.conditions().stream().allMatch(condition -> matches(
                    result,
                    condition
            ))) {
                List<String> arguments = rule.arguments().stream().map(argument -> argumentValue(
                        result,
                        argument
                )).toList();
                String namespace = rule.i18nNamespace() == null ? action.namespace() : rule.i18nNamespace();
                return token(
                        namespace,
                        rule.noticeKey(),
                        rule.noticeKey(),
                        arguments
                );
            }
        }
        if (!response.reachable()) {
            return appToken(
                    "gui.config.action.notice.unreachable",
                    action.spec().actionId()
            );
        }
        if (response.is2xx()) {
            return appToken("gui.config.action.notice.success", action.spec().actionId());
        }
        return appToken(
                "gui.config.action.notice.failed",
                action.spec().actionId(),
                "HTTP " + response.status()
        );
    }

    private static boolean matches(
            ActionResult result,
            GuiConfigActionResultCondition condition
    ) {
        String actual = result.value(condition.source(), condition.path());
        return switch (condition.operator()) {
            case TRUE -> Boolean.parseBoolean(actual);
            case FALSE -> !Boolean.parseBoolean(actual);
            case EQUALS -> actual.equals(condition.value());
            case NOT_EQUALS -> !actual.equals(condition.value());
            case GREATER_THAN -> parseInt(actual, 0) > parseInt(condition.value(), 0);
            case CONTAINS -> actual.contains(condition.value());
            case BLANK -> actual.isBlank();
            case NOT_BLANK -> !actual.isBlank();
        };
    }

    private static String argumentValue(
            ActionResult result,
            GuiConfigActionResultArgument argument
    ) {
        String value = result.value(argument.source(), argument.path());
        return value.isBlank() ? sanitizeActionText(argument.defaultValue()) : value;
    }

    void saveConfiguration() {
        owner.runBusy(() -> saveConfiguration(false));
    }

    private void saveConfiguration(boolean symbolicRootPinned) {
        List<ConfigField> changed = changedConfigurationFields();
        boolean repositoriesChanged = repositories.changed();
        Map<String, String> interfaceValues = pendingInterfaceValues();
        boolean interfaceChanged = interfaceValues.entrySet().stream().anyMatch(entry -> !Objects.equals(
                entry.getValue(),
                savedValues.get(new FieldKey(null, entry.getKey()))
        ));
        if (!repositories.loaded()) {
            setConfigNotice(host.message(
                    "gui.config.market.repo.read-failed",
                    repositories.loadFailure()
            ));
            return;
        }
        if (changed.isEmpty() && !repositoriesChanged && !interfaceChanged) {
            setConfigNotice(host.message("gui.config.notice.saved-no-change"));
            return;
        }
        try {
            validate(changed);
            ConfigField rootField = changed.stream().filter(field -> field.owner() == null && "download.root-folder".equals(
                    field.spec().key())).findFirst().orElse(null);
            if (!symbolicRootPinned && rootField != null) {
                String path = symbolicRootPathToPin(
                        savedValues.get(rootField.key()),
                        values.get(rootField.key())
                );
                if (path != null) {
                    showSymbolicRootPinDialog(path);
                    return;
                }
            }
            persist(
                    changed,
                    repositoriesChanged,
                    interfaceChanged ? interfaceValues : Map.of()
            );
            Set<String> hotKeys = new LinkedHashSet<>();
            boolean backendRestart = repositoriesChanged;
            boolean processRestart = interfaceChanged && !Objects.equals(
                    interfaceValues.get(
                            "app.gui-provider"),
                    savedValues.getOrDefault(new FieldKey(null, "app.gui-provider"), "gui-swing")
            );
            for (ConfigField field : changed) {
                switch (field.spec().effect()) {
                    case HOT_RELOAD -> hotKeys.add(field.spec().key());
                    case BACKEND_RESTART -> backendRestart = true;
                    case PROCESS_RESTART -> processRestart = true;
                }
                if (field.spec().sensitive()) {
                    values.put(field.key(), "");
                    Set<FieldKey> stored = new LinkedHashSet<>(storedCredentialFields);
                    stored.add(field.key());
                    storedCredentialFields = Set.copyOf(stored);
                } else {
                    savedValues.put(field.key(), values.get(field.key()));
                }
            }
            if (repositoriesChanged) repositories.markSaved();
            if (interfaceChanged) {
                interfaceValues.forEach((key, value) -> savedValues.put(
                        new FieldKey(null, key),
                        value
                ));
            }
            boolean hotReloaded = hotKeys.isEmpty() || host.guiPostJson(
                    "config/reload",
                    Map.of("changedKeys", List.copyOf(hotKeys)),
                    5_000
            ).is2xx();
            if (processRestart) {
                setConfigNotice(host.message("gui.config.notice.saved-process"));
                showConfigurationRestartDialog(true);
            } else if (backendRestart) {
                setConfigNotice(host.message("gui.config.notice.saved"));
                showConfigurationRestartDialog(false);
            } else {
                setConfigNotice(host.message(hotReloaded ? "gui.config.notice.saved-hot" : "gui.config.notice.saved-hot-failed"));
            }
        } catch (Exception failure) {
            setConfigNotice(host.message(
                    "gui.config.dialog.save-failed.message",
                    safeMessage(failure)
            ));
        }
    }

    private List<ConfigField> changedConfigurationFields() {
        return configFields.stream().filter(field -> !field.spec().sensitive() ? !Objects.equals(
                values.get(field.key()),
                savedValues.get(field.key())
        ) : !values.getOrDefault(field.key(), "").isBlank()).toList();
    }

    private static GuiConfigEffect strongestEffect(List<GuiConfigEffect> effects) {
        if (effects.contains(GuiConfigEffect.PROCESS_RESTART))
            return GuiConfigEffect.PROCESS_RESTART;
        if (effects.contains(GuiConfigEffect.BACKEND_RESTART))
            return GuiConfigEffect.BACKEND_RESTART;
        return GuiConfigEffect.HOT_RELOAD;
    }

    int pendingConfigurationChangeCount() {
        int count = changedConfigurationFields().size();
        if (repositories.changed()) count++;
        for (Map.Entry<String, String> entry : pendingInterfaceValues().entrySet()) {
            if (!Objects.equals(
                    entry.getValue(),
                    savedValues.get(new FieldKey(null, entry.getKey()))
            )) count++;
        }
        return count;
    }

    GuiConfigEffect pendingConfigurationEffect() {
        List<GuiConfigEffect> effects = changedConfigurationFields().stream().map(field -> field.spec().effect()).collect(
                java.util.stream.Collectors.toCollection(ArrayList::new));
        if (repositories.changed()) effects.add(GuiConfigEffect.BACKEND_RESTART);
        Map<String, String> interfaceValues = pendingInterfaceValues();
        if (interfaceValues.entrySet().stream().anyMatch(entry -> !Objects.equals(
                entry.getValue(),
                savedValues.get(new FieldKey(null, entry.getKey()))
        ))) {
            effects.add(Objects.equals(
                    interfaceValues.get("app.gui-provider"),
                    savedValues.getOrDefault(new FieldKey(null, "app.gui-provider"), "gui-swing")
            ) ? GuiConfigEffect.HOT_RELOAD : GuiConfigEffect.PROCESS_RESTART);
        }
        return strongestEffect(effects);
    }

    private String symbolicRootPathToPin(String oldValue, String newValue) {
        try {
            String oldRoot = host.normalizeRootFolder(oldValue);
            if (Path.of(oldRoot).isAbsolute()) return null;
            Path oldAbsolute = Path.of(oldRoot).toAbsolutePath().normalize();
            Path newAbsolute = Path.of(host.normalizeRootFolder(newValue)).toAbsolutePath().normalize();
            if (oldAbsolute.equals(newAbsolute)) return null;
        } catch (RuntimeException ignored) {
            return null;
        }

        DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
        if (!response.reachable() || !response.is2xx() || response.body() == null) {
            LOG.warn(host.message("gui.config.log.symbolic-pin.status-unavailable"));
            return null;
        }
        try {
            DesktopUiHost.GuiValue body = response.body();
            if (!body.path("symbolicReferenced").asBoolean(false)) return null;
            for (DesktopUiHost.GuiValue prefix : body.path("prefixes")) {
                if (prefix.path("symbolic").asBoolean(false)) {
                    String path = prefix.path("path").asText("");
                    return path.isBlank() ? null : path;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn(
                    host.message("gui.config.log.symbolic-pin.status-unavailable"),
                    failure
            );
        }
        return null;
    }

    private void showSymbolicRootPinDialog(String path) {
        owner.showDialog(
                "config.symbolic-pin",
                "gui.config.symbolic-pin.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> column(
                        "config.symbolic-pin.content",
                        new DesktopUiNode.Text(
                                "config.symbolic-pin.message",
                                appToken("gui.config.symbolic-pin.message", path),
                                TextStyle.BODY,
                                true,
                                false
                        ),
                        row(
                                "config.symbolic-pin.actions",
                                button(
                                        "config.symbolic-pin.confirm",
                                        "config.symbolic-pin.confirm",
                                        "desktop.ui.action.confirm",
                                        !owner.busy(),
                                        nextActions,
                                        () -> pinSymbolicRootAndSave(path)
                                ),
                                button(
                                        "config.symbolic-pin.cancel",
                                        "config.symbolic-pin.cancel",
                                        "desktop.ui.action.cancel",
                                        !owner.busy(),
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            setConfigNotice(host.message(
                                                    "gui.config.symbolic-pin.cancelled"));
                                            owner.rebuild();
                                        }
                                )
                        )
                ),
                620,
                0
        );
    }

    private void pinSymbolicRootAndSave(String path) {
        owner.closeDialog();
        owner.runBusy(() -> {
            try {
                DesktopUiHost.GuiResponse response = host.guiPostJson(
                        "path-prefixes/pin",
                        Map.of("path", path),
                        15_000
                );
                if (response.reachable() && response.is2xx() && response.body() != null && response.body().path(
                        "success").asBoolean(false)) {
                    saveConfiguration(true);
                    return;
                }
                String detail = response.reachable() ? Integer.toString(response.status()) : "unreachable";
                LOG.warn(host.message("gui.config.log.symbolic-pin.failed", detail));
            } catch (RuntimeException failure) {
                LOG.warn(
                        host.message("gui.config.log.symbolic-pin.failed", safeMessage(failure)),
                        failure
                );
            }
            owner.showDialog(
                    "config.symbolic-pin.failed",
                    "gui.dialog.error.title",
                    "gui.config.symbolic-pin.failed",
                    DesktopUiDocument.DialogStyle.ERROR
            );
        });
    }

    private void showConfigurationRestartDialog(boolean processRestart) {
        String title = processRestart ? "gui.action.restart-application" : "gui.action.restart-service";
        String message = processRestart ? "gui.config.dialog.process-restart-required.message" : "gui.status.dialog.restart.confirm.message";
        owner.showDialog(
                "config.restart",
                title,
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "config.restart.content",
                        text("config.restart.message", message, TextStyle.BODY),
                        row(
                                "config.restart.actions",
                                button(
                                        "config.restart.confirm",
                                        "config.restart.confirm",
                                        title,
                                        true,
                                        nextActions,
                                        processRestart ? this::restartApplicationAfterConfigSave : this::restartBackendAfterConfigSave
                                ),
                                button(
                                        "config.restart.later",
                                        dismissAction,
                                        "gui.action.restart-later",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                500,
                0
        );
    }

    private void restartApplicationAfterConfigSave() {
        owner.closeDialog();
        owner.runBusy(() -> setConfigNotice(host.message(host.restartApplication() ? "gui.config.notice.process-restarting" : "desktop.ui.action.failed")));
    }

    private void restartBackendAfterConfigSave() {
        owner.closeDialog();
        try {
            boolean accepted = host.restartBackend(owner::rebuild);
            setConfigNotice(host.message(accepted ? "gui.config.notice.restarting" : "gui.message.backend-busy"));
        } catch (RuntimeException failure) {
            LOG.warn(
                    host.message("gui.status.log.restart-request.failed", safeMessage(failure)),
                    failure
            );
            setConfigNotice(host.message("gui.message.backend-busy"));
        }
        owner.rebuild();
    }

    private void persist(
            List<ConfigField> changed,
            boolean repositoriesChanged,
            Map<String, String> interfaceValues
    ) throws Exception {
        Map<DesktopUiHost.ConfigFile, Map<String, String>> normal = new LinkedHashMap<>();
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        for (ConfigField field : changed) {
            String value = host.requireSafeConfigValue(values.getOrDefault(
                    field.key(),
                    ""
            ));
            if (field.spec().sensitive() && field.owner() != null) {
                secrets.computeIfAbsent(
                        field.owner(),
                        ignored -> new LinkedHashMap<>()
                ).put(host.requireSafeConfigKey(field.spec().key()), value);
            } else {
                DesktopUiHost.ConfigFile file = field.owner() == null ? host.applicationConfig() : host.pluginConfig(
                        field.owner());
                normal.computeIfAbsent(
                        file,
                        ignored -> new LinkedHashMap<>()
                ).put(host.requireSafeConfigKey(field.spec().key()), value);
            }
        }
        DesktopUiHost.ConfigFile applicationConfig = host.applicationConfig();
        if (!interfaceValues.isEmpty()) {
            Map<String, String> applicationValues = normal.computeIfAbsent(
                    applicationConfig,
                    ignored -> new LinkedHashMap<>()
            );
            for (Map.Entry<String, String> entry : interfaceValues.entrySet()) {
                applicationValues.put(
                        host.requireSafeConfigKey(entry.getKey()),
                        host.requireSafeConfigValue(entry.getValue())
                );
            }
        }
        if (repositoriesChanged) {
            host.readPluginRepositories(applicationConfig);
            normal.computeIfAbsent(applicationConfig, ignored -> new LinkedHashMap<>());
        }
        Map<DesktopUiHost.ConfigFile, DesktopUiHost.ConfigSnapshot> snapshots = new LinkedHashMap<>();
        for (DesktopUiHost.ConfigFile file : normal.keySet()) snapshots.put(
                file,
                file.snapshot()
        );
        Map<String, DesktopUiHost.CredentialSnapshot> credentialSnapshots = new LinkedHashMap<>();
        for (String owner : secrets.keySet())
            credentialSnapshots.put(owner, host.snapshotCredentials(owner));
        try {
            host.withCredentialLocks(
                    secrets.keySet(),
                    () -> {
                        for (Map.Entry<DesktopUiHost.ConfigFile, Map<String, String>> entry : normal.entrySet()) {
                            if (!entry.getValue().isEmpty())
                                entry.getKey().writeAll(entry.getValue());
                        }
                        if (repositoriesChanged) {
                            host.writePluginRepositories(applicationConfig, repositories.entries());
                        }
                        for (Map.Entry<String, Map<String, String>> entry : secrets.entrySet()) {
                            host.updateCredentials(entry.getKey(), entry.getValue());
                        }
                    }
            );
        } catch (Exception failure) {
            Exception rollbackFailure = null;
            for (Map.Entry<DesktopUiHost.ConfigFile, DesktopUiHost.ConfigSnapshot> entry : snapshots.entrySet()) {
                try {
                    entry.getKey().restore(entry.getValue());
                } catch (Exception rollback) {
                    rollbackFailure = rollback;
                }
            }
            for (Map.Entry<String, DesktopUiHost.CredentialSnapshot> entry : credentialSnapshots.entrySet()) {
                try {
                    host.restoreCredentials(entry.getKey(), entry.getValue());
                } catch (Exception rollback) {
                    rollbackFailure = rollback;
                }
            }
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    private void validate(List<ConfigField> fields) throws Exception {
        for (ConfigField field : fields) {
            GuiConfigFieldContribution spec = field.spec();
            String value = values.getOrDefault(field.key(), "");
            host.requireSafeConfigKey(spec.key());
            host.requireSafeConfigValue(value);
            if (spec.type() == GuiConfigFieldType.PORT) {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) throw new IllegalArgumentException(spec.key());
            }
            if (spec.type() == GuiConfigFieldType.INT) {
                int number = Integer.parseInt(value);
                if (spec.minValue() != null && number < spec.minValue())
                    throw new IllegalArgumentException(spec.key());
                if (spec.maxValue() != null && number > spec.maxValue())
                    throw new IllegalArgumentException(spec.key());
            }
            if (spec.type() == GuiConfigFieldType.ENUM && !spec.enumValues().contains(value)) {
                throw new IllegalArgumentException(spec.key());
            }
            if (spec.key().startsWith("maintenance.") && spec.key().endsWith(".time") && !host.validMaintenanceTime(
                    value)) {
                throw new IllegalArgumentException(spec.key());
            }
        }
    }

    void clearCredential(ConfigField field) {
        if (field.owner() == null || !field.spec().sensitive()) return;
        owner.runBusy(() -> {
            try {
                host.updateCredentials(field.owner(), Map.of(field.spec().key(), ""));
                values.put(field.key(), "");
                Set<FieldKey> stored = new LinkedHashSet<>(storedCredentialFields);
                stored.remove(field.key());
                storedCredentialFields = Set.copyOf(stored);
                setConfigNotice(host.message("desktop.ui.config.secret-cleared"));
            } catch (Exception failure) {
                setConfigNotice(host.message(
                        "gui.config.dialog.save-failed.message",
                        safeMessage(failure)
                ));
            }
        });
    }

    void updateAutoStart(boolean enabled) {
        if (!autoStartSupported || enabled == autoStartEnabled) return;
        owner.runBusy(() -> {
            try {
                host.setAutoStartEnabled(enabled);
                autoStartEnabled = enabled;
                setConfigNotice(host.message(enabled ? "gui.config.autostart.notice.enabled" : "gui.config.autostart.notice.disabled"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn(
                        host.message(
                                "gui.config.log.autostart.apply-failed",
                                enabled,
                                safeMessage(interrupted)
                        ),
                        interrupted
                );
                setConfigNotice(host.message("desktop.ui.tools.operation-failed"));
            } catch (Exception failure) {
                LOG.warn(
                        host.message(
                                "gui.config.log.autostart.apply-failed",
                                enabled,
                                safeMessage(failure)
                        ),
                        failure
                );
                setConfigNotice(host.message("desktop.ui.tools.operation-failed"));
            }
        });
    }

    void openConfigFile() {
        owner.runBusy(() -> {
            try {
                host.openLocalPath(configPath);
            } catch (Exception failure) {
                LOG.warn(
                        host.message(
                                "gui.config.log.open-file-failed",
                                configPath,
                                safeMessage(failure)
                        ),
                        failure
                );
                owner.showDialog(
                        "config.open-failed",
                        "gui.dialog.error.title",
                        "desktop.ui.tools.operation-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            }
        });
    }

    void requestConfigurationReset() {
        owner.showDialog(
                "config.reset.dialog",
                "gui.config.dialog.reset-confirm.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "config.reset.content",
                        text(
                                "config.reset.message",
                                "gui.config.dialog.reset-confirm.message",
                                TextStyle.BODY
                        ),
                        row(
                                "config.reset.actions",
                                button(
                                        "config.reset.confirm",
                                        "config.reset.confirm",
                                        "gui.button.reset-defaults",
                                        true,
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            resetConfiguration();
                                        }
                                ),
                                button(
                                        "config.reset.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                480,
                0
        );
    }

    private void resetConfiguration() {
        for (ConfigField field : configFields) values.put(
                field.key(),
                field.spec().defaultValue()
        );
        setConfigNotice("");
        owner.rebuild();
    }

    void reloadConfiguration() {
        load();
        setConfigNotice("");
        owner.rebuild();
    }

    private Map<String, String> pendingInterfaceValues() {
        String language = form(
                "interface.language",
                selected("app.language", "follow-system")
        );
        if (!"follow-system".equals(language) && host.matchLocale(language).isEmpty())
            language = "follow-system";
        Set<String> availableProviders = owner.currentSources().stream().filter(source -> source.plugin() instanceof DesktopUiProvider).map(
                DesktopUiPluginSource::id).collect(java.util.stream.Collectors.toSet());
        String provider = form(
                "interface.provider",
                selected("app.gui-provider", "gui-swing")
        );
        if (!availableProviders.contains(provider)) provider = "gui-swing";
        Set<String> availableThemes = view.fields.themeOptions(provider).stream().map(DesktopUiNode.Option::id).collect(
                java.util.stream.Collectors.toSet());
        String theme = form("interface.theme", selected("app.theme", "system"));
        if (!availableThemes.contains(theme)) theme = "system";
        String expandAll = Boolean.toString(boolForm(
                "interface.config-menu-expand-all",
                Boolean.parseBoolean(selected("app.config-menu-expand-all", "false"))
        ));
        return Map.of(
                "app.language",
                language,
                "app.gui-provider",
                provider,
                "app.theme",
                theme,
                "app.config-menu-expand-all",
                expandAll
        );
    }

    private void applyLocale(String tag) {
        if (tag == null || tag.isBlank() || "follow-system".equals(tag)) host.detectSystemLocale();
        else host.matchLocale(tag).ifPresent(locale -> Locale.setDefault(locale.toLocale()));
        owner.rebuild();
    }

    synchronized void load() {
        loader.load();
    }

    String themePreference() {
        return selected("app.theme", "system");
    }

    DesktopUiHost.FfmpegProxy proxySettings() {
        try {
            Map<String, String> values = host.applicationConfig().readAll(List.of(
                    "proxy.enabled",
                    "proxy.host",
                    "proxy.port"
            ));
            boolean enabled = Boolean.parseBoolean(values.getOrDefault(
                    "proxy.enabled",
                    "false"
            ));
            String proxyHost = values.getOrDefault("proxy.host", host.defaultProxyHost());
            int proxyPort = parseInt(values.get("proxy.port"), host.defaultProxyPort());
            return new DesktopUiHost.FfmpegProxy(
                    enabled && !proxyHost.isBlank() && proxyPort > 0,
                    proxyHost,
                    proxyPort
            );
        } catch (Exception ignored) {
            return new DesktopUiHost.FfmpegProxy(false, "", 0);
        }
    }

    boolean visible(ConfigField field) {
        return (!"debug.enabled".equals(field.spec().key()) || debugUnlocked) && conditions(
                field,
                field.spec().visibleWhen()
        );
    }

    boolean enabled(ConfigField field) {
        return conditions(field, field.spec().enabledWhen());
    }

    private boolean conditions(
            ConfigField field,
            List<GuiConfigCondition> conditions
    ) {
        if (conditions == null) return true;
        for (GuiConfigCondition condition : conditions) {
            if (condition == null || condition.operator() == null) return false;
            String actual = values.getOrDefault(
                    new FieldKey(field.owner(), condition.key()),
                    ""
            );
            String expected = condition.value() == null ? "" : condition.value();
            boolean matches = switch (condition.operator()) {
                case TRUE -> Boolean.parseBoolean(actual);
                case FALSE -> !Boolean.parseBoolean(actual);
                case EQUALS -> actual.equals(expected);
                case NOT_EQUALS -> !actual.equals(expected);
                case BLANK -> actual.isBlank();
                case NOT_BLANK -> !actual.isBlank();
            };
            if (!matches) return false;
        }
        return true;
    }

    String selected(String key, String fallback) {
        return form(
                "interface." + switch (key) {
                    case "app.language" -> "language";
                    case "app.gui-provider" -> "provider";
                    case "app.theme" -> "theme";
                    case "app.config-menu-expand-all" -> "config-menu-expand-all";
                    default -> key;
                },
                savedValues.getOrDefault(new FieldKey(null, key), fallback)
        );
    }

    private String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    private boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }

    private void setConfigNotice(String value) {
        configNotice = nullToEmpty(value);
        configNoticeToken = null;
    }

    record PluginConfig(
            String owner,
            String namespace,
            String displayNameKey,
            List<GuiConfigContribution> contributions,
            List<WebRouteContribution> routes
    ) {
    }

    record LocalizedText(String namespace, String key, String fallback) {
        TextToken token() {
            return DesktopUiNodes.token(namespace, key, fallback);
        }

        static LocalizedText key(String namespace, String key) {
            return new LocalizedText(namespace, key, key);
        }

        static LocalizedText optional(String namespace, String key) {
            return key == null || key.isBlank() ? null : key(namespace, key);
        }

        static LocalizedText app(String key) {
            return key(null, key);
        }

        static LocalizedText raw(String text) {
            return new LocalizedText(null, "", text);
        }
    }

    record ConfigLayout(
            FieldKey field,
            String cardId,
            LocalizedText cardLabel,
            int order
    ) {
    }

    record ConfigNotice(
            String id,
            LocalizedText text,
            Set<String> cardIds,
            int order
    ) {
    }

    record ConfigAction(
            String owner,
            String namespace,
            GuiConfigActionContribution spec,
            LocalizedText label,
            LocalizedText help,
            LocalizedText sendingNotice,
            int readTimeoutMillis
    ) {
        String cardId() {
            return spec.cardId();
        }
    }

    record ConfigPreset(
            String owner,
            String namespace,
            GuiConfigPresetContribution spec,
            LocalizedText label,
            LocalizedText help
    ) {
        String cardId() {
            return spec.cardId();
        }
    }

    record ConfigSection(
            String id,
            GuiConfigGroupContribution group,
            GuiConfigSectionLayout layout,
            int order,
            boolean mergeable,
            boolean contributesGroupVisibility,
            LocalizedText title,
            LocalizedText help,
            LocalizedText layoutLabel,
            LocalizedText layoutHelp,
            LocalizedText presetLabel,
            LocalizedText presetHelp,
            List<ConfigLayout> layouts,
            List<ConfigAction> actions,
            List<ConfigPreset> presets,
            List<ConfigNotice> notices
    ) {
    }

    record FieldKey(String owner, String key) {
        FieldKey {
            key = key == null ? "" : key.trim();
            owner = owner == null || owner.isBlank() ? null : owner.trim();
        }
    }

    record ConfigField(
            FieldKey key,
            String owner,
            GuiConfigFieldContribution spec,
            GuiConfigGroupContribution group,
            String namespace,
            boolean affectsConditions
    ) {
    }
}
