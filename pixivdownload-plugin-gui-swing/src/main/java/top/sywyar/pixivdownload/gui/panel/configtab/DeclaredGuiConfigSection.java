package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.config.GuiConfigActionSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigActionResultSafety;
import top.sywyar.pixivdownload.gui.config.GuiConfigActionResultRuleSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigFieldLayoutSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigPresetSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionNoticeSpec;
import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiValue;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiThemeRefresh;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigConditionOperator;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.text.MessageFormat;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Generic renderer for plugin-declared GUI configuration sections.
 */
@Slf4j
final class DeclaredGuiConfigSection implements ConfigSection {

    static final String NOTICE_ID_PROPERTY = "pixivdownload.guiConfig.sectionNoticeId";
    static final String NOTICE_STYLE_PROPERTY = "pixivdownload.guiConfig.sectionNoticeStyle";
    private static final Color HINT_COLOR = new Color(0, 128, 96);

    private final ConfigSectionContext ctx;
    private final String group;
    private final List<GuiConfigSectionSpec> sections;
    private final List<PresetState> presetStates = new ArrayList<>();

    DeclaredGuiConfigSection(ConfigSectionContext ctx, String group, List<GuiConfigSectionSpec> sections) {
        this.ctx = ctx;
        this.group = group;
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public JComponent build() {
        presetStates.clear();
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        Set<String> rendered = new LinkedHashSet<>();

        for (GuiConfigSectionSpec section : sections) {
            addSectionNotices(content, section);
            addSectionHeader(content, section);
            addPresetCombo(content, section, null);
            if (section.layout() == GuiConfigSectionLayout.CARD_SWITCHER) {
                Map<String, List<GuiConfigFieldLayoutSpec>> layoutsByCard = layoutsByCard(section);
                if (!layoutsByCard.isEmpty()) {
                    addCardSwitcher(content, section, rendered, layoutsByCard);
                } else {
                    addFields(content, fieldsFor(section, rendered));
                }
            } else if (section.layout() == GuiConfigSectionLayout.COMPACT_GRID) {
                addCompactGrid(content, section, rendered);
            } else {
                addFields(content, fieldsFor(section, rendered));
            }
            addActions(content, section, null);
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    @Override
    public void onValuesLoaded() {
        for (PresetState state : presetStates) {
            GuiConfigPresetSpec selected = resolvePreset(state);
            if (selected == null) {
                continue;
            }
            state.setUpdating(true);
            try {
                state.combo().setSelectedItem(selected);
            } finally {
                state.setUpdating(false);
            }
        }
    }

    @Override
    public void afterEnabledStates() {
        for (PresetState state : presetStates) {
            Object selected = state.combo().getSelectedItem();
            if (selected instanceof GuiConfigPresetSpec preset) {
                preset.lockedFieldKeys().stream()
                        .filter(key -> trustedPresetField(state.section(), preset, key) != null)
                        .forEach(ctx::lockField);
            }
        }
    }

    private GuiConfigPresetSpec resolvePreset(PresetState state) {
        GuiConfigPresetSpec fallback = null;
        for (GuiConfigPresetSpec preset : state.presets()) {
            if (preset.values().isEmpty() && fallback == null) {
                fallback = preset;
            }
            if (preset.matchFieldKey() == null) {
                continue;
            }
            if (trustedPresetField(state.section(), preset, preset.matchFieldKey()) == null) {
                continue;
            }
            if (matchesPresetValue(ctx.currentFieldValue(preset.matchFieldKey()), preset)) {
                return preset;
            }
        }
        return fallback;
    }

    private static boolean matchesPresetValue(String actual, GuiConfigPresetSpec preset) {
        String safeActual = actual == null ? "" : actual;
        String safeExpected = preset.matchValue() == null ? "" : preset.matchValue();
        return switch (preset.matchMode()) {
            case EQUALS_IGNORE_CASE -> safeActual.equalsIgnoreCase(safeExpected);
            case TRIMMED_TRAILING_SLASH_IGNORE_CASE ->
                    trimTrailingSlashes(safeActual).equalsIgnoreCase(trimTrailingSlashes(safeExpected));
        };
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void addSectionNotices(JPanel content, GuiConfigSectionSpec section) {
        for (GuiConfigSectionNoticeSpec notice : section.notices()) {
            if (notice.text().isBlank()) {
                continue;
            }
            JLabel label = new JLabel(notice.text());
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
            label.setForeground(noticeColor(notice));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.putClientProperty(NOTICE_ID_PROPERTY, notice.noticeId());
            label.putClientProperty(NOTICE_STYLE_PROPERTY, notice.style());
            content.add(label);
            content.add(Box.createVerticalStrut(4));
        }
    }

    private static Color noticeColor(GuiConfigSectionNoticeSpec notice) {
        return switch (notice.style()) {
            case HINT -> HINT_COLOR;
        };
    }

    private void addSectionHeader(JPanel content, GuiConfigSectionSpec section) {
        if (section.title().isBlank() && section.help().isBlank()) {
            return;
        }
        JLabel title = new JLabel(section.title().isBlank() ? section.sectionId() : section.title());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        if (!section.help().isBlank()) {
            JLabel help = new JLabel(section.help());
            help.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(help);
        }
        content.add(Box.createVerticalStrut(4));
    }

    private void addPresetCombo(JPanel content, GuiConfigSectionSpec section, String cardId) {
        List<GuiConfigPresetSpec> presets = section.presets().stream()
                .filter(preset -> Objects.equals(cardId, preset.cardId()))
                .toList();
        if (presets.isEmpty()) {
            return;
        }
        JComboBox<GuiConfigPresetSpec> combo =
                new JComboBox<>(presets.toArray(new GuiConfigPresetSpec[0]));
        PresetState state = new PresetState(combo, presets, section);
        presetStates.add(state);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof GuiConfigPresetSpec preset) {
                    label.setText(preset.label());
                }
                return label;
            }
        });
        combo.addActionListener(e -> {
            if (state.updating()) {
                return;
            }
            if (combo.getSelectedItem() instanceof GuiConfigPresetSpec preset) {
                preset.values().forEach((key, value) -> {
                    if (trustedPresetField(section, preset, key) != null) {
                        ctx.setFieldValue(key, value);
                    }
                });
                ctx.updateEnabledStates();
            }
        });
        JPanel panel = FieldRenderer.fieldPanel(
                textOrDefault(section.presetLabel(), "gui.config.section.preset.label")
                        + message("gui.punctuation.colon"),
                combo,
                null,
                textOrDefault(section.presetHelp(), "gui.config.section.preset.help"));
        addPanel(content, panel);
    }

