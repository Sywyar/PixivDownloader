package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import javax.swing.*;
import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

/**
 * 安全页包含管理员密码、程序访问密钥与插件声明的安全管理入口。
 * 密钥编辑在同一标签页内切换为整页编辑器，返回后回到概览。
 * 所有修改通过 GUI 令牌通道提交，不直接操作 setup_config.json，以确保运行中的后端会同步失效旧 session。
 */
@Slf4j
public class SecurityPanel extends JPanel {
    private static final String OVERVIEW = "overview";
    private static final String EDITOR = "program-keys";

    private final JPasswordField currentPasswordField = new JPasswordField(24);
    private final JPasswordField newPasswordField = new JPasswordField(24);
    private final JPasswordField confirmPasswordField = new JPasswordField(24);
    private final JButton submitButton = SwingPage.primary(new JButton(message("gui.security.action.submit")));
    private final SwingPage.Text statusLabel = SwingPage.text(SwingPage.Style.SECONDARY, message("gui.security.status.idle"));

    private volatile boolean submitting;
    private final CardLayout cards = new CardLayout();
    private final SwingPage.Block accessSection = new SwingPage.Block();
    private final SwingPage.RowList securityNavigation = new SwingPage.RowList();
    private final top.sywyar.pixivdownload.gui.SwingShellAction shellAction = new top.sywyar.pixivdownload.gui.SwingShellAction();
    private final ProgramKeysPanel programKeys = new ProgramKeysPanel(new ProgramKeysPanel.Navigator() {
        @Override public void showEditor() { cards.show(SecurityPanel.this, EDITOR); }
        @Override public void showOverview() { cards.show(SecurityPanel.this, OVERVIEW); }
    });
    private JScrollPane overview;

    public SecurityPanel(int serverPort) {
        buildUi();
    }

    /** 从其它页面跳转时显示密钥列表。 */
    public void showProgramKeys() {
        programKeys.returnToList();
        SwingUtilities.invokeLater(() -> {
            Rectangle bounds = SwingUtilities.convertRectangle(programKeys.getParent(), programKeys.getBounds(),
                    overview.getViewport().getView());
            ((JComponent) overview.getViewport().getView()).scrollRectToVisible(bounds);
            programKeys.focusPrimaryAction();
        });
    }

