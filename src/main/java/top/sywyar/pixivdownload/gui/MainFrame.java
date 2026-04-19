package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.panel.AboutPanel;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;
import top.sywyar.pixivdownload.gui.panel.StatusPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;

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

        // 应用图标：需使用 MediaTracker 等待图像解码完成，否则 Swing 会拒绝未就绪的图像
        Image appIcon = loadAppIcon();
        if (appIcon != null) {
            setIconImages(List.of(
                    scaled(appIcon, 16),
                    scaled(appIcon, 24),
                    scaled(appIcon, 32),
                    scaled(appIcon, 48),
                    scaled(appIcon, 64)
            ));
        }

        // 标签页
        JTabbedPane tabs = new JTabbedPane();
        statusPanel = new StatusPanel(serverPort, rootFolder, configPath);
        tabs.addTab("状态", statusPanel);
        tabs.addTab("配置", new ConfigPanel(configPath));
        tabs.addTab("关于", new AboutPanel());

        setContentPane(tabs);
    }

    /** 返回当前生效的 Web 控制台 URL，供托盘菜单等延迟调用。 */
    public String getMonitorUrl() {
        return statusPanel.getMonitorUrl();
    }

    public void showWindow() {
        if (!isVisible()) {
            setVisible(true);
        }

        int state = getExtendedState();
        if ((state & Frame.ICONIFIED) != 0) {
            setExtendedState(state & ~Frame.ICONIFIED);
        }

        toFront();
        requestFocus();
    }

    /** 释放资源（关闭轮询 Timer）。 */
    @Override
    public void dispose() {
        statusPanel.dispose();
        super.dispose();
    }

    private static Image loadAppIcon() {
        try {
            var stream = MainFrame.class.getResourceAsStream("/static/favicon.ico");
            if (stream != null) {
                byte[] bytes = stream.readAllBytes();
                Image img = Toolkit.getDefaultToolkit().createImage(bytes);
                MediaTracker tracker = new MediaTracker(new Canvas());
                tracker.addImage(img, 0);
                tracker.waitForAll();
                if (!tracker.isErrorAny()) {
                    return img;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Image scaled(Image src, int size) {
        return src.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }
}
