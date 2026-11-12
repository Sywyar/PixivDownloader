package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.JButton;
import java.net.URI;

/** 设置与工具页共用同一语言的导入指南，打开浏览器不修改表单。 */
public final class LocalImportGuideButton extends JButton {
    public LocalImportGuideButton(DesktopUiHost host) {
        super(host.message("local-import.guide"));
        setName("local-import.guide");
        var shellAction = new SwingShellAction();
        addActionListener(event -> shellAction.run(
                "gui-open-local-import-guide",
                () -> host.openExternalUri(URI.create(host.message("local-import.guide-url"))),
                failure -> GuiErrorDialog.show(this, host.message("gui.dialog.error.title"),
                        host.message("gui.error.open-browser", failure.getMessage()))));
    }
}