    private void buildUi() {
        setLayout(cards);

        JPanel content = SwingPage.page();
        content.add(SwingPage.header(message("gui.tab.security"), message("gui.security.page.subtitle")));
        content.add(buildChangePasswordSection());
        content.add(Box.createVerticalStrut(30));
        content.add(programKeys);
        accessSection.add(Box.createVerticalStrut(30));
        accessSection.add(SwingPage.section(message("gui.security.section.access"), null));
        accessSection.add(securityNavigation);
        content.add(accessSection);
        content.add(Box.createVerticalGlue());
        refreshSecurityNavigation();

        overview = SwingPage.scroll(content);
        add(overview, OVERVIEW);
        add(programKeys.editorPage(), EDITOR);
        cards.show(this, OVERVIEW);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) {
                refreshSecurityNavigation();
                programKeys.pageShown();
            } else {
                programKeys.pageHidden();
            }
        });
    }

    private void refreshSecurityNavigation() {
        securityNavigation.removeAll();
        for (var plugin : SwingHost.context().currentPluginSnapshots()) {
            for (var navigation : plugin.navigation().stream()
                    .filter(entry -> entry.placements().contains(NavigationPlacements.DESKTOP_SECURITY_ACTIONS)
                            && entry.visibleTo().isVisibleTo(Audience.ADMIN))
                    .sorted(Comparator.comparingInt(NavigationContribution::priority)).toList()) {
                String label = SwingHost.context().resolveText(new DesktopUiText(navigation.labelNamespace(),
                        navigation.labelI18nKey(), navigation.id(), List.of()));
                String description = navigation.descriptionI18nKey() == null || navigation.descriptionI18nKey().isBlank() ? null
                        : SwingHost.context().resolveText(new DesktopUiText(navigation.labelNamespace(),
                        navigation.descriptionI18nKey(), "", List.of()));
                var button = new JButton(SwingHost.host().message("desktop.ui.action.open"));
                button.getAccessibleContext().setAccessibleName(label);
                button.setToolTipText(label);
                button.addActionListener(event -> {
                    if (!SwingHost.context().currentPluginSnapshots().contains(plugin)) return;
                    shellAction.run("security-open-management",
                            () -> SwingHost.host().openExternalUri(SwingHost.host().backendUri(navigation.href())),
                            failure -> GuiErrorDialog.show(this, message("gui.dialog.error.title"),
                                    message("gui.error.open-browser", failure.getClass().getSimpleName())));
                });
                securityNavigation.add(SwingPage.row(label, description, button));
            }
        }
        accessSection.setVisible(securityNavigation.getComponentCount() > 0);
        accessSection.revalidate();
        accessSection.repaint();
    }

    private JComponent buildChangePasswordSection() {
        SwingPage.Block section = new SwingPage.Block();
        section.add(SwingPage.section(message("gui.security.card.change-password.title"),
                message("gui.security.card.change-password.description")));
        section.add(Box.createVerticalStrut(8));

        submitButton.addActionListener(e -> submitChangePassword());
        SwingPage.Form form = new SwingPage.Form(520)
                .field(message("gui.security.field.current-password"), currentPasswordField)
                .field(message("gui.security.field.new-password"), newPasswordField)
                .field(message("gui.security.field.confirm-password"), confirmPasswordField)
                .note(statusLabel)
                .note(SwingPage.inline(submitButton));
        section.add(SwingPage.capped(form, 520));
        return section;
    }

    private void submitChangePassword() {
        if (submitting) {
            return;
        }
        String current = new String(currentPasswordField.getPassword());
        String next = new String(newPasswordField.getPassword());
        String confirm = new String(confirmPasswordField.getPassword());

        if (current.isEmpty()) {
            setError(message("gui.security.validation.current-required"));
            currentPasswordField.requestFocusInWindow();
            return;
        }
        if (next.isEmpty()) {
            setError(message("gui.security.validation.new-required"));
            newPasswordField.requestFocusInWindow();
            return;
        }
        if (next.length() < SwingHost.host().minimumPasswordLength()) {
            setError(message("gui.security.validation.weak-password"));
            newPasswordField.requestFocusInWindow();
            return;
        }
        if (!next.equals(confirm)) {
            setError(message("gui.security.validation.mismatch"));
            confirmPasswordField.requestFocusInWindow();
            return;
        }
        if (next.equals(current)) {
            setError(message("gui.security.validation.same-password"));
            newPasswordField.requestFocusInWindow();
            return;
        }

        submitting = true;
        refreshActionStates();
        setStatus(message("gui.security.action.submitting"), SwingPage::secondaryColor);

        Thread worker = new Thread(() -> {
            ChangePasswordOutcome outcome = sendChangePassword(current, next);
            SwingUtilities.invokeLater(() -> finishSubmit(outcome));
        }, "gui-change-password");
        worker.setDaemon(true);
        worker.start();
    }

    private ChangePasswordOutcome sendChangePassword(String oldPwd, String newPwd) {
        DesktopUiHost.GuiResponse response = SwingHost.host().guiPostJson("change-password",
                Map.of("oldPassword", oldPwd, "newPassword", newPwd), 5_000);
        if (!response.reachable()) return ChangePasswordOutcome.unreachable("unreachable");
        if (response.is2xx()) return ChangePasswordOutcome.success();
        String error = response.body() == null ? null : response.body().path("error").asText(null);
        return ChangePasswordOutcome.serverError(response.status(), error);
    }

    private void finishSubmit(ChangePasswordOutcome outcome) {
        submitting = false;
        refreshActionStates();

        switch (outcome.kind()) {
            case SUCCESS -> {
                log.info(logMessage("gui.security.log.change-password.success"));
                currentPasswordField.setText("");
                newPasswordField.setText("");
                confirmPasswordField.setText("");
                setStatus(message("gui.security.status.success"), SwingPage::successColor);
                JOptionPane.showMessageDialog(this,
                        message("gui.security.dialog.success.message"),
                        message("gui.security.dialog.success.title"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
            case INVALID_CURRENT -> {
                log.warn(logMessage("gui.security.log.change-password.invalid-current"));
                setError(message("gui.security.error.invalid-current"));
                currentPasswordField.requestFocusInWindow();
            }
            case WEAK_PASSWORD -> {
                log.warn(logMessage("gui.security.log.change-password.weak-password"));
                setError(message("gui.security.error.weak-password"));
                newPasswordField.requestFocusInWindow();
            }
            case SAME_PASSWORD -> {
                log.warn(logMessage("gui.security.log.change-password.same-password"));
                setError(message("gui.security.error.same-password"));
                newPasswordField.requestFocusInWindow();
            }
            case SETUP_INCOMPLETE -> {
                log.warn(logMessage("gui.security.log.change-password.failed", "setup-incomplete"));
                setError(message("gui.security.error.setup-incomplete"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.setup-incomplete"));
            }
            case SAVE_FAILED -> {
                log.error(logMessage("gui.security.log.change-password.failed", "save-failed"));
                setError(message("gui.security.error.save-failed"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.save-failed"));
            }
            case BACKEND_UNREACHABLE -> {
                log.error(logMessage("gui.security.log.change-password.backend-unreachable", outcome.detail()));
                setError(message("gui.security.error.backend-unreachable"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.backend-unreachable"));
            }
            default -> {
                log.error(logMessage("gui.security.log.change-password.failed",
                        outcome.detail() == null ? "unknown" : outcome.detail()));
                setError(message("gui.security.error.unexpected"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.unexpected"));
            }
        }
    }

    private void refreshActionStates() {
        submitButton.setEnabled(!submitting);
        currentPasswordField.setEnabled(!submitting);
        newPasswordField.setEnabled(!submitting);
        confirmPasswordField.setEnabled(!submitting);
    }

    private void setError(String text) {
        setStatus(text, SwingPage::errorColor);
    }

    private void setStatus(String text, Supplier<Color> color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.color(color);
            statusLabel.setText(text);
        });
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return SwingHost.host().message(code, args);
    }

    private record ChangePasswordOutcome(Kind kind, String detail) {
        enum Kind {
            SUCCESS,
            INVALID_CURRENT,
            WEAK_PASSWORD,
            SAME_PASSWORD,
            SETUP_INCOMPLETE,
            SAVE_FAILED,
            BACKEND_UNREACHABLE,
            UNEXPECTED
        }

        static ChangePasswordOutcome success() {
            return new ChangePasswordOutcome(Kind.SUCCESS, null);
        }

        static ChangePasswordOutcome unreachable(String detail) {
            return new ChangePasswordOutcome(Kind.BACKEND_UNREACHABLE, detail);
        }

        static ChangePasswordOutcome serverError(int status, String errorKind) {
            if (errorKind == null) {
                return new ChangePasswordOutcome(Kind.UNEXPECTED, "http-" + status);
            }
            return switch (errorKind) {
                case "invalid-current" -> new ChangePasswordOutcome(Kind.INVALID_CURRENT, null);
                case "weak-password" -> new ChangePasswordOutcome(Kind.WEAK_PASSWORD, null);
                case "same-password" -> new ChangePasswordOutcome(Kind.SAME_PASSWORD, null);
                case "setup-incomplete" -> new ChangePasswordOutcome(Kind.SETUP_INCOMPLETE, null);
                case "save-failed" -> new ChangePasswordOutcome(Kind.SAVE_FAILED, null);
                default -> new ChangePasswordOutcome(Kind.UNEXPECTED, errorKind);
            };
        }
    }
}
