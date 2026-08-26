package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopRepositorySettingsController.choice;
import static top.sywyar.pixivdownload.guicompose.model.DesktopRepositorySettingsController.formRow;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 受信密钥编辑对话框的节点树。
 */
final class DesktopTrustedKeyEditorView {
    private final DesktopRepositorySettingsController model;

    DesktopTrustedKeyEditorView(DesktopRepositorySettingsController model) {
        this.model = model;
    }

    DesktopUiNode content(
            Map<String, Runnable> nextActions,
            String dismissAction,
            Runnable dismiss
    ) {
        List<DesktopUiNode.FormRow> fields = new ArrayList<>();
        fields.add(formRow(
                "config.market.trusted-key.id.row",
                "gui.config.market.repo.trust.field.key-id",
                null,
                input(
                        "config.market.trusted-key.id.input",
                        "config.market.trusted-key.id",
                        "gui.config.market.repo.trust.field.key-id",
                        null,
                        InputKind.TEXT,
                        model.form("config.market.trusted-key.id", ""),
                        true
                )
        ));
        fields.add(formRow(
                "config.market.trusted-key.algorithm.row",
                "gui.config.market.repo.trust.field.algorithm",
                null,
                input(
                        "config.market.trusted-key.algorithm.input",
                        "config.market.trusted-key.algorithm",
                        "gui.config.market.repo.trust.field.algorithm",
                        null,
                        InputKind.TEXT,
                        model.form("config.market.trusted-key.algorithm", "Ed25519"),
                        true
                )
        ));
        fields.add(formRow(
                "config.market.trusted-key.public-key.row",
                "gui.config.market.repo.trust.field.public-key",
                "gui.config.market.repo.trust.public-key.hint",
                input(
                        "config.market.trusted-key.public-key.input",
                        "config.market.trusted-key.public-key",
                        "gui.config.market.repo.trust.field.public-key",
                        "gui.config.market.repo.trust.public-key.hint",
                        InputKind.MULTILINE,
                        model.form("config.market.trusted-key.public-key", ""),
                        true
                )
        ));
        List<DesktopUiNode.Option> states = List.of(
                new DesktopUiNode.Option(
                        "ACTIVE",
                        key("gui.config.market.repo.trust.state.active"),
                        true
                ),
                new DesktopUiNode.Option(
                        "RETIRED",
                        key("gui.config.market.repo.trust.state.retired"),
                        true
                ),
                new DesktopUiNode.Option(
                        "REVOKED",
                        key("gui.config.market.repo.trust.state.revoked"),
                        true
                )
        );
        fields.add(formRow(
                "config.market.trusted-key.state.row",
                "gui.config.market.repo.trust.field.state",
                null,
                choice(
                        "config.market.trusted-key.state.input",
                        "config.market.trusted-key.state",
                        "gui.config.market.repo.trust.field.state",
                        null,
                        states,
                        model.form("config.market.trusted-key.state", "ACTIVE"),
                        true
                )
        ));
        fields.add(formRow(
                "config.market.trusted-key.publisher.row",
                "gui.config.market.repo.trust.field.publisher",
                null,
                input(
                        "config.market.trusted-key.publisher.input",
                        "config.market.trusted-key.publisher",
                        "gui.config.market.repo.trust.field.publisher",
                        null,
                        InputKind.TEXT,
                        model.form("config.market.trusted-key.publisher", ""),
                        true
                )
        ));
        fields.add(formRow(
                "config.market.trusted-key.trust-label.row",
                "gui.config.market.repo.trust.field.trust-label",
                null,
                input(
                        "config.market.trusted-key.trust-label.input",
                        "config.market.trusted-key.trust-label",
                        "gui.config.market.repo.trust.field.trust-label",
                        null,
                        InputKind.TEXT,
                        model.form("config.market.trusted-key.trust-label", ""),
                        true
                )
        ));
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(new DesktopUiNode.Form(
                "config.market.trusted-key.form",
                DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"),
                fields
        ));
        if (!model.formErrorKey.isBlank()) {
            content.add(text(
                    "config.market.trusted-key.error",
                    model.formErrorKey,
                    TextStyle.ERROR
            ));
        }
        String cancelAction = "config.market.trusted-key.cancel";
        nextActions.put(cancelAction, model::showRepositoryEditorDialog);
        return column(
                "config.market.trusted-key.dialog.content",
                column("config.market.trusted-key.dialog.fields", content),
                row(
                        "config.market.trusted-key.dialog.actions",
                        button(
                                "config.market.trusted-key.save",
                                "config.market.trusted-key.save",
                                "gui.config.market.repo.dialog.ok",
                                true,
                                nextActions,
                                model::saveTrustedKeyEditor
                        ),
                        button(
                                "config.market.trusted-key.cancel",
                                cancelAction,
                                "gui.config.market.repo.dialog.cancel",
                                true,
                                nextActions,
                                model::showRepositoryEditorDialog
                        )
                )
        );
    }
}
