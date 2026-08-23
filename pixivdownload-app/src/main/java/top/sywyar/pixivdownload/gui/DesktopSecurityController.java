package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.AppDesktopUiModel.RendererContract;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 管理员密码变更表单及其提交状态。
 */
final class DesktopSecurityController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopSecurityController.class);

    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final RendererContract rendererContract;
    private final Map<String, String> formValues;

    private volatile TextToken notice = key("gui.security.status.idle");
    private volatile long formRevision;

    DesktopSecurityController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            RendererContract rendererContract,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.rendererContract = rendererContract;
        this.formValues = formValues;
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        TextStyle noticeStyle = notice.key().contains(".error.") || notice.key().contains(
                ".validation.") ? TextStyle.ERROR : notice.key().endsWith(".success") ? TextStyle.SUCCESS : TextStyle.CAPTION;
        DesktopUiNode form = new DesktopUiNode.Form(
                "security.form",
                DesktopUiNode.FormStyle.COMPACT,
                null,
                List.of(
                        new DesktopUiNode.FormRow(
                                "security.current.row",
                                key("gui.security.field.current-password"),
                                null,
                                passwordInput(
                                        "security.current.input",
                                        "security.current",
                                        "gui.security.field.current-password",
                                        !owner.busy()
                                ),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "security.new.row",
                                key("gui.security.field.new-password"),
                                null,
                                passwordInput(
                                        "security.new.input",
                                        "security.new",
                                        "gui.security.field.new-password",
                                        !owner.busy()
                                ),
                                null
                        ),
                        new DesktopUiNode.FormRow(
                                "security.confirm.row",
                                key("gui.security.field.confirm-password"),
                                null,
                                passwordInput(
                                        "security.confirm.input",
                                        "security.confirm",
                                        "gui.security.field.confirm-password",
                                        !owner.busy()
                                ),
                                null
                        )
                )
        );
        List<DesktopUiNode> actions = new ArrayList<>();
        actions.add(button(
                "security.submit",
                "security.submit",
                "gui.security.action.submit",
                !owner.busy(),
                nextActions,
                this::changePassword
        ));
        if (rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER) {
            actions.add(button(
                    "security.clear",
                    "security.clear",
                    "gui.security.action.clear",
                    !owner.busy(),
                    nextActions,
                    () -> {
                        clearSecurityForm();
                        notice = key("gui.security.status.idle");
                        owner.rebuild();
                    }
            ));
        }
        DesktopUiNode bottom = column(
                "security.bottom",
                text(
                        "security.description",
                        "gui.security.card.change-password.description",
                        TextStyle.CAPTION
                ),
                new DesktopUiNode.Text(
                        "security.notice",
                        notice,
                        noticeStyle,
                        true,
                        false
                ),
                row("security.actions", actions)
        );
        return scroll(
                "security.scroll",
                column(
                        "security.root",
                        text("security.title", "desktop.ui.security.title", TextStyle.TITLE),
                        group(
                                "security.card",
                                "gui.security.card.change-password.title",
                                column("security.card.layout", form, bottom)
                        )
                )
        );
    }

    private void changePassword() {
        String current = form("security.current", "");
        String next = form("security.new", "");
        String confirm = form("security.confirm", "");
        if (current.isBlank()) {
            notice = key("gui.security.validation.current-required");
            owner.rebuild();
            return;
        }
        if (next.isBlank()) {
            notice = key("gui.security.validation.new-required");
            owner.rebuild();
            return;
        }
        if (next.length() < host.minimumPasswordLength()) {
            notice = key("gui.security.validation.weak-password");
            owner.rebuild();
            return;
        }
        if (!next.equals(confirm)) {
            notice = key("gui.security.validation.mismatch");
            owner.rebuild();
            return;
        }
        if (next.equals(current)) {
            notice = key("gui.security.validation.same-password");
            owner.rebuild();
            return;
        }
        notice = key("gui.security.action.submitting");
        owner.runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson(
                    "change-password",
                    Map.of(
                            "oldPassword",
                            current,
                            "newPassword",
                            next
                    ),
                    5_000
            );
            if (response.is2xx()) {
                LOG.info(host.message("gui.security.log.change-password.success"));
                clearSecurityForm();
                notice = key("gui.security.status.success");
                owner.showDialog(
                        "security.success",
                        "gui.security.dialog.success.title",
                        "gui.security.dialog.success.message",
                        DesktopUiDocument.DialogStyle.SUCCESS
                );
                return;
            }
            String error = response.body() == null ? "unexpected" : response.body().path("error").asText(
                    "unexpected");
            String messageKey = switch (error) {
                case "invalid-current" -> "gui.security.error.invalid-current";
                case "weak-password" -> "gui.security.error.weak-password";
                case "same-password" -> "gui.security.error.same-password";
                case "setup-incomplete" -> "gui.security.error.setup-incomplete";
                case "save-failed" -> "gui.security.error.save-failed";
                default ->
                        response.reachable() ? "gui.security.error.unexpected" : "gui.security.error.backend-unreachable";
            };
            notice = key(messageKey);
            if (Set.of(
                    "setup-incomplete",
                    "save-failed",
                    "unexpected"
            ).contains(error) || !response.reachable()) {
                LOG.error(
                        "Desktop password change failed: reachable={}, status={}, kind={}",
                        response.reachable(),
                        response.status(),
                        error
                );
                owner.showDialog(
                        "security.error",
                        "gui.dialog.error.title",
                        messageKey,
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } else {
                LOG.warn(
                        "Desktop password change rejected: status={}, kind={}",
                        response.status(),
                        error
                );
            }
        });
    }

    private void clearSecurityForm() {
        formValues.remove("security.current");
        formValues.remove("security.new");
        formValues.remove("security.confirm");
        formRevision++;
    }

    private DesktopUiNode.TextInput passwordInput(
            String id,
            String binding,
            String label,
            boolean enabled
    ) {
        return new DesktopUiNode.TextInput(
                id,
                binding,
                key(label),
                null,
                InputKind.PASSWORD,
                "",
                24,
                1,
                enabled,
                formRevision
        );
    }

    private String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }
}
