package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.panel.AboutPanel;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;
import top.sywyar.pixivdownload.gui.panel.StatusPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/**
 * GUI 主窗口（800×600，可调整大小）。
 * 包含三个标签页：状态、配置、关于。
 * 关闭窗口时缩回系统托盘，不退出进程。
 */
public class MainFrame extends JFrame {

    private final StatusPanel statusPanel;

    public MainFrame(int serverPort, String rootFolder, Path configPath) {
        super("PixivDownload");
        setSize(800, 600);
        setMinimumSize(new Dimension(640, 480));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // 关闭按钮 = 缩回托盘
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
            }
        });

        // 应用图标
        try {
            var stream = MainFrame.class.getResourceAsStream("/static/favicon.ico");
            if (stream != null) {
                setIconImage(Toolkit.getDefaultToolkit().createImage(stream.readAllBytes()));
            }
        } catch (Exception ignored) {}

        // 标签页
        JTabbedPane tabs = new JTabbedPane();
        statusPanel = new StatusPanel(serverPort, rootFolder);
        tabs.addTab("状态", statusPanel);
        tabs.addTab("配置", new ConfigPanel(configPath));
        tabs.addTab("关于", new AboutPanel());

        setContentPane(tabs);
    }

    /** 释放资源（关闭轮询 Timer）。 */
    @Override
    public void dispose() {
        statusPanel.dispose();
        super.dispose();
    }
}
