package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static top.sywyar.pixivdownload.guicompose.model.DesktopConfigurationController.*;
import static top.sywyar.pixivdownload.guicompose.model.DesktopConfigurationFieldView.*;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** 默认目录与可选模式覆盖的草稿编辑，复用设置页原有字段绑定与保存。 */
final class DesktopDirectoryTemplatesView {
    private final DesktopConfigurationController model;
    private final DesktopConfigurationFieldView fields;

    DesktopDirectoryTemplatesView(
            DesktopConfigurationController model,
            DesktopConfigurationFieldView fields
    ) {
        this.model = model;
        this.fields = fields;
    }

    DesktopUiNode build(
            ConfigSection section,
            Set<FieldKey> rendered,
            Set<FieldKey> locked,
            Map<String, ConfigField> bindings,
            Map<String, Consumer<List<String>>> selections,
            Map<String, Runnable> actions
    ) {
        String base = "config.section." + safeId(section.id());
        var layouts = section.layouts().stream().filter(layout -> {
            var field = fields.field(layout.field());
            return field != null && field.spec().type() == GuiConfigFieldType.DIRECTORY_TEMPLATE && model.visible(field);
        }).toList();
        if (layouts.isEmpty()) return column(base, List.of());
        List<DesktopUiNode> nodes = new ArrayList<>();
        var defaultField = fields.field(layouts.getFirst().field());
        rendered.add(defaultField.key());
        var defaultForm = (DesktopUiNode.Form) fields.configFieldNode(defaultField, locked, bindings, selections, actions);
        var defaultRow = defaultForm.rows().getFirst();
        nodes.add(new DesktopUiNode.Form(
                defaultForm.id(),
                defaultForm.formStyle(),
                defaultForm.labelSuffix(),
                List.of(new DesktopUiNode.FormRow(
                        defaultRow.id(),
                        key("gui.config.directory.default"),
                        null,
                        defaultRow.content(),
                        defaultRow.trailing()
                ))
        ));
        nodes.add(text(base + ".fallback", "gui.config.directory.help", DesktopUiNode.TextStyle.CAPTION));
        var overrides = layouts.subList(1, layouts.size());
        for (var layout : overrides) {
            rendered.add(layout.field());
            if (!model.values.getOrDefault(layout.field(), "").isBlank()) model.directoryRows.add(layout.field());
        }
        List<DesktopUiNode.FormRow> rows = new ArrayList<>();
        for (var layout : overrides) {
            if (!model.directoryRows.contains(layout.field())) continue;
            var field = fields.field(layout.field());
            var form = (DesktopUiNode.Form) fields.configFieldNode(field, locked, bindings, selections, actions);
            var row = form.rows().getFirst();
            String binding = bindingId(field.key());
            boolean enabled = !model.owner.busy() && model.enabled(field) && !locked.contains(field.key());
            var available = overrides.stream().filter(candidate -> candidate.equals(layout)
                    || !model.directoryRows.contains(candidate.field())).toList();
            var options = available.stream().map(candidate -> new DesktopUiNode.Option(
                    bindingId(candidate.field()),
                    candidate.cardLabel() == null ? token(
                            fields.field(candidate.field()).namespace(),
                            fields.field(candidate.field()).spec().labelKey(),
                            candidate.field().key()
                    ) : candidate.cardLabel().token(),
                    model.enabled(fields.field(candidate.field())) && !locked.contains(candidate.field())
            )).toList();
            selections.put(binding + ".mode", selected -> {
                if (!enabled) return;
                available.stream().filter(candidate -> bindingId(candidate.field()).equals(first(selected)))
                        .filter(candidate -> !candidate.equals(layout))
                        .filter(candidate -> model.enabled(fields.field(candidate.field())) && !locked.contains(candidate.field()))
                        .findFirst().ifPresent(candidate -> {
                            model.values.put(candidate.field(), model.values.getOrDefault(field.key(), ""));
                            model.values.put(field.key(), "");
                            model.directoryRows.remove(field.key());
                            model.directoryRows.add(candidate.field());
                            focus(candidate.field());
                            model.owner.rebuild();
                        });
            });
            var mode = new DesktopUiNode.Choice(
                    binding + ".mode",
                    binding + ".mode",
                    key("gui.config.directory.mode"),
                    null,
                    DesktopUiNode.ChoiceStyle.COMBO_BOX,
                    DesktopUiNode.SelectionMode.SINGLE,
                    options,
                    List.of(binding),
                    enabled
            );
            var remove = button(
                    binding + ".remove",
                    binding + ".remove",
                    "gui.config.directory.remove",
                    enabled,
                    actions,
                    () -> {
                        model.values.put(field.key(), "");
                        model.directoryRows.remove(field.key());
                        focus(overrides.stream().map(ConfigLayout::field).filter(model.directoryRows::contains)
                                .findFirst().orElse(defaultField.key()));
                        model.owner.rebuild();
                    }
            );
            rows.add(new DesktopUiNode.FormRow(
                    row.id(),
                    row.label(),
                    row.help(),
                    new DesktopUiNode.Dock(binding + ".override", 8, null, row.content(), null, mode, remove),
                    row.trailing()
            ));
        }
        if (!rows.isEmpty()) nodes.add(new DesktopUiNode.Form(
                base + ".overrides",
                DesktopUiNode.FormStyle.DIRECTORY_OVERRIDES,
                key("gui.punctuation.colon"),
                rows
        ));
        var available = overrides.stream().filter(layout -> !model.directoryRows.contains(layout.field()))
                .filter(layout -> model.enabled(fields.field(layout.field())) && !locked.contains(layout.field())).toList();
        nodes.add(button(
                base + ".add",
                base + ".add",
                "gui.config.directory.add",
                !model.owner.busy() && !available.isEmpty(),
                actions,
                () -> {
                    if (!available.isEmpty()) {
                        model.directoryRows.add(available.getFirst().field());
                        focus(available.getFirst().field());
                        model.owner.rebuild();
                    }
                }
        ));
        String guide = base + ".guide";
        actions.put(guide + ".open", () -> model.owner.openUri(model.host.message("gui.config.directory.guide-url")));
        List<DesktopUiNode> help = new ArrayList<>();
        if (defaultRow.help() != null) help.add(new DesktopUiNode.Text(
                base + ".help.text", defaultRow.help(), DesktopUiNode.TextStyle.CAPTION, true, false));
        help.add(new DesktopUiNode.Link(guide, guide + ".open", key("gui.config.directory.guide"), null, true));
        return new DesktopUiNode.Group(
                base,
                section.title() == null ? key("gui.config.directory.default") : section.title().token(),
                column(base + ".content", nodes),
                false,
                null,
                key("gui.config.directory.variables"),
                column(base + ".help.content", help)
        );
    }

    private void focus(FieldKey field) {
        model.locateRow(bindingId(field) + ".row");
    }
}
