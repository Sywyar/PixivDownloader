package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

/**
 * "安全"页：当前仅包含修改管理员密码的子面板。
 * 所有修改通过 POST /api/gui/change-password 走 GUI 令牌通道，
 * 不直接操作 setup_config.json，以确保运行中的后端会同步失效旧 session。
 */
@Slf4j
public class SecurityPanel extends JPanel {

    private final JPasswordField currentPasswordField = new JPasswordField(24);
    private final JPasswordField newPasswordField = new JPasswordField(24);
    private final JPasswordField confirmPasswordField = new JPasswordField(24);
    private final JButton submitButton = new JButton(message("gui.security.action.submit"));
    private final JLabel statusLabel = secondaryLabel(message("gui.security.status.idle"));

    private volatile boolean submitting;

    public SecurityPanel(int serverPort) {
        buildUi();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildChangePasswordCard());
        content.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JComponent buildChangePasswordCard() {
        JPanel panel = createCard(message("gui.security.card.change-password.title"));
        panel.setLayout(new BorderLayout(0, 12));

        JLabel desc = secondaryLabel(message("gui.security.card.change-password.description"));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        g.gridx = 0;
        g.gridy = row;
        g.weightx = 0;
        form.add(new JLabel(message("gui.security.field.current-password")), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(currentPasswordField, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        form.add(new JLabel(message("gui.security.field.new-password")), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(newPasswordField, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        form.add(new JLabel(message("gui.security.field.confirm-password")), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(confirmPasswordField, g);

        submitButton.addActionListener(e -> submitChangePassword());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(submitButton);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(desc);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(statusLabel);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(actions);

        panel.add(form, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private static JPanel createCard(String title) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleJustification(TitledBorder.LEFT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                border,
                BorderFactory.createEmptyBorder(10, 12, 12, 12)
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private void submitChangePassword() {
        if (submitting) {
            return;
        }
        String current = new String(currentPasswordField.getPassword());
        String next = new String(newPasswordField.getPassword());
        String confirm = new String(confirmPasswordField.getPassword());

        if (current.isEmpty()) {
            setStatus(message("gui.security.validation.current-required"));
            currentPasswordField.requestFocusInWindow();
            return;
        }
        if (next.isEmpty()) {
            setStatus(message("gui.security.validation.new-required"));
            newPasswordField.requestFocusInWindow();
            return;
        }
        if (next.length() < SwingHost.host().minimumPasswordLength()) {
            setStatus(message("gui.security.validation.weak-password"));
            newPasswordField.requestFocusInWindow();
            return;
        }
        if (!next.equals(confirm)) {
            setStatus(message("gui.security.validation.mismatch"));
            confirmPasswordField.requestFocusInWindow();
            return;
        }
        if (next.equals(current)) {
            setStatus(message("gui.security.validation.same-password"));
            newPasswordField.requestFocusInWindow();
            return;
        }

        submitting = true;
        refreshActionStates();
        setStatus(message("gui.security.action.submitting"));

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
                setStatus(message("gui.security.status.success"));
                JOptionPane.showMessageDialog(this,
                        message("gui.security.dialog.success.message"),
                        message("gui.security.dialog.success.title"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
            case INVALID_CURRENT -> {
                log.warn(logMessage("gui.security.log.change-password.invalid-current"));
                setStatus(message("gui.security.error.invalid-current"));
                currentPasswordField.requestFocusInWindow();
            }
            case WEAK_PASSWORD -> {
                log.warn(logMessage("gui.security.log.change-password.weak-password"));
                setStatus(message("gui.security.error.weak-password"));
                newPasswordField.requestFocusInWindow();
            }
            case SAME_PASSWORD -> {
                log.warn(logMessage("gui.security.log.change-password.same-password"));
                setStatus(message("gui.security.error.same-password"));
                newPasswordField.requestFocusInWindow();
            }
            case SETUP_INCOMPLETE -> {
                log.warn(logMessage("gui.security.log.change-password.failed", "setup-incomplete"));
                setStatus(message("gui.security.error.setup-incomplete"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.setup-incomplete"));
            }
            case SAVE_FAILED -> {
                log.error(logMessage("gui.security.log.change-password.failed", "save-failed"));
                setStatus(message("gui.security.error.save-failed"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.save-failed"));
            }
            case BACKEND_UNREACHABLE -> {
                log.error(logMessage("gui.security.log.change-password.backend-unreachable", outcome.detail()));
                setStatus(message("gui.security.error.backend-unreachable"));
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.security.error.backend-unreachable"));
            }
            default -> {
                log.error(logMessage("gui.security.log.change-password.failed",
                        outcome.detail() == null ? "unknown" : outcome.detail()));
                setStatus(message("gui.security.error.unexpected"));
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

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private static JLabel secondaryLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.GRAY);
        return label;
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

        static ChangePasswordOutcome unexpected(String detail) {
            return new ChangePasswordOutcome(Kind.UNEXPECTED, detail);
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