    private void addCardSwitcher(JPanel content, GuiConfigSectionSpec section, Set<String> rendered,
                                 Map<String, List<GuiConfigFieldLayoutSpec>> byCard) {
        List<String> cardIds = new ArrayList<>(byCard.keySet());
        JComboBox<String> cardCombo = new JComboBox<>(cardIds.toArray(new String[0]));
        cardCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof String cardId) {
                    label.setText(cardLabel(cardId, byCard.get(cardId)));
                }
                return label;
            }
        });
        JPanel comboRow = FieldRenderer.fieldPanel(
                textOrDefault(section.layoutLabel(), "gui.config.section.card.label") + message("gui.punctuation.colon"),
                cardCombo, null, textOrDefault(section.layoutHelp(), "gui.config.section.card.help"));
        addPanel(content, comboRow);

        Map<String, JComponent> cards = new LinkedHashMap<>();
        for (String cardId : cardIds) {
            JPanel card = ctx.newContentPanel();
            addCardContent(card, section, byCard.get(cardId), cardId, rendered);
            cards.put(cardId, card);
        }
        JPanel cardHost = manualSwapHost();
        cardCombo.addActionListener(e -> {
            if (cardCombo.getSelectedItem() instanceof String cardId) {
                GuiThemeRefresh.showCard(cardHost, cards.get(cardId));
            }
        });
        if (!cardIds.isEmpty()) {
            GuiThemeRefresh.showCard(cardHost, cards.get(cardIds.get(0)));
        }
        content.add(cardHost);
    }

    private void addCardContent(JPanel content, GuiConfigSectionSpec section,
                                List<GuiConfigFieldLayoutSpec> layouts,
                                String cardId, Set<String> rendered) {
        addPresetCombo(content, section, cardId);
        List<ConfigFieldSpec> fields = fieldsByLayout(section, layouts, rendered);
        if (!addNestedEnumSwitcher(content, fields)) {
            addFields(content, fields);
        }
        addActions(content, section, cardId);
    }

    private boolean addNestedEnumSwitcher(JPanel content, List<ConfigFieldSpec> fields) {
        NestedEnumCards nested = nestedEnumCards(fields);
        if (nested == null) {
            return false;
        }

        FieldRenderer.RenderedField controller = ConfigFieldRows.render(nested.controller());
        ctx.registerField(nested.controller(), controller);
        content.add(controller.panel());

        Map<String, JComponent> cards = new LinkedHashMap<>();
        for (String value : nested.controller().enumValues()) {
            JPanel card = ctx.newContentPanel();
            addFields(card, nested.fieldsByValue().getOrDefault(value, List.of()));
            cards.put(value, card);
        }

        JPanel cardHost = manualSwapHost();
        if (controller.control() instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> {
                Object selected = combo.getSelectedItem();
                if (selected instanceof String value) {
                    GuiThemeRefresh.showCard(cardHost, cardOrFirst(cards, value));
                }
            });
            Object selected = combo.getSelectedItem();
            String initial = selected instanceof String value ? value : nested.controller().defaultValue();
            GuiThemeRefresh.showCard(cardHost, cardOrFirst(cards, initial));
        }
        content.add(cardHost);
        return true;
    }

    private void addCompactGrid(JPanel content, GuiConfigSectionSpec section, Set<String> rendered) {
        List<ConfigFieldSpec> fields = fieldsFor(section, rendered);
        List<ConfigFieldSpec> compactFields = fields.stream()
                .filter(field -> field.type() == FieldType.BOOL)
                .toList();
        List<ConfigFieldSpec> otherFields = fields.stream()
                .filter(field -> field.type() != FieldType.BOOL)
                .toList();
        if (!compactFields.isEmpty()) {
            JPanel grid = new JPanel(new GridLayout(0, 2, 12, 2));
            grid.setOpaque(false);
            Map<ConfigFieldSpec, JCheckBox> checkBoxes = new LinkedHashMap<>();
            for (ConfigFieldSpec spec : compactFields) {
                JCheckBox checkBox = new JCheckBox(spec.label());
                checkBox.setSelected("true".equalsIgnoreCase(spec.defaultValue()));
                checkBox.setToolTipText(spec.helpText());
                checkBox.setOpaque(false);
                checkBoxes.put(spec, checkBox);
                grid.add(checkBox);
            }
            GuiConfigEffect effect = compactFields.stream()
                    .anyMatch(field -> field.effect() == GuiConfigEffect.PROCESS_RESTART)
                    ? GuiConfigEffect.PROCESS_RESTART
                    : compactFields.stream().anyMatch(field -> field.effect() == GuiConfigEffect.BACKEND_RESTART)
                            ? GuiConfigEffect.BACKEND_RESTART
                            : GuiConfigEffect.HOT_RELOAD;
            JPanel panel = FieldRenderer.fieldPanel(
                    textOrDefault(section.layoutLabel(), "gui.config.section.compact.label")
                            + message("gui.punctuation.colon"),
                    grid,
                    ctx.effectLabel(effect),
                    textOrDefault(section.layoutHelp(), "gui.config.section.compact.help"));
            addPanel(content, panel);
            for (Map.Entry<ConfigFieldSpec, JCheckBox> entry : checkBoxes.entrySet()) {
                JCheckBox checkBox = entry.getValue();
                FieldRenderer.RenderedField renderedField = new FieldRenderer.RenderedField(
                        panel,
                        () -> Boolean.toString(checkBox.isSelected()),
                        value -> checkBox.setSelected("true".equalsIgnoreCase(value)),
                        checkBox,
                        ctx.hiddenValidationError());
                ctx.registerField(entry.getKey(), renderedField);
            }
        }
        addFields(content, otherFields);
    }

    private String cardLabel(String cardId, List<GuiConfigFieldLayoutSpec> layouts) {
        if (layouts != null) {
            for (GuiConfigFieldLayoutSpec layout : layouts) {
                if (!layout.cardLabel().isBlank()) {
                    return layout.cardLabel();
                }
            }
        }
        return cardId;
    }

    private Map<String, List<GuiConfigFieldLayoutSpec>> layoutsByCard(GuiConfigSectionSpec section) {
        Map<String, List<GuiConfigFieldLayoutSpec>> byCard = new LinkedHashMap<>();
        for (GuiConfigFieldLayoutSpec layout : section.fieldLayouts()) {
            if (layout.cardId() == null) {
                continue;
            }
            byCard.computeIfAbsent(layout.cardId(), ignored -> new ArrayList<>()).add(layout);
        }
        return byCard;
    }

    private List<ConfigFieldSpec> fieldsFor(GuiConfigSectionSpec section, Set<String> rendered) {
        if (!section.fieldLayouts().isEmpty()) {
            return fieldsByLayout(section, section.fieldLayouts(), rendered);
        }
        if (!section.contributesGroupVisibility()) {
            return List.of();
        }
        return ctx.allFields().stream()
                .filter(field -> matchesGroup(field, section))
                .filter(field -> section.ownerPluginIds().contains(field.ownerPluginId()))
                .filter(field -> rendered.add(field.key()))
                .toList();
    }

    private boolean matchesGroup(ConfigFieldSpec field, GuiConfigSectionSpec section) {
        String fieldGroupId = normalizeGroupId(field.groupId());
        String sectionGroupId = normalizeGroupId(section.groupId());
        if (fieldGroupId != null && sectionGroupId != null) {
            return fieldGroupId.equals(sectionGroupId);
        }
        return Objects.equals(field.group(), group);
    }

    private static String normalizeGroupId(String groupId) {
        return groupId == null || groupId.isBlank() ? null : groupId.trim();
    }

    private List<ConfigFieldSpec> fieldsByLayout(GuiConfigSectionSpec section,
                                                 List<GuiConfigFieldLayoutSpec> layouts,
                                                 Set<String> rendered) {
        List<ConfigFieldSpec> fields = new ArrayList<>();
        for (GuiConfigFieldLayoutSpec layout : layouts) {
            ConfigFieldSpec spec = ctx.findSpec(layout.fieldKey());
            String owner = effectiveOwner(layout.ownerPluginId(), section);
            if (spec != null
                    && owner != null
                    && owner.equals(spec.ownerPluginId())
                    && section.ownerPluginIds().contains(owner)
                    && rendered.add(spec.key())) {
                fields.add(spec);
            }
        }
        return fields;
    }

    private NestedEnumCards nestedEnumCards(List<ConfigFieldSpec> fields) {
        if (fields == null || fields.size() < 2) {
            return null;
        }
        for (ConfigFieldSpec candidate : fields) {
            if (candidate.type() != FieldType.ENUM || candidate.enumValues().isEmpty()) {
                continue;
            }
            Map<String, List<ConfigFieldSpec>> byValue = new LinkedHashMap<>();
            boolean allDependOnCandidate = true;
            for (ConfigFieldSpec field : fields) {
                if (field.key().equals(candidate.key())) {
                    continue;
                }
                String value = visibleEqualsValue(field, candidate.key());
                if (value == null || !candidate.enumValues().contains(value)) {
                    allDependOnCandidate = false;
                    break;
                }
                byValue.computeIfAbsent(value, ignored -> new ArrayList<>()).add(field);
            }
            if (allDependOnCandidate && !byValue.isEmpty()) {
                return new NestedEnumCards(candidate, byValue);
            }
        }
        return null;
    }

    private static String visibleEqualsValue(ConfigFieldSpec field, String key) {
        for (GuiConfigCondition condition : field.visibleWhenConditions()) {
            if (condition != null
                    && condition.operator() == GuiConfigConditionOperator.EQUALS
                    && key.equals(condition.key())) {
                return condition.value() == null ? "" : condition.value();
            }
        }
        return null;
    }

    private static JComponent cardOrFirst(Map<String, JComponent> cards, String key) {
        JComponent card = cards.get(key);
        if (card != null) {
            return card;
        }
        return cards.values().iterator().next();
    }

    private void addFields(JPanel content, List<ConfigFieldSpec> fields) {
        if (!fields.isEmpty()) {
            ctx.addFields(content, fields);
        }
    }

    private void addActions(JPanel content, GuiConfigSectionSpec section, String cardId) {
        for (GuiConfigActionSpec action : section.actions().stream()
                .filter(action -> Objects.equals(cardId, action.cardId()))
                .toList()) {
            JButton button = new JButton(action.label());
            button.addActionListener(e -> runAction(section, action, button));
            JPanel panel = FieldRenderer.fieldPanel(
                    action.label() + message("gui.punctuation.colon"),
                    button,
                    null,
                    action.help());
            addPanel(content, panel);
        }
    }

    private void runAction(GuiConfigSectionSpec section, GuiConfigActionSpec action, JButton button) {
        button.setEnabled(false);
        ctx.showNotice(action.sendingNotice().isBlank()
                ? message("gui.config.action.notice.sending", action.label())
                : action.sendingNotice());
        Map<String, Object> body;
        try {
            body = buildPayload(section, action);
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.action.payload-failed", action.actionId(), safeMessage(e)), e);
            ctx.showNotice(message("gui.config.action.notice.failed", action.label(), safeMessage(e)));
            button.setEnabled(true);
            return;
        }

        SwingWorker<DesktopUiHost.GuiResponse, Void> worker = new SwingWorker<>() {
            @Override
            protected DesktopUiHost.GuiResponse doInBackground() {
                String owner = effectiveOwner(action.ownerPluginId(), section);
                return ctx.desktopHost().guiPostJson(action.endpoint(), body, action.readTimeoutMillis(), owner);
            }

            @Override
            protected void done() {
                try {
                    DesktopUiHost.GuiResponse response = get();
                    ctx.showNotice(actionNotice(action, response));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.action.notice.unreachable", action.label()));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    log.warn(logMessage("gui.config.log.action.failed",
                            action.actionId(), safeMessage(cause)), cause);
                    ctx.showNotice(message("gui.config.action.notice.failed",
                            action.label(), safeMessage(cause)));
                } finally {
                    button.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private String actionNotice(GuiConfigActionSpec action, DesktopUiHost.GuiResponse response) {
        ActionResult result = ActionResult.from(response, action.resultSummary());
        for (GuiConfigActionResultRuleSpec rule : action.resultRules()) {
            if (rule.conditions().stream().allMatch(condition -> matches(result, condition))) {
                Object[] args = rule.arguments().stream()
                        .map(argument -> argumentValue(result, argument))
                        .toArray();
                return args.length == 0 ? rule.notice() : MessageFormat.format(rule.notice(), args);
            }
        }
        if (!response.reachable()) {
            return message("gui.config.action.notice.unreachable", action.label());
        }
        if (response.is2xx()) {
            return message("gui.config.action.notice.success", action.label());
        }
        return message("gui.config.action.notice.failed", action.label(), "HTTP " + response.status());
    }

    private boolean matches(ActionResult result, GuiConfigActionResultCondition condition) {
        String actual = result.value(condition.source(), condition.path());
        return switch (condition.operator()) {
            case TRUE -> Boolean.parseBoolean(actual);
            case FALSE -> !Boolean.parseBoolean(actual);
            case EQUALS -> actual.equals(condition.value());
            case NOT_EQUALS -> !actual.equals(condition.value());
            case GREATER_THAN -> parseIntOrZero(actual) > parseIntOrZero(condition.value());
            case CONTAINS -> actual.contains(condition.value());
            case BLANK -> actual.isBlank();
            case NOT_BLANK -> !actual.isBlank();
        };
    }

    private String argumentValue(ActionResult result, GuiConfigActionResultArgument argument) {
        String value = result.value(argument.source(), argument.path());
        return value.isBlank()
                ? GuiConfigActionResultSafety.sanitizeDisplayText(argument.defaultValue())
                : value;
    }

    private Map<String, Object> buildPayload(GuiConfigSectionSpec section, GuiConfigActionSpec action) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        String owner = effectiveOwner(action.ownerPluginId(), section);
        if (owner == null || !section.ownerPluginIds().contains(owner)) {
            throw new IllegalStateException("GUI action owner is unavailable");
        }
        for (GuiConfigActionPayloadField field : action.payloadFields()) {
            ConfigFieldSpec source = field.fieldKey() == null ? null : ctx.findSpec(field.fieldKey());
            if (field.fieldKey() != null && (source == null || !owner.equals(source.ownerPluginId()))) {
                throw new IllegalStateException("GUI action payload field owner mismatch");
            }
            String value = field.fieldKey() == null
                    ? field.literalValue()
                    : ctx.actionFieldValue(field.fieldKey());
            putPayload(root, field.payloadPath(), value, field.valueType());
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private void putPayload(Map<String, Object> root, String path, String value, GuiConfigActionPayloadType type) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object child = current.computeIfAbsent(part, ignored -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map<?, ?>)) throw new IllegalStateException("GUI action payload path conflict");
            current = (Map<String, Object>) child;
        }
        String leaf = parts[parts.length - 1];
        switch (type) {
            case INT -> current.put(leaf, parseIntOrZero(value));
            case BOOLEAN -> current.put(leaf, Boolean.parseBoolean(value));
            case STRING -> current.put(leaf, value == null ? "" : value);
        }
    }

    private static void addPanel(JPanel content, JPanel panel) {
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(panel);
        content.add(Box.createVerticalStrut(2));
    }

    private static String textOrDefault(String text, String defaultKey) {
        return text == null || text.isBlank() ? message(defaultKey) : text;
    }

    private static JPanel manualSwapHost() {
        JPanel host = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        host.setOpaque(false);
        host.setAlignmentX(Component.LEFT_ALIGNMENT);
        return host;
    }

    private static int parseIntOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ConfigFieldSpec trustedPresetField(GuiConfigSectionSpec section,
                                               GuiConfigPresetSpec preset,
                                               String fieldKey) {
        String owner = effectiveOwner(preset.ownerPluginId(), section);
        ConfigFieldSpec field = ctx.findSpec(fieldKey);
        if (owner == null
                || !section.ownerPluginIds().contains(owner)
                || field == null
                || !owner.equals(field.ownerPluginId())
                || field.type() == FieldType.PASSWORD) {
            return null;
        }
        return field;
    }

    private static String effectiveOwner(String itemOwner, GuiConfigSectionSpec section) {
        if (itemOwner != null && !itemOwner.isBlank()) {
            return itemOwner.trim();
        }
        return section.pluginId() == null || section.pluginId().isBlank() ? null : section.pluginId().trim();
    }

    private record PresetState(JComboBox<GuiConfigPresetSpec> combo,
                               List<GuiConfigPresetSpec> presets,
                               GuiConfigSectionSpec section) {
        private static final String UPDATING_KEY = "pixivdownloader.updatingPreset";

        private boolean updating() {
            return Boolean.TRUE.equals(combo.getClientProperty(UPDATING_KEY));
        }

        private void setUpdating(boolean updating) {
            combo.putClientProperty(UPDATING_KEY, updating);
        }
    }

    private record NestedEnumCards(
            ConfigFieldSpec controller,
            Map<String, List<ConfigFieldSpec>> fieldsByValue
    ) {
    }

    private record ActionResult(
            boolean reachable,
            boolean http2xx,
            int status,
            GuiValue body,
            String summary
    ) {
        private static ActionResult from(DesktopUiHost.GuiResponse response,
                                         GuiConfigActionResultSummary summarySpec) {
            GuiValue parsed = response.bodyLimitExceeded() ? null : response.body();
            return new ActionResult(response.reachable(), response.is2xx(), response.status(), parsed,
                    buildSummary(parsed, summarySpec));
        }

        private String value(GuiConfigActionResultSource source, String path) {
            return switch (source) {
                case REACHABLE -> Boolean.toString(reachable);
                case HTTP_2XX -> Boolean.toString(http2xx);
                case HTTP_STATUS -> Integer.toString(status);
                case HTTP_STATUS_TEXT -> status <= 0 ? "" : "HTTP " + status;
                case JSON -> jsonText(path);
                case SUMMARY -> summary == null ? "" : summary;
            };
        }

        private String jsonText(String path) {
            if (!GuiConfigActionResultSafety.isSafeJsonPath(path, false)) {
                return "";
            }
            GuiValue node = nodeAt(body, path);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            if (node.isBoolean()) {
                return Boolean.toString(node.asBoolean());
            }
            if (node.isNumber()) {
                return GuiConfigActionResultSafety.sanitizeDisplayText(node.asText());
            }
            if (!node.isTextual()) {
                return "";
            }
            return GuiConfigActionResultSafety.sanitizeDisplayText(node.asText(""));
        }

        private static String buildSummary(GuiValue body, GuiConfigActionResultSummary spec) {
            if (body == null || spec == null
                    || !GuiConfigActionResultSafety.isSafeJsonPath(spec.arrayPath(), false)
                    || !GuiConfigActionResultSafety.isSafeJsonPath(spec.labelPath(), false)
                    || !GuiConfigActionResultSafety.isSafeJsonPath(spec.statusPath(), true)
                    || !GuiConfigActionResultSafety.isSafeJsonPath(spec.detailPath(), true)) {
                return "";
            }
            GuiValue array = nodeAt(body, spec.arrayPath());
            if (array == null || !array.isArray() || array.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int itemCount = 0;
            for (GuiValue item : array) {
                if (itemCount >= GuiConfigActionResultSafety.MAX_SUMMARY_ITEMS) {
                    break;
                }
                String status = textAt(item, spec.statusPath());
                if (!spec.statusPath().isBlank() && status.equals(spec.successStatus())) {
                    continue;
                }
                String label = textAt(item, spec.labelPath());
                String detail = textAt(item, spec.detailPath());
                if (label.isBlank() && status.isBlank() && detail.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(label.isBlank() ? "-" : label);
                if (!spec.statusPath().isBlank()) {
                    sb.append(": ");
                    sb.append(status);
                    if (!detail.isBlank()) {
                        sb.append(" (").append(detail).append(')');
                    }
                } else if (!detail.isBlank()) {
                    sb.append(": ").append(detail);
                }
                itemCount++;
                if (sb.codePointCount(0, sb.length()) >= GuiConfigActionResultSafety.MAX_SUMMARY_CODE_POINTS) {
                    break;
                }
            }
            return GuiConfigActionResultSafety.sanitizeSummary(sb.toString());
        }

        private static String textAt(GuiValue node, String path) {
            if (!GuiConfigActionResultSafety.isSafeJsonPath(path, true)) {
                return "";
            }
            GuiValue found = nodeAt(node, path);
            if (found == null || found.isMissingNode() || found.isNull() || !found.isValueNode()) {
                return "";
            }
            return GuiConfigActionResultSafety.sanitizeDisplayText(found.asText(""));
        }

        private static GuiValue nodeAt(GuiValue root, String path) {
            if (root == null) {
                return null;
            }
            if (path == null || path.isBlank()) {
                return root;
            }
            GuiValue current = root;
            for (String part : path.split("\\.")) {
                if (part.isBlank()) {
                    return null;
                }
                current = current.path(part);
                if (current.isMissingNode() || current.isNull()) {
                    return current;
                }
            }
            return current;
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return SwingHost.host().message(code, args);
    }
}
