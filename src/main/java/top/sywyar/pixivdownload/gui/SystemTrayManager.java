package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.net.URI;

/**
 * 系统托盘图标管理器。
 * 关闭主窗口时缩回托盘；托盘右键"退出"才真正退出进程。
 */
@Slf4j
public final class SystemTrayManager {

    private SystemTrayManager() {}

    /**
     * 在系统托盘中安装图标和菜单。
     *
     * @param frame      主窗口（托盘操作会控制其可见性）
     * @param serverPort 用于"打开 Web 控制台"操作
     * @param rootFolder 用于"打开下载目录"操作
     * @return 安装是否成功（某些 Linux 桌面环境不支持系统托盘）
     */
    public static boolean install(MainFrame frame, int serverPort, String rootFolder) {
        if (!SystemTray.isSupported()) {
            log.warn("当前系统不支持系统托盘");
            return false;
        }

        Image icon = loadIcon();
        TrayIcon trayIcon = new TrayIcon(icon, "PixivDownload");
        trayIcon.setImageAutoSize(true);

        // 双击图标 → 显示主窗口
        trayIcon.addActionListener(e -> showFrame(frame));

        // 右键菜单
        PopupMenu menu = new PopupMenu();

        MenuItem showItem = new MenuItem("显示主窗口");
        showItem.addActionListener(e -> showFrame(frame));
        menu.add(showItem);

        MenuItem browserItem = new MenuItem("打开 Web 控制台");
        browserItem.addActionListener(e -> openBrowser(serverPort));
        menu.add(browserItem);

        MenuItem folderItem = new MenuItem("打开下载目录");
        folderItem.addActionListener(e -> openFolder(rootFolder));
        menu.add(folderItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("退出");
        exitItem.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(exitItem);

        trayIcon.setPopupMenu(menu);

        try {
            SystemTray.getSystemTray().add(trayIcon);
            log.debug("系统托盘图标已安装");
            return true;
        } catch (AWTException e) {
            log.warn("安装托盘图标失败: {}", e.getMessage());
            return false;
        }
    }

    private static void showFrame(MainFrame frame) {
        frame.setVisible(true);
        frame.setState(Frame.NORMAL);
        frame.toFront();
    }

    private static void openBrowser(int port) {
        try {
            Desktop.getDesktop().browse(new URI("http://localhost:" + port + "/monitor.html"));
        } catch (Exception e) {
            log.warn("无法打开浏览器: {}", e.getMessage());
        }
    }

    private static void openFolder(String rootFolder) {
        try {
            Desktop.getDesktop().open(new java.io.File(rootFolder));
        } catch (Exception e) {
            log.warn("无法打开目录: {}", e.getMessage());
        }
    }

    private static Image loadIcon() {
        try {
            // 使用 classpath 中的 favicon.ico（已在 jpackage workflow 作为应用图标）
            var stream = SystemTrayManager.class.getResourceAsStream("/static/favicon.ico");
            if (stream != null) {
                return Toolkit.getDefaultToolkit().createImage(stream.readAllBytes());
            }
        } catch (Exception e) {
            log.warn("加载托盘图标失败，使用默认图标: {}", e.getMessage());
        }
        // 回退：生成一个简单的纯色图标
        return createFallbackIcon();
    }

    private static Image createFallbackIcon() {
        int size = 16;
        var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillOval(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Dialog", Font.BOLD, 10));
        FontMetrics fm = g.getFontMetrics();
        String text = "P";
        g.drawString(text, (size - fm.stringWidth(text)) / 2, (size + fm.getAscent()) / 2 - 1);
        g.dispose();
        return img;
    }
}
