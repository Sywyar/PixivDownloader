package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.AppDesktopUiModel.RendererContract;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ChoiceStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ToggleStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.responseDetail;
import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.safeJsonPath;
import static top.sywyar.pixivdownload.gui.GuiActionResponseSafety.sanitizeActionText;
import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.gui.DesktopConfigurationController.*;
import static top.sywyar.pixivdownload.gui.DesktopConfigurationFieldView.*;

/**
 * 配置页节点树与交互绑定构建。
 */
final class DesktopConfigurationView {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfigurationView.class);
    private static final String APP_OWNER = "app";

    private final DesktopConfigurationController model;
    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final RendererContract rendererContract;
    private final Map<String, String> formValues;
    private final DesktopRepositorySettingsController repositories;
    private final Map<FieldKey, String> values;
    private final Map<FieldKey, String> savedValues;
    final DesktopConfigurationFieldView fields;

    DesktopConfigurationView(DesktopConfigurationController model) {
        this.model = model;
        this.owner = model.owner;
        this.host = model.host;
        this.rendererContract = model.rendererContract;
        this.formValues = model.formValues;
        this.repositories = model.repositories;
        this.values = model.values;
        this.savedValues = model.savedValues;
        this.fields = new DesktopConfigurationFieldView(model);
    }

    DesktopUiNode classicPage(
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        Map<String, ConfigField> nextBindings = new LinkedHashMap<>();
        DesktopUiNode result = configPage(nextBindings, nextSelections, nextActions);
        model.fieldBindings = Map.copyOf(nextBindings);
        return result;
    }

    DesktopUiNode controlCenterPage(
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        Map<String, ConfigField> nextBindings = new LinkedHashMap<>();
        DesktopUiNode result = controlCenterConfigPage(
                nextBindings,
                nextSelections,
                nextActions
        );
        model.fieldBindings = Map.copyOf(nextBindings);
        return result;
    }

    private DesktopUiNode configPage(
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode.Tab> tabs = configTabs(
                nextBindings,
                nextSelections,
                nextActions
        );
        List<DesktopUiNode> bottom = configFooterNodes(false, nextActions);
        return new DesktopUiNode.Dock(
                "config.root",
                0,
                null,
                new DesktopUiNode.Tabs("config.tabs", tabs),
                new DesktopUiNode.Surface(
                        "config.bottom",
                        DesktopUiNode.SurfaceStyle.PLAIN,
                        DesktopUiNode.Insets.all(8),
                        true,
                        column("config.bottom.content", bottom)
                ),
                null,
                null
        );
    }

    private DesktopUiNode controlCenterConfigPage(
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode.Tab> tabs = configTabs(
                nextBindings,
                nextSelections,
                nextActions
        );
        String selectedId = formValues.getOrDefault(
                "settings.category",
                tabs.get(0).id()
        );
        DesktopUiNode.Tab selected = tabs.stream().filter(tab -> tab.id().equals(selectedId)).findFirst().orElse(
                tabs.get(0));
        DesktopUiNode categories = new DesktopUiNode.Surface(
                "settings.categories.surface",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(14),
                true,
                column(
                        "settings.categories.content",
                        text(
                                "settings.categories.title",
                                "desktop.ui.settings.categories.title",
                                TextStyle.HEADING
                        ),
                        new DesktopUiNode.Tree(
                                "settings.categories",
                                "settings.category",
                                tabs.stream().map(tab -> new DesktopUiNode.TreeItem(
                                        tab.id(),
                                        tab.title(),
                                        List.of()
                                )).toList(),
                                SelectionMode.SINGLE,
                                List.of(selected.id()),
                                true
                        )
                )
        );
        DesktopUiNode content = new DesktopUiNode.Surface(
                "settings.content",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(14),
                true,
                selected.content() instanceof DesktopUiNode.Scroll scroll ? scroll.content() : selected.content()
        );
        DesktopUiNode summary = new DesktopUiNode.Surface(
                "settings.summary",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(14),
                true,
                column("settings.summary.content", configFooterNodes(true, nextActions))
        );
        return scroll(
                "settings.scroll",
                column(
                        "settings.root",
                        new DesktopUiNode.AdaptiveGrid(
                                "settings.layout",
                                280,
                                2,
                                16,
                                16,
                                List.of(column("settings.sidebar", categories, summary), content)
                        )
                )
        );
    }

    private List<DesktopUiNode.Tab> configTabs(
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        Map<String, GuiConfigGroupContribution> groups = new LinkedHashMap<>();
        DesktopCoreConfigCatalog.groups().forEach(group -> groups.put(
                group.groupId(),
                group
        ));
        for (ConfigField field : model.configFields) {
            if (field.group() != null) groups.putIfAbsent(
                    field.group().groupId(),
                    field.group()
            );
        }
        for (ConfigSection section : model.configSections)
            groups.putIfAbsent(section.group().groupId(), section.group());

        Set<FieldKey> claimed = model.configSections.stream().flatMap(section -> section.layouts().stream()).map(
                ConfigLayout::field).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> visibleGroups = new LinkedHashSet<>();
        model.configFields.stream().filter(field -> field.spec().contributesGroupVisibility()).map(
                field -> field.spec().groupId()).forEach(visibleGroups::add);
        model.configSections.stream().filter(ConfigSection::contributesGroupVisibility).map(section -> section.group().groupId()).forEach(
                visibleGroups::add);
        Set<FieldKey> locked = fields.lockedFields();
        Set<FieldKey> rendered = new LinkedHashSet<>();
        List<GuiConfigGroupContribution> orderedGroups = groups.values().stream().filter(group -> !"interface".equals(
                group.groupId())).filter(group -> visibleGroups.contains(group.groupId())).sorted(
                Comparator.comparingInt(GuiConfigGroupContribution::order).thenComparing(
                        GuiConfigGroupContribution::groupId)).toList();

        List<DesktopUiNode.Tab> tabs = new ArrayList<>();
        tabs.add(new DesktopUiNode.Tab(
                "interface",
                key("gui.config.category.interface"),
                fields.interfaceSettings()
        ));
        if (fields.boolForm(
                "interface.config-menu-expand-all",
                Boolean.parseBoolean(fields.selected("app.config-menu-expand-all", "false"))
        )) {
            for (GuiConfigGroupContribution group : orderedGroups) {
                List<DesktopUiNode> nodes = configGroupNodes(
                        group,
                        claimed,
                        rendered,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                );
                if (!nodes.isEmpty()) tabs.add(configGroupTab(group, nodes));
            }
        } else {
            addConfigCategory(
                    tabs,
                    "download",
                    "gui.config.group.download",
                    orderedGroups,
                    Set.of(GuiConfigGroups.DOWNLOAD),
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            addConfigCategory(
                    tabs,
                    "runtime-network",
                    "gui.config.category.runtime-network",
                    orderedGroups,
                    Set.of(
                            GuiConfigGroups.SERVER,
                            GuiConfigGroups.PROXY,
                            GuiConfigGroups.HTTPS,
                            GuiConfigGroups.UPDATE
                    ),
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            addConfigCategory(
                    tabs,
                    "access-control",
                    "gui.config.category.access-control",
                    orderedGroups,
                    Set.of(GuiConfigGroups.GUEST_INVITE, GuiConfigGroups.SECURITY),
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            addConfigCategory(
                    tabs,
                    "automation-maintenance",
                    "gui.config.category.automation-maintenance",
                    orderedGroups,
                    Set.of(GuiConfigGroups.SCHEDULE, GuiConfigGroups.MAINTENANCE),
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            Set<String> remaining = orderedGroups.stream().map(GuiConfigGroupContribution::groupId).filter(
                    id -> !renderedGroup(id)).collect(java.util.stream.Collectors.toCollection(
                    LinkedHashSet::new));
            remaining.add(GuiConfigGroups.PLUGINS);
            addPluginConfigCategory(
                    tabs,
                    orderedGroups,
                    remaining,
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
        }
        return List.copyOf(tabs);
    }

    private List<DesktopUiNode> configFooterNodes(
            boolean controlCenter,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode> bottom = new ArrayList<>();
        int pendingChanges = model.pendingConfigurationChangeCount();
        if (controlCenter) {
            bottom.add(new DesktopUiNode.Text(
                    "settings.unsaved-count",
                    appToken("gui.config.notice.unsaved-count", pendingChanges),
                    TextStyle.CAPTION,
                    true,
                    false
            ));
            if (pendingChanges > 0)
                bottom.add(effectNode("settings.impact", model.pendingConfigurationEffect()));
        }
        bottom.add(row(
                "config.actions",
                button(
                        "config.open",
                        "config.open",
                        "gui.button.open-config",
                        !owner.busy(),
                        nextActions,
                        model::openConfigFile
                ),
                button(
                        "config.save",
                        "config.save",
                        "gui.button.save",
                        !owner.busy(),
                        nextActions,
                        model::saveConfiguration
                ),
                button(
                        "config.reset",
                        "config.reset",
                        "gui.button.reset-defaults",
                        !owner.busy(),
                        nextActions,
                        model::requestConfigurationReset
                ),
                button(
                        "config.reload",
                        "config.reload",
                        "desktop.ui.action.reload",
                        !owner.busy(),
                        nextActions,
                        model::reloadConfiguration
                )
        ));
        if (model.configNoticeToken != null) {
            bottom.add(new DesktopUiNode.Text(
                    "config.notice.plugin",
                    model.configNoticeToken,
                    TextStyle.CAPTION,
                    true,
                    true
            ));
        } else if (!model.configNotice.isBlank()) {
            bottom.add(status("config.notice", model.configNotice));
        }
        return List.copyOf(bottom);
    }

    private static boolean renderedGroup(String id) {
        return Set.of(
                GuiConfigGroups.DOWNLOAD,
                GuiConfigGroups.SERVER,
                GuiConfigGroups.PROXY,
                GuiConfigGroups.HTTPS,
                GuiConfigGroups.UPDATE,
                GuiConfigGroups.GUEST_INVITE,
                GuiConfigGroups.SECURITY,
                GuiConfigGroups.SCHEDULE,
                GuiConfigGroups.MAINTENANCE
        ).contains(id);
    }

    private void addConfigCategory(
            List<DesktopUiNode.Tab> tabs,
            String id,
            String label,
            List<GuiConfigGroupContribution> groups,
            Set<String> groupIds,
            Set<FieldKey> claimed,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode> content = new ArrayList<>();
        for (GuiConfigGroupContribution group : groups) {
            if (!groupIds.contains(group.groupId())) continue;
            List<DesktopUiNode> nodes = configGroupNodes(
                    group,
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            if (!nodes.isEmpty()) content.add(new DesktopUiNode.Group(
                    "config.category." + id + "." + safeId(group.groupId()),
                    token(group.i18nNamespace(), group.labelKey(), group.groupId()),
                    new DesktopUiNode.Container(
                            "config.category." + id + "." + safeId(group.groupId()) + ".content",
                            ContainerLayout.COLUMN,
                            1,
                            0,
                            Alignment.STRETCH,
                            nodes
                    )
            ));
        }
        if (!content.isEmpty()) tabs.add(new DesktopUiNode.Tab(
                id,
                key(label),
                scroll(
                        "config.category." + id + ".scroll",
                        new DesktopUiNode.Surface(
                                "config.category." + id + ".padding",
                                DesktopUiNode.SurfaceStyle.PLAIN,
                                DesktopUiNode.Insets.all(16),
                                true,
                                column("config.category." + id + ".content", content)
                        )
                )
        ));
    }

    private DesktopUiNode.Tab configGroupTab(
            GuiConfigGroupContribution group,
            List<DesktopUiNode> nodes
    ) {
        String id = "config." + safeId(group.groupId());
        return new DesktopUiNode.Tab(
                id,
                token(group.i18nNamespace(), group.labelKey(), group.groupId()),
                configGroupContent(id, nodes)
        );
    }

    private void addPluginConfigCategory(
            List<DesktopUiNode.Tab> tabs,
            List<GuiConfigGroupContribution> groups,
            Set<String> groupIds,
            Set<FieldKey> claimed,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode.Tab> scopes = new ArrayList<>();
        List<DesktopUiNode.Tab> pluginTabs = new ArrayList<>();
        for (GuiConfigGroupContribution group : groups) {
            if (!groupIds.contains(group.groupId())) continue;
            List<DesktopUiNode> nodes = configGroupNodes(
                    group,
                    claimed,
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            );
            if (nodes.isEmpty()) continue;
            if (GuiConfigGroups.PLUGINS.equals(group.groupId())) {
                scopes.add(new DesktopUiNode.Tab(
                        "plugin-market-settings",
                        key("gui.config.scope.plugin-market-settings"),
                        configGroupContent("config.category.plugins.market", nodes)
                ));
            } else {
                pluginTabs.add(configGroupTab(group, nodes));
            }
        }
        DesktopUiNode pluginSettings = pluginTabs.isEmpty() ? text(
                "config.category.plugins.settings.empty",
                "gui.config.scope.plugins.empty",
                TextStyle.BODY
        ) : new DesktopUiNode.Tabs("config.category.plugins.settings.tabs", pluginTabs);
        scopes.add(new DesktopUiNode.Tab(
                "plugin-settings",
                key("gui.config.scope.plugins"),
                pluginSettings
        ));
        tabs.add(new DesktopUiNode.Tab(
                "plugins",
                key("gui.config.group.plugins"),
                new DesktopUiNode.Tabs("config.category.plugins.scopes", scopes)
        ));
    }

    private static DesktopUiNode configGroupContent(
            String id,
            List<DesktopUiNode> nodes
    ) {
        return scroll(
                id + ".scroll",
                new DesktopUiNode.Surface(
                        id + ".padding",
                        DesktopUiNode.SurfaceStyle.PLAIN,
                        DesktopUiNode.Insets.all(16),
                        true,
                        new DesktopUiNode.Container(
                                id + ".content",
                                ContainerLayout.COLUMN,
                                1,
                                0,
                                Alignment.STRETCH,
                                nodes
                        )
                )
        );
    }

    private List<DesktopUiNode> configGroupNodes(
            GuiConfigGroupContribution group,
            Set<FieldKey> claimed,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        model.configSections.stream().filter(section -> group.groupId().equals(section.group().groupId())).sorted(
                Comparator.comparingInt(ConfigSection::order).thenComparing(ConfigSection::id)).map(
                section -> configSectionNode(
                        section,
                        rendered,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                )).forEach(nodes::add);
        model.configFields.stream().filter(field -> group.groupId().equals(field.spec().groupId())).filter(
                field -> !claimed.contains(field.key())).filter(model::visible).sorted(Comparator.comparingInt(
                field -> field.spec().order())).filter(field -> rendered.add(field.key())).map(field -> fields.configFieldNode(
                        field,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                )).forEach(nodes::add);
        if (GuiConfigGroups.SERVER.equals(group.groupId())) {
            String binding = "config.autostart";
            String helpKey = model.autoStartSupported ? "gui.config.field.autostart.help" : "gui.config.field.autostart.unsupported.help";
            nextSelections.put(
                    binding,
                    values -> model.updateAutoStart(Boolean.parseBoolean(first(values)))
            );
            nodes.add(new DesktopUiNode.Form(
                    "config.autostart.form",
                    DesktopUiNode.FormStyle.RESPONSIVE,
                    key("gui.punctuation.colon"),
                    List.of(new DesktopUiNode.FormRow(
                            "config.autostart.row",
                            key("gui.config.field.autostart.label"),
                            key(helpKey),
                            new DesktopUiNode.Toggle(
                                    "config.autostart.input",
                                    binding,
                                    key("gui.config.field.autostart.label"),
                                    key(helpKey),
                                    ToggleStyle.CHECKBOX,
                                    model.autoStartEnabled,
                                    model.autoStartSupported && !owner.busy()
                            ),
                            null
                    ))
            ));
        }
        if (GuiConfigGroups.PLUGINS.equals(group.groupId())) {
            nodes.add(repositories.section(nextActions));
        }
        return nodes;
    }

    private DesktopUiNode configSectionNode(
            ConfigSection section,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        String base = "config.section." + safeId(section.id());
        List<DesktopUiNode> nodes = new ArrayList<>();
        if (section.layout() != GuiConfigSectionLayout.CARD_SWITCHER) {
            nodes.addAll(configNoticeNodes(base, section.notices(), null));
        }
        if (section.title() != null) {
            nodes.add(new DesktopUiNode.Text(
                    base + ".title",
                    section.title().token(),
                    TextStyle.HEADING,
                    true,
                    true
            ));
        }
        if (section.help() != null) {
            nodes.add(new DesktopUiNode.Text(
                    base + ".help",
                    section.help().token(),
                    TextStyle.BODY,
                    true,
                    true
            ));
        }
        if (section.layout() == GuiConfigSectionLayout.CARD_SWITCHER) {
            Map<String, List<ConfigLayout>> cards = new LinkedHashMap<>();
            section.layouts().stream().filter(layout -> layout.cardId() != null).forEach(layout -> cards.computeIfAbsent(
                    layout.cardId(),
                    ignored -> new ArrayList<>()
            ).add(layout));
            if (!cards.isEmpty()) {
                nodes.addAll(presetNodes(
                        base,
                        section,
                        null,
                        section.presets().stream().filter(preset -> preset.cardId() == null).toList(),
                        nextSelections
                ));
                String binding = base + ".card.selection";
                String selectedCard = fields.form(binding, cards.keySet().iterator().next());
                if (!cards.containsKey(selectedCard))
                    selectedCard = cards.keySet().iterator().next();
                List<DesktopUiNode.Option> options = new ArrayList<>();
                for (Map.Entry<String, List<ConfigLayout>> entry : cards.entrySet()) {
                    String cardId = entry.getKey();
                    LocalizedText label = entry.getValue().stream().map(ConfigLayout::cardLabel).filter(
                            Objects::nonNull).findFirst().orElse(LocalizedText.raw(cardId));
                    options.add(new DesktopUiNode.Option(cardId, label.token(), true));
                }
                nextSelections.put(
                        binding,
                        values -> {
                            String value = first(values);
                            if (cards.containsKey(value)) {
                                formValues.put(binding, value);
                                owner.rebuild();
                            }
                        }
                );
                TextToken layoutLabel = section.layoutLabel() == null ? key(
                        "gui.config.section.card.label") : section.layoutLabel().token();
                TextToken layoutHelp = section.layoutHelp() == null ? key(
                        "gui.config.section.card.help") : section.layoutHelp().token();
                nodes.add(new DesktopUiNode.Form(
                        base + ".card.selector.form",
                        DesktopUiNode.FormStyle.RESPONSIVE,
                        key("gui.punctuation.colon"),
                        List.of(new DesktopUiNode.FormRow(
                                base + ".card.selector.row",
                                layoutLabel,
                                layoutHelp,
                                new DesktopUiNode.Choice(
                                        base + ".card.selector",
                                        binding,
                                        layoutLabel,
                                        layoutHelp,
                                        ChoiceStyle.COMBO_BOX,
                                        SelectionMode.SINGLE,
                                        options,
                                        List.of(selectedCard),
                                        !owner.busy()
                                ),
                                null
                        ))
                ));
                nodes.addAll(configNoticeNodes(base, section.notices(), selectedCard));
                nodes.addAll(sectionContent(
                        section,
                        selectedCard,
                        cards.get(selectedCard),
                        rendered,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                ));
                nodes.addAll(actionNodes(
                        base,
                        section.actions().stream().filter(action -> action.cardId() == null).toList(),
                        nextActions
                ));
            } else {
                nodes.addAll(configNoticeNodes(base, section.notices(), null));
                nodes.addAll(sectionContent(
                        section,
                        null,
                        section.layouts(),
                        rendered,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                ));
            }
        } else if (section.layout() == GuiConfigSectionLayout.COMPACT_GRID) {
            nodes.addAll(presetNodes(
                    base,
                    section,
                    null,
                    section.presets().stream().filter(preset -> preset.cardId() == null).toList(),
                    nextSelections
            ));
            List<DesktopUiNode> compact = new ArrayList<>();
            List<DesktopUiNode> normal = new ArrayList<>();
            List<GuiConfigEffect> compactEffects = new ArrayList<>();
            for (ConfigLayout layout : section.layouts()) {
                ConfigField field = fields.field(layout.field());
                if (field == null || !model.visible(field) || !rendered.add(field.key())) continue;
                if (field.spec().type() == GuiConfigFieldType.BOOL) {
                    String binding = bindingId(field.key());
                    nextBindings.put(binding, field);
                    compact.add(new DesktopUiNode.Toggle(
                            binding + ".input",
                            binding,
                            token(field.namespace(), field.spec().labelKey(), field.spec().key()),
                            optionalToken(field.namespace(), field.spec().helpKey()),
                            ToggleStyle.CHECKBOX,
                            Boolean.parseBoolean(values.getOrDefault(
                                    field.key(),
                                    field.spec().defaultValue()
                            )),
                            model.enabled(field) && !locked.contains(field.key())
                    ));
                    compactEffects.add(field.spec().effect());
                } else {
                    normal.add(fields.configFieldNode(
                            field,
                            locked,
                            nextBindings,
                            nextSelections,
                            nextActions
                    ));
                }
            }
            if (!compact.isEmpty()) {
                TextToken layoutLabel = section.layoutLabel() == null ? key(
                        "gui.config.section.compact.label") : section.layoutLabel().token();
                TextToken layoutHelp = section.layoutHelp() == null ? key(
                        "gui.config.section.compact.help") : section.layoutHelp().token();
                nodes.add(new DesktopUiNode.Form(
                        base + ".grid.form",
                        DesktopUiNode.FormStyle.RESPONSIVE,
                        key("gui.punctuation.colon"),
                        List.of(new DesktopUiNode.FormRow(
                                base + ".grid.row",
                                layoutLabel,
                                layoutHelp,
                                new DesktopUiNode.Container(
                                        base + ".grid",
                                        ContainerLayout.GRID,
                                        2,
                                        8,
                                        Alignment.START,
                                        compact
                                ),
                                effectNode(base + ".grid", strongestEffect(compactEffects))
                        ))
                ));
            }
            nodes.addAll(normal);
            nodes.addAll(actionNodes(
                    base,
                    section.actions().stream().filter(action -> action.cardId() == null).toList(),
                    nextActions
            ));
        } else {
            nodes.addAll(sectionContent(
                    section,
                    null,
                    section.layouts(),
                    rendered,
                    locked,
                    nextBindings,
                    nextSelections,
                    nextActions
            ));
        }
        return column(base, nodes);
    }

    private static List<DesktopUiNode> configNoticeNodes(
            String base,
            List<ConfigNotice> notices,
            String selectedCard
    ) {
        return notices.stream().filter(notice -> notice.cardIds().isEmpty() || notice.cardIds().contains(
                selectedCard)).map(notice -> (DesktopUiNode) new DesktopUiNode.Text(
                        base + ".notice." + safeId(notice.id()),
                        notice.text().token(),
                        TextStyle.CAPTION,
                        true,
                        true
                )).toList();
    }

    private static GuiConfigEffect strongestEffect(List<GuiConfigEffect> effects) {
        if (effects.contains(GuiConfigEffect.PROCESS_RESTART))
            return GuiConfigEffect.PROCESS_RESTART;
        if (effects.contains(GuiConfigEffect.BACKEND_RESTART))
            return GuiConfigEffect.BACKEND_RESTART;
        return GuiConfigEffect.HOT_RELOAD;
    }

    private List<DesktopUiNode> sectionContent(
            ConfigSection section,
            String cardId,
            List<ConfigLayout> layouts,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> nextBindings,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        String base = "config.section." + safeId(section.id()) + (cardId == null ? "" : ".card." + safeId(
                cardId));
        List<DesktopUiNode> nodes = new ArrayList<>();
        List<ConfigPreset> presets = section.presets().stream().filter(preset -> Objects.equals(
                cardId,
                preset.cardId()
        )).toList();
        nodes.addAll(presetNodes(
                base,
                section,
                cardId,
                presets,
                nextSelections
        ));
        for (ConfigLayout layout : layouts) {
            ConfigField field = fields.field(layout.field());
            if (field != null && model.visible(field) && rendered.add(field.key())) {
                nodes.add(fields.configFieldNode(
                        field,
                        locked,
                        nextBindings,
                        nextSelections,
                        nextActions
                ));
            }
        }
        List<ConfigAction> actions = section.actions().stream().filter(action -> Objects.equals(
                cardId,
                action.cardId()
        )).toList();
        nodes.addAll(actionNodes(base, actions, nextActions));
        return nodes;
    }

    private List<DesktopUiNode> presetNodes(
            String base,
            ConfigSection section,
            String cardId,
            List<ConfigPreset> presets,
            Map<String, Consumer<List<String>>> nextSelections
    ) {
        if (presets.isEmpty()) return List.of();
        String binding = base + ".preset";
        ConfigPreset selected = fields.selectedPreset(presets);
        List<DesktopUiNode.Option> options = presets.stream().map(preset -> new DesktopUiNode.Option(
                presetOptionId(preset),
                preset.label().token(),
                true
        )).toList();
        nextSelections.put(
                binding,
                values -> presets.stream().filter(preset -> presetOptionId(preset).equals(first(
                        values))).findFirst().ifPresent(model::applyPreset)
        );
        LocalizedText label = section.presetLabel() == null ? LocalizedText.app(
                "gui.config.section.preset.label") : section.presetLabel();
        TextToken help = section.presetHelp() == null ? key("gui.config.section.preset.help") : section.presetHelp().token();
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(
                base + ".preset.input",
                binding,
                label.token(),
                help,
                ChoiceStyle.COMBO_BOX,
                SelectionMode.SINGLE,
                options,
                selected == null ? List.of() : List.of(presetOptionId(selected)),
                !owner.busy()
        );
        return List.of(new DesktopUiNode.Form(
                base + ".preset.form",
                DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"),
                List.of(new DesktopUiNode.FormRow(
                        base + ".preset.row",
                        label.token(),
                        help,
                        choice,
                        null
                ))
        ));
    }

    private List<DesktopUiNode> actionNodes(
            String base,
            List<ConfigAction> configActions,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (ConfigAction action : configActions) {
            String id = base + ".action." + index++ + "." + safeId(action.owner() + "." + action.spec().actionId());
            String target = id + ".run";
            nextActions.put(target, () -> model.runConfigAction(action));
            nodes.add(new DesktopUiNode.Button(
                    id,
                    target,
                    action.label().token(),
                    action.help() == null ? null : action.help().token(),
                    ButtonStyle.NORMAL,
                    !owner.busy()
            ));
        }
        return nodes;
    }

}
