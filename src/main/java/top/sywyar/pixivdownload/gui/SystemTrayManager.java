package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;

/**
 * 系统托盘图标管理器。
 * 关闭主窗口时缩回托盘；托盘右键"退出"才真正退出进程。
 * 使用 Swing {@link JPopupMenu}（而非 AWT {@link PopupMenu}），
 * 因为 Windows 上 AWT 原生菜单使用系统 ANSI 码页，会把中文渲染成方块。
 */
@Slf4j
public final class SystemTrayManager {

    private SystemTrayManager() {}

    /**
     * 在系统托盘中安装图标和菜单。
     *
     * @param frame      主窗口（托盘操作会控制其可见性，URL 从此处动态获取）
     * @param rootFolder 用于"打开下载目录"操作
     * @return 安装是否成功（某些 Linux 桌面环境不支持系统托盘）
     */
    public static boolean install(MainFrame frame, String rootFolder) {
        if (!SystemTray.isSupported()) {
            log.warn("当前系统不支持系统托盘");
            return false;
        }

        Image icon = loadIcon();
        TrayIcon trayIcon = new TrayIcon(icon, "PixivDownload");
        trayIcon.setImageAutoSize(true);

        // Swing 弹出菜单（支持中文，无需依赖 AWT ANSI 码页）
        JPopupMenu menu = buildPopupMenu(frame, rootFolder, trayIcon);

        // 左键双击 = 显示主窗口
        trayIcon.addActionListener(e -> showFrame(frame));

        // 右键 = 弹出 Swing 菜单（使用绝对屏幕坐标，避免 TrayIcon 事件坐标不可靠）
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) { maybeShow(e); }
            @Override
            public void mousePressed(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    showAt(menu, p.x, p.y);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
            log.debug("系统托盘图标已安装");
            return true;
        } catch (AWTException e) {
            log.warn("安装托盘图标失败: {}", e.getMessage());
            return false;
        }
    }

    private static JPopupMenu buildPopupMenu(MainFrame frame,
                                             String rootFolder, TrayIcon trayIcon) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem showItem = new JMenuItem("显示主窗口");
        showItem.addActionListener(e -> showFrame(frame));
        menu.add(showItem);

        JMenuItem browserItem = new JMenuItem("打开 Web 控制台");
        // 点击时才调用 frame.getMonitorUrl()，确保读到 Spring 启动后更新的 scheme/domain
        browserItem.addActionListener(e -> openBrowser(frame.getMonitorUrl()));
        menu.add(browserItem);

        JMenuItem folderItem = new JMenuItem("打开下载目录");
        folderItem.addActionListener(e -> openFolder(rootFolder));
        menu.add(folderItem);

        menu.addSeparator();

        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(exitItem);

        return menu;
    }

    /**
     * JPopupMenu 需挂到一个 window 上。借助一个隐藏的无装饰 JDialog 作为锚点，
     * 根据屏幕可用区域翻转弹出方向（靠近屏幕底部/右边时向上/左弹出），
     * 模拟 Windows 托盘菜单的原生定位行为。
     */
    private static void showAt(JPopupMenu menu, int screenX, int screenY) {
        Dimension pref = menu.getPreferredSize();
        Rectangle avail = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();

        int x = screenX;
        int y = screenY;
        if (x + pref.width > avail.x + avail.width)  x -= pref.width;
        if (y + pref.height > avail.y + avail.height) y -= pref.height;
        if (x < avail.x) x = avail.x;
        if (y < avail.y) y = avail.y;

        JDialog anchor = new JDialog();
        anchor.setUndecorated(true);
        anchor.setSize(1, 1);
        anchor.setLocation(x, y);
        anchor.setAlwaysOnTop(true);
        anchor.setFocusableWindowState(true);
        anchor.setVisible(true);

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                anchor.dispose();
            }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                anchor.dispose();
            }
        });
        menu.show(anchor, 0, 0);
        anchor.toFront();
    }

    private static void showFrame(MainFrame frame) {
        frame.showWindow();
    }

    private static void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
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

    /**
     * 从 classpath 加载 favicon.ico 作为托盘图标。
     * 使用 MediaTracker 确保图片完全解码后再返回，避免出现空白图标。
     */
    private static Image loadIcon() {
        try {
            var stream = SystemTrayManager.class.getResourceAsStream("/static/favicon.ico");
            if (stream != null) {
                byte[] bytes = stream.readAllBytes();
                Image img = Toolkit.getDefaultToolkit().createImage(bytes);
                MediaTracker tracker = new MediaTracker(new Canvas());
                tracker.addImage(img, 0);
                tracker.waitForAll();
                if (!tracker.isErrorAny()) {
                    return img;
                }
                log.warn("加载 favicon.ico 出错，使用备用图标");
            }
        } catch (Exception e) {
            log.warn("加载托盘图标失败，使用默认图标: {}", e.getMessage());
        }
        return createFallbackIcon();
    }

    private static Image createFallbackIcon() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
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
