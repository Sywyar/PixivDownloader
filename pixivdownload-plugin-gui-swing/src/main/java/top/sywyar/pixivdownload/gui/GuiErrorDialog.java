package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一的错误弹窗：一句简短的通用提示 + "打开日志文件(html)" / "打开日志文件(txt)" 按钮。
 * <p>
 * 弹窗**不展示任何详细错误信息**（异常 message、后端返回文本、PowerShell 输出等往往很长，
 * 塞进弹窗体验很差）。详细信息由调用方在弹窗前写入日志，
 * 用户通过下方按钮一键打开日志查看。
 * <p>
 * {@code message} 参数保留是为了调用点兼容/可读性（通常是已记入日志的那条上下文），
 * 但<strong>不会</strong>显示在弹窗里；弹窗体固定为 {@code gui.dialog.error.see-log}。
 */
@Slf4j
public final class GuiErrorDialog {

    private static final Path LOG_TXT = Path.of("log", "latest.log");
    private static final Path LOG_HTML = Path.of("log", "html", "latest.html");

    private GuiErrorDialog() {
    }

    /**
     * @param message 失败上下文（通常已由调用方记入日志）；仅用于可读性，<b>不会</b>显示在弹窗里。
     */
    @SuppressWarnings("unused")
    public static void show(Component parent, String title, String message) {
        SwingShellAction shellAction = new SwingShellAction();
        JButton htmlButton = new JButton(GuiMessages.get("gui.dialog.error.open-log-html"));
        JButton txtButton = new JButton(GuiMessages.get("gui.dialog.error.open-log-txt"));
        htmlButton.addActionListener(e -> openLog(parent, LOG_HTML, shellAction));
        txtButton.addActionListener(e -> openLog(parent, LOG_TXT, shellAction));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttonRow.add(htmlButton);
        buttonRow.add(txtButton);

        JOptionPane.showMessageDialog(parent,
                new Object[]{GuiMessages.get("gui.dialog.error.see-log"), buttonRow},
                title, JOptionPane.ERROR_MESSAGE);
    }

    private static void openLog(Component parent, Path logPath, SwingShellAction shellAction) {
        Path absolute = logPath.toAbsolutePath();
        shellAction.run(
                "gui-open-error-log",
                () -> {
                    if (!Files.isRegularFile(absolute)) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent,
                                GuiMessages.get("gui.dialog.error.log-missing", absolute),
                                GuiMessages.get("gui.dialog.info.title"), JOptionPane.INFORMATION_MESSAGE));
                        return;
                    }
                    SwingHost.host().openLocalPath(absolute);
                },
                failure -> {
                    log.warn(SwingHost.host().message("gui.dialog.error.log.open-failed",
                            absolute, failure.getMessage()), failure);
                    JOptionPane.showMessageDialog(parent,
                            GuiMessages.get("gui.dialog.error.open-log-failed", failure.getMessage()),
                            GuiMessages.get("gui.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
                }
        );
    }
}
