package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ChoiceStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ToggleStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.guicompose.model.DesktopConfigurationController.*;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 配置字段、界面偏好与生效级别的节点构建。
 */
final class DesktopConfigurationFieldView {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfigurationFieldView.class);
    private static final String APP_OWNER = "app";

    private final DesktopConfigurationController model;
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> formValues;
    private final Map<FieldKey, String> values;

    DesktopConfigurationFieldView(DesktopConfigurationController model) {
        this.model = model;
        this.owner = model.owner;
        this.host = model.host;
        this.formValues = model.formValues;
        this.values = model.values;
    }

    DesktopUiNode interfaceSettings() {
        List<DesktopUiNode.Option> locales = new ArrayList<>();
        locales.add(new DesktopUiNode.Option(
                "follow-system",
                key("gui.interface.language.option.follow-system"),
                true
        ));
        host.visibleLocales().forEach(locale -> locales.add(new DesktopUiNode.Option(
                safeId(locale.tag()),
                TextToken.raw(locale.nativeName()),
                true
        )));

        List<DesktopUiNode.Option> providers = new ArrayList<>();
        for (DesktopUiPluginSnapshot source : owner.currentSources().stream().sorted(Comparator.comparing(
                DesktopUiPluginSnapshot::id)).toList()) {
            try {
                if (source.desktopUiProvider() && validId(source.id())) {
                    providers.add(new DesktopUiNode.Option(
                            source.id(),
                            pluginToken(source, source.displayNameKey(), source.id()),
                            true
                    ));
                }
            } catch (RuntimeException ignored) {
                // 单个无效的可选 provider 不会移除设置页。
            }
        }

        String selectedProvider = model.selectedProvider();
        List<DesktopUiNode.Option> themes = themeOptions(selectedProvider);
        String configuredTheme = selected("app.theme", "system");
        String selectedTheme = themes.stream().anyMatch(theme -> theme.id().equals(configuredTheme)) ? configuredTheme : "system";

        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(formField(
                "interface.language",
                key("gui.interface.language.label"),
                key("gui.interface.language.help"),
                choice(
                        "interface.language.input",
                        "interface.language",
                        "gui.interface.language.label",
                        null,
                        locales,
                        selected("app.language", "follow-system"),
                        true
                ),
                GuiConfigEffect.HOT_RELOAD
        ));
        nodes.add(formField(
                "interface.provider",
                key("gui.interface.provider.label"),
                key("gui.interface.provider.help"),
                choice(
                        "interface.provider.input",
                        "interface.provider",
                        "gui.interface.provider.label",
                        null,
                        providers,
                        selectedProvider,
                        !providers.isEmpty()
                ),
                GuiConfigEffect.PROCESS_RESTART
        ));
        nodes.add(formField(
                "interface.theme",
                key("gui.interface.theme.label"),
                key("gui.interface.theme.help"),
                choice(
                        "interface.theme.input",
                        "interface.theme",
                        "gui.interface.theme.label",
                        null,
                        themes,
                        selectedTheme,
                        true
                ),
                GuiConfigEffect.HOT_RELOAD
        ));
        nodes.add(formField(
                "interface.config-menu-expand-all",
                key("gui.interface.config-menu-expand-all.label"),
                key("gui.interface.config-menu-expand-all.help"),
                new DesktopUiNode.Toggle(
                        "interface.config-menu-expand-all.input",
                        "interface.config-menu-expand-all",
                        key("gui.interface.config-menu-expand-all.label"),
                        null,
                        ToggleStyle.CHECKBOX,
                        boolForm(
                                "interface.config-menu-expand-all",
                                Boolean.parseBoolean(selected(
                                        "app.config-menu-expand-all",
                                        "false"
                                ))
                        ),
                        true
                ),
                GuiConfigEffect.HOT_RELOAD
        ));
        return scroll(
                "interface.scroll",
                new DesktopUiNode.Surface(
                        "interface.padding",
                        DesktopUiNode.SurfaceStyle.PLAIN,
                        DesktopUiNode.Insets.all(16),
                        true,
                        column("interface.content", nodes)
                )
        );
    }

    List<DesktopUiNode.Option> themeOptions(String providerId) {
        Map<String, DesktopUiNode.Option> themes = new LinkedHashMap<>();
        themes.put(
                "system",
                new DesktopUiNode.Option(
                        "system",
                        key("gui.interface.theme.option.system"),
                        true
                )
        );
        themes.put(
                "light",
                new DesktopUiNode.Option("light", key("gui.interface.theme.option.light"), true)
        );
        themes.put(
                "dark",
                new DesktopUiNode.Option("dark", key("gui.interface.theme.option.dark"), true)
        );
        DesktopUiPluginSnapshot source = owner.currentSources().stream().filter(candidate -> candidate.id().equals(
                providerId)).filter(candidate -> candidate.desktopUiProvider()).findFirst().orElse(
                null);
        if (source == null) return List.copyOf(themes.values());
        try {
            List<GuiThemeContribution> contributions = source.themes();
            if (contributions == null) return List.copyOf(themes.values());
            Set<String> providerThemeIds = new LinkedHashSet<>();
            for (GuiThemeContribution contribution : contributions) {
                if (contribution == null || !validId(contribution.themeId()) || Set.of(
                        "system",
                        "light",
                        "dark"
                ).contains(contribution.themeId())) continue;
                String id = contribution.themeId();
                if (!providerThemeIds.add(id)) {
                    themes.remove(id);
                    continue;
                }
                themes.put(
                        id,
                        new DesktopUiNode.Option(
                                id,
                                TextToken.raw(contribution.displayName(Locale.getDefault())),
                                true
                        )
                );
            }
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Ignored invalid desktop UI themes from provider '{}': {}",
                    providerId,
                    failure.toString()
            );
        }
        return List.copyOf(themes.values());
    }

    DesktopUiNode configFieldNode(
            ConfigField field,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        String binding = bindingId(field.key());
        GuiConfigFieldContribution spec = field.spec();
        String value = values.getOrDefault(field.key(), spec.defaultValue());
        TextToken label = token(field.namespace(), spec.labelKey(), spec.key());
        TextToken help = optionalToken(field.namespace(), spec.helpKey());
        boolean enabled = model.enabled(field) && !locked.contains(field.key());
        String nodeId = binding + ".input";
        DesktopUiNode node = switch (spec.type()) {
            case BOOL -> new DesktopUiNode.Toggle(
                    nodeId,
                    binding,
                    label,
                    help,
                    ToggleStyle.CHECKBOX,
                    Boolean.parseBoolean(value),
                    enabled
            );
            case INT, PORT -> new DesktopUiNode.TextInput(
                    nodeId,
                    binding,
                    label,
                    null,
                    InputKind.NUMBER,
                    value,
                    12,
                    1,
                    enabled
            );
            case TIME -> new DesktopUiNode.TextInput(
                    nodeId,
                    binding,
                    label,
                    help,
                    InputKind.TIME,
                    value,
                    5,
                    1,
                    enabled
            );
            case ENUM -> {
                List<DesktopUiNode.Option> options = new ArrayList<>();
                for (int index = 0; index < spec.enumValues().size(); index++) {
                    String option = spec.enumValues().get(index);
                    options.add(new DesktopUiNode.Option(
                            "option." + index,
                            enumToken(field, option),
                            true
                    ));
                }
                int selectedIndex = spec.enumValues().indexOf(value);
                nextSelections.put(
                        binding,
                        selectedIds -> {
                            String selectedId = first(selectedIds);
                            int index = selectedId.startsWith("option.") ? parseInt(
                                    selectedId.substring("option.".length()),
                                    -1
                            ) : -1;
                            if (index >= 0 && index < spec.enumValues().size()) {
                                values.put(field.key(), spec.enumValues().get(index));
                                if (field.affectsConditions()) owner.rebuild();
                            }
                        }
                );
                yield new DesktopUiNode.Choice(
                        nodeId,
                        binding,
                        label,
                        help,
                        ChoiceStyle.COMBO_BOX,
                        SelectionMode.SINGLE,
                        options,
                        selectedIndex < 0 ? List.of() : List.of("option." + selectedIndex),
                        enabled
                );
            }
            case PATH_DIR, PATH_FILE, STRING, PASSWORD -> new DesktopUiNode.TextInput(
                    nodeId,
                    binding,
                    label,
                    help,
                    switch (spec.type()) {
                case PATH_DIR -> InputKind.DIRECTORY;
                case PATH_FILE -> InputKind.FILE;
                case PASSWORD -> InputKind.PASSWORD;
                default -> InputKind.TEXT;
            },
                    spec.sensitive() ? "" : value,
                    32,
                    1,
                    enabled
            );
        };
        if (spec.type() != GuiConfigFieldType.ENUM) nextBindings.put(binding, field);
        if (spec.sensitive() && field.owner() != null) {
            String actionId = binding + ".clear";
            node = new DesktopUiNode.Dock(
                    binding + ".credential",
                    4,
                    null,
                    node,
                    text(
                            binding + ".credential-status",
                            model.storedCredentialFields.contains(field.key()) ? "gui.credential.status.saved" : "gui.credential.status.not-saved",
                            TextStyle.CAPTION
                    ),
                    null,
                    button(
                            binding + ".clear.button",
                            actionId,
                            "desktop.ui.config.clear-secret",
                            !owner.busy(),
                            nextActions,
                            () -> model.clearCredential(field)
                    )
            );
        }
        return formField(
                binding,
                label,
                help,
                node,
                spec.effect()
        );
    }

    static DesktopUiNode.Form formField(
            String id,
            TextToken label,
            TextToken help,
            DesktopUiNode field,
            GuiConfigEffect effect
    ) {
        return new DesktopUiNode.Form(
                id + ".form",
                DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"),
                List.of(new DesktopUiNode.FormRow(
                        id + ".row",
                        label,
                        help,
                        field,
                        effectNode(id, effect)
                ))
        );
    }

    static DesktopUiNode.Text effectNode(String id, GuiConfigEffect effect) {
        String key = switch (effect) {
            case HOT_RELOAD -> "gui.label.hot-reload";
            case BACKEND_RESTART -> "gui.label.restart-required";
            case PROCESS_RESTART -> "gui.label.process-restart-required";
        };
        TextStyle style = switch (effect) {
            case HOT_RELOAD -> TextStyle.SUCCESS;
            case BACKEND_RESTART -> TextStyle.WARNING;
            case PROCESS_RESTART -> TextStyle.ERROR;
        };
        return new DesktopUiNode.Text(
                id + ".effect",
                key(key),
                style,
                false,
                false
        );
    }

    ConfigField field(FieldKey key) {
        return model.configFields.stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElse(
                null);
    }

    Set<FieldKey> lockedFields() {
        Set<FieldKey> locked = new LinkedHashSet<>();
        for (ConfigSection section : model.configSections) {
            Map<String, List<ConfigPreset>> groups = new LinkedHashMap<>();
            for (ConfigPreset preset : section.presets()) {
                String card = section.layout() == GuiConfigSectionLayout.CARD_SWITCHER ? nullToEmpty(
                        preset.cardId()) : "";
                groups.computeIfAbsent(card, ignored -> new ArrayList<>()).add(preset);
            }
            for (List<ConfigPreset> presets : groups.values()) {
                ConfigPreset selected = selectedPreset(presets);
                if (selected != null) {
                    selected.spec().lockedFieldKeys().stream().map(key -> new FieldKey(
                            selected.owner(),
                            key
                    )).forEach(locked::add);
                }
            }
        }
        return Set.copyOf(locked);
    }

    ConfigPreset selectedPreset(List<ConfigPreset> presets) {
        ConfigPreset fallback = null;
        for (ConfigPreset preset : presets) {
            if (preset.spec().values().isEmpty() && fallback == null) fallback = preset;
            if (preset.spec().matchFieldKey() == null) continue;
            String actual = values.getOrDefault(
                    new FieldKey(
                            preset.owner(),
                            preset.spec().matchFieldKey()
                    ),
                    ""
            );
            String expected = preset.spec().matchValue();
            boolean matches = switch (preset.spec().matchMode()) {
                case EQUALS_IGNORE_CASE -> actual.equalsIgnoreCase(expected);
                case TRIMMED_TRAILING_SLASH_IGNORE_CASE ->
                        trimTrailingSlashes(actual).equalsIgnoreCase(trimTrailingSlashes(expected));
            };
            if (matches) return preset;
        }
        return fallback;
    }

    private TextToken enumToken(ConfigField field, String option) {
        String labelKey = field.spec().enumValueLabelKeys().get(option);
        return labelKey == null ? TextToken.raw(option) : token(
                field.namespace(),
                labelKey,
                option
        );
    }

    private static TextToken pluginToken(
            DesktopUiPluginSnapshot source,
            String key,
            String fallback
    ) {
        return token(source.displayNamespace(), key, fallback);
    }

    private static String trimTrailingSlashes(String value) {
        String result = nullToEmpty(value).trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    String selected(String key, String fallback) {
        return model.selected(key, fallback);
    }

    String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }

    static TextToken optionalToken(String namespace, String key) {
        return key == null || key.isBlank() ? null : token(namespace, key, key);
    }

    static String bindingId(FieldKey key) {
        return "config." + safeId(key.owner() == null ? APP_OWNER : key.owner()) + "." + safeId(key.key());
    }

    static String presetOptionId(ConfigPreset preset) {
        return "preset." + safeId(preset.owner()) + "." + safeId(preset.spec().presetId());
    }

    static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : nullToEmpty(values.get(0));
    }

    static DesktopUiNode.FormRow formRow(
            String id,
            String labelKey,
            String helpKey,
            DesktopUiNode content
    ) {
        return new DesktopUiNode.FormRow(
                id,
                key(labelKey),
                helpKey == null ? null : key(helpKey),
                content,
                null
        );
    }

    static DesktopUiNode.Choice choice(
            String id,
            String binding,
            String label,
            String help,
            List<DesktopUiNode.Option> options,
            String selected,
            boolean enabled
    ) {
        List<String> selectedIds = options.stream().anyMatch(option -> option.id().equals(selected)) ? List.of(
                selected) : List.of();
        return new DesktopUiNode.Choice(
                id,
                binding,
                key(label),
                help == null ? null : key(help),
                ChoiceStyle.COMBO_BOX,
                SelectionMode.SINGLE,
                options,
                selectedIds,
                enabled
        );
    }
}
