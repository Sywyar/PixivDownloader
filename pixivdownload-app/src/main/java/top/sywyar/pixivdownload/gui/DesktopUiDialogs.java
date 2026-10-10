package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Label;
import java.awt.Panel;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** 宿主启动流程使用且不依赖 Swing 的呈现桥。 */
final class DesktopUiDialogs {
    static final int INFORMATION_MESSAGE = 1;
    static final int WARNING_MESSAGE = 2;
    static final int ERROR_MESSAGE = 3;
    private static final Logger log = LoggerFactory.getLogger(DesktopUiDialogs.class);

    private DesktopUiDialogs() {}

    static void invokeLater(Runnable action) {
        if (action != null) action.run();
    }

    static void showMessageDialog(Object owner, Object message, String title, int messageType) {
        DesktopUiSession ui = owner instanceof DesktopUiSession session ? session : GuiLauncher.activeUi();
        if (ui == null) {
            log.warn("Desktop UI message before provider startup: {}: {}", title, message);
            return;
        }
        DesktopUiSession.MessageLevel level = switch (messageType) {
            case WARNING_MESSAGE -> DesktopUiSession.MessageLevel.WARNING;
            case ERROR_MESSAGE -> DesktopUiSession.MessageLevel.ERROR;
            default -> DesktopUiSession.MessageLevel.INFO;
        };
        ui.showMessage(level, title, String.valueOf(message));
    }

    static void show(Object owner, String title, String message, Throwable failure) {
        if (failure != null) log.error("{}: {}", title, message, failure);
        showMessageDialog(owner, message, title, ERROR_MESSAGE);
    }

    /** 无可用 provider 时保留可重新唤起的恢复窗口，打开浏览器不会丢失进程操作入口。 */
    static void showBootstrapRecoveryDialog(
            String title,
            String message,
            String marketLabel,
            String restartLabel,
            String exitLabel,
            String logsLabel,
            String failureMessage,
            Runnable openMarket,
            java.util.function.BooleanSupplier restart,
            java.util.function.BooleanSupplier exit,
            java.util.function.BooleanSupplier openLogs,
            java.util.function.Consumer<Runnable> activation
    ) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Desktop UI bootstrap message in a headless environment: {}: {}", title, message);
            return;
        }
        EventQueue.invokeLater(() -> {
            Dialog dialog = new Dialog((Frame) null, title, false);
            dialog.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
            dialog.setLayout(new BorderLayout(12, 12));
            var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int width = Math.min(720, Math.max(320, screen.width - 48));
            int textWidth = width - 64;
            var lines = wrapBootstrapText(message, dialog.getFontMetrics(dialog.getFont()), textWidth);
            Label body = new Label("") {
                @Override public Dimension getPreferredSize() {
                    return new Dimension(textWidth + 24, (lines.size() + 1) * getFontMetrics(getFont()).getHeight());
                }
                @Override public void paint(Graphics graphics) {
                    graphics.setFont(getFont());
                    graphics.setColor(getForeground());
                    var metrics = graphics.getFontMetrics();
                    int y = metrics.getHeight();
                    for (String line : lines) {
                        graphics.drawString(line, 12, y);
                        y += metrics.getHeight();
                    }
                }
            };
            body.getAccessibleContext().setAccessibleName(message);
            java.awt.ScrollPane scroll = new java.awt.ScrollPane(java.awt.ScrollPane.SCROLLBARS_AS_NEEDED);
            scroll.add(body);
            dialog.add(scroll, BorderLayout.CENTER);
            Label feedback = new Label("") {
                @Override public Dimension getPreferredSize() { return bootstrapTextSize(this, failureMessage); }
                @Override public void paint(Graphics graphics) {
                    if (isVisible()) paintBootstrapText(this, failureMessage, graphics);
                }
            };
            feedback.getAccessibleContext().setAccessibleName(failureMessage);
            feedback.setVisible(false);
            Panel buttons = new Panel(new java.awt.GridLayout(0, 1, 0, 8));
            Button restartButton = bootstrapButton(restartLabel);
            Button marketButton = bootstrapButton(marketLabel);
            Button exitButton = bootstrapButton(exitLabel);
            Button logsButton = bootstrapButton(logsLabel);
            Runnable requestExit = () -> {
                if (exit.getAsBoolean()) dialog.dispose();
                else { feedback.setVisible(true); dialog.validate(); }
            };
            restartButton.addActionListener(event -> {
                if (restart.getAsBoolean()) {
                    restartButton.setEnabled(false);
                    marketButton.setEnabled(false);
                    exitButton.setEnabled(false);
                    logsButton.setEnabled(false);
                } else {
                    feedback.setVisible(true);
                    dialog.validate();
                }
            });
            marketButton.addActionListener(event -> openMarket.run());
            exitButton.addActionListener(event -> requestExit.run());
            logsButton.addActionListener(event -> {
                logsButton.setEnabled(false);
                java.util.concurrent.CompletableFuture.supplyAsync(openLogs::getAsBoolean)
                        .whenComplete((opened, failure) -> EventQueue.invokeLater(() -> {
                            if (!dialog.isDisplayable()) return;
                            feedback.setVisible(failure != null || !Boolean.TRUE.equals(opened));
                            logsButton.setEnabled(restartButton.isEnabled());
                            dialog.validate();
                        }));
            });
            buttons.add(restartButton);
            buttons.add(marketButton);
            buttons.add(logsButton);
            buttons.add(exitButton);
            Panel footer = new Panel(new BorderLayout(8, 8));
            footer.add(feedback, BorderLayout.NORTH);
            footer.add(buttons, BorderLayout.CENTER);
            dialog.add(footer, BorderLayout.SOUTH);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { requestExit.run(); }
            });
            dialog.setSize(width, Math.min(640, Math.max(320, screen.height - 48)));
            dialog.setLocationRelativeTo(null);
            activation.accept(() -> EventQueue.invokeLater(() -> {
                if (dialog.isDisplayable()) { dialog.setVisible(true); dialog.toFront(); }
            }));
            dialog.setVisible(true);
            restartButton.requestFocus();
        });
    }

    static java.util.List<String> wrapBootstrapText(
            String text,
            java.awt.FontMetrics metrics,
            int width
    ) {
        var lines = new java.util.ArrayList<String>();
        for (String paragraph : text.split("\\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int cp = paragraph.codePointAt(offset);
                String next = new String(Character.toChars(cp));
                if (!line.isEmpty() && metrics.stringWidth(line + next) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
                offset += Character.charCount(cp);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static Button bootstrapButton(String label) {
        Button button = new Button("") {
            @Override public Dimension getPreferredSize() { return bootstrapTextSize(this, label); }
            @Override public void paint(Graphics graphics) { paintBootstrapText(this, label, graphics); }
        };
        button.getAccessibleContext().setAccessibleName(label);
        return button;
    }

    private static Dimension bootstrapTextSize(Component component, String text) {
        var metrics = component.getFontMetrics(component.getFont());
        return new Dimension(metrics.stringWidth(text) + 16, metrics.getHeight() + 10);
    }

    private static void paintBootstrapText(Component component, String text, Graphics graphics) {
        graphics.setFont(component.getFont());
        graphics.setColor(component.getForeground());
        var metrics = graphics.getFontMetrics();
        graphics.drawString(
                text,
                (component.getWidth() - metrics.stringWidth(text)) / 2,
                (component.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent()
        );
    }
}
