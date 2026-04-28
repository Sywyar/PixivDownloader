package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
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

    /** 已安装的托盘图标，供热重载语言时刷新文案。 */
    private static volatile TrayIcon installedTrayIcon;
    /** 关联的主窗口与下载目录，重建菜单时复用。 */
    private static volatile MainFrame installedFrame;
    private static volatile String installedRootFolder;

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
            log.warn(logMessage("gui.tray.log.unsupported"));
            return false;
        }

        Image icon = loadIcon();
        TrayIcon trayIcon = new TrayIcon(icon, message("app.name"));
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
            installedTrayIcon = trayIcon;
            installedFrame = frame;
            installedRootFolder = rootFolder;
            log.debug(logMessage("gui.tray.log.installed"));
            return true;
        } catch (AWTException e) {
            log.warn(logMessage("gui.tray.log.install-failed", e.getMessage()));
            return false;
        }
    }

    /**
     * 在 GUI 语言切换后重建托盘菜单与 tooltip 文案，使其反映新 locale。
     * 若托盘未安装（headless 或不支持），静默忽略。
     */
    public static void refreshLocale() {
        TrayIcon trayIcon = installedTrayIcon;
        MainFrame frame = installedFrame;
        String rootFolder = installedRootFolder;
        if (trayIcon == null || frame == null) {
            return;
        }
        trayIcon.setToolTip(message("app.name"));
        // JPopupMenu 在每次右键时通过闭包捕获新菜单引用即可刷新；
        // 这里重建菜单并替换原 mouseListener 中引用的菜单。
        JPopupMenu newMenu = buildPopupMenu(frame, rootFolder, trayIcon);
        for (MouseListener listener : trayIcon.getMouseListeners()) {
            trayIcon.removeMouseListener(listener);
        }
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) { maybeShow(e); }
            @Override
            public void mousePressed(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    showAt(newMenu, p.x, p.y);
                }
            }
        });
    }

    private static JPopupMenu buildPopupMenu(MainFrame frame,
                                             String rootFolder, TrayIcon trayIcon) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem showItem = new JMenuItem(message("gui.tray.menu.show-main-window"));
        showItem.addActionListener(e -> showFrame(frame));
        menu.add(showItem);

        menu.addSeparator();

        JMenuItem batchItem = new JMenuItem(message("gui.action.open-batch"));
        batchItem.addActionListener(e -> openBrowser(frame.getBatchUrl()));
        menu.add(batchItem);

        JMenuItem monitorItem = new JMenuItem(message("gui.action.open-monitor"));
        monitorItem.addActionListener(e -> openBrowser(frame.getMonitorUrl()));
        menu.add(monitorItem);

        JMenuItem galleryItem = new JMenuItem(message("gui.action.open-gallery"));
        galleryItem.addActionListener(e -> openBrowser(frame.getGalleryUrl()));
        menu.add(galleryItem);

        JMenuItem folderItem = new JMenuItem(message("gui.action.open-download-directory"));
        folderItem.addActionListener(e -> openFolder(rootFolder));
        menu.add(folderItem);

        menu.addSeparator();

        JMenuItem exitItem = new JMenuItem(message("gui.action.exit"));
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
            log.warn(logMessage("gui.tray.log.open-browser.failed", e.getMessage()));
        }
    }

    private static void openFolder(String rootFolder) {
        try {
            Desktop.getDesktop().open(new java.io.File(rootFolder));
        } catch (Exception e) {
            log.warn(logMessage("gui.tray.log.open-folder.failed", e.getMessage()));
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
                log.warn(logMessage("gui.tray.log.favicon-fallback"));
            }
        } catch (Exception e) {
            log.warn(logMessage("gui.tray.log.icon-load.failed", e.getMessage()));
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

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
