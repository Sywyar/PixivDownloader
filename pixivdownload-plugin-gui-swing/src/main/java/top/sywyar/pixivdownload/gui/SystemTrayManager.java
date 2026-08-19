package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

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
    private static volatile MainFrame installedFrame;
    private static volatile DesktopUiContext installedContext;

    private SystemTrayManager() {}

    /**
     * 在系统托盘中安装图标和菜单。
     *
     * @param frame 主窗口（托盘操作会控制其可见性）
     * @param context 工具包中性的桌面 UI 上下文
     * @return 安装是否成功（某些 Linux 桌面环境不支持系统托盘）
     */
    public static boolean install(MainFrame frame, DesktopUiContext context) {
        DesktopUiDocument.Tray tray = context.currentDocument().tray().orElse(null);
        if (tray == null) return false;
        if (!SystemTray.isSupported()) {
            log.warn(logMessage(context, "gui.tray.log.unsupported"));
            return false;
        }

        Image icon = loadIcon(context);
        TrayIcon trayIcon = new TrayIcon(icon, frame.resolveText(tray.tooltip()));
        trayIcon.setImageAutoSize(true);

        // 左键双击 = 显示主窗口
        trayIcon.addActionListener(e -> showFrame(frame));

        // 右键 = 弹出 Swing 菜单（使用绝对屏幕坐标，避免 TrayIcon 事件坐标不可靠）。
        // 每次右键都重建菜单：JPopupMenu 不挂在任何窗口树上，主题切换后的全局重涂
        // 够不到它，复用同一实例会让菜单停留在创建时的主题（例如启动深色后切到浅色仍是白字）。
        // 即时重建可同时跟随当前主题与语言，并避免 showAt 每次累积 PopupMenuListener。
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) { maybeShow(e); }
            @Override
            public void mousePressed(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    showAt(buildPopupMenu(frame, context), p.x, p.y);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
            installedTrayIcon = trayIcon;
            installedFrame = frame;
            installedContext = context;
            log.debug(logMessage(context, "gui.tray.log.installed"));
            return true;
        } catch (AWTException e) {
            log.warn(logMessage(context, "gui.tray.log.install-failed", e.getMessage()));
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
        DesktopUiContext context = installedContext;
        if (trayIcon == null || frame == null || context == null) return;
        DesktopUiDocument.Tray tray = context.currentDocument().tray().orElse(null);
        if (tray == null) {
            uninstall();
            return;
        }
        // 菜单已在每次右键时按当前 locale 重建，这里只需刷新 tooltip 文案。
        trayIcon.setToolTip(frame.resolveText(tray.tooltip()));
    }

    private static JPopupMenu buildPopupMenu(MainFrame frame, DesktopUiContext context) {
        JPopupMenu menu = new JPopupMenu();
        DesktopUiDocument.Tray tray = context.currentDocument().tray().orElse(null);
        if (tray == null) return menu;
        for (DesktopUiDocument.TrayItem descriptor : tray.items()) {
            if (descriptor.role() == DesktopUiDocument.TrayItemRole.SEPARATOR) {
                menu.addSeparator();
                continue;
            }
            JMenuItem item = new JMenuItem(frame.resolveText(descriptor.label()));
            item.addActionListener(event -> {
                if (descriptor.role() == DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW) {
                    showFrame(frame);
                } else {
                    context.dispatchEvent(new DesktopUiNode.Event(
                            DesktopUiNode.EventType.ACTIVATE, descriptor.id(), descriptor.actionId(),
                            DesktopUiNode.Value.empty()));
                }
            });
            menu.add(item);
        }
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

    public static void uninstall() {
        TrayIcon trayIcon = installedTrayIcon;
        MainFrame frame = installedFrame;
        installedTrayIcon = null;
        installedFrame = null;
        installedContext = null;
        if (frame != null) frame.setCloseToTray(false);
        if (trayIcon != null && SystemTray.isSupported()) SystemTray.getSystemTray().remove(trayIcon);
    }

    /**
     * 从 classpath 加载 favicon.ico 作为托盘图标。
     * 使用 MediaTracker 确保图片完全解码后再返回，避免出现空白图标。
     */
    private static Image loadIcon(DesktopUiContext context) {
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
                log.warn(logMessage(context, "gui.tray.log.favicon-fallback"));
            }
        } catch (Exception e) {
            log.warn(logMessage(context, "gui.tray.log.icon-load.failed", e.getMessage()));
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

    private static String logMessage(DesktopUiContext context, String code, Object... args) {
        return context.host().message(code, args);
    }
}
