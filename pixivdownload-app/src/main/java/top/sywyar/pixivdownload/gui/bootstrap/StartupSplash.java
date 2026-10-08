package top.sywyar.pixivdownload.gui.bootstrap;

import top.sywyar.pixivdownload.common.AppInfo;

import javax.imageio.ImageIO;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.ScrollPane;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** 插件加载前的 JDK AWT 引导窗口，首个桌面窗口接管后释放全部资源。 */
public final class StartupSplash implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private volatile String status = "";
    private volatile StartupAppearance systemAppearance;
    private volatile String theme;
    private volatile Timer timer;
    private Window window;
    private StartupSplashView view;
    private AWTEventListener handoff;
    private Rectangle workArea;
    private Dimension contentSize;
    private StartupAppearance displayedAppearance;
    private String displayedStatus;
    private int frame;

    public StartupSplash() { }

    /** 文案由入口按实际阶段和当前语言提供，加载动画不推算完成比例。 */
    public void showStatus(String text) {
        status = text == null ? "" : text;
        if (closed.get()) return;
        if (started.compareAndSet(false, true)) open();
        else repaint();
    }

    private void open() {
        if (GraphicsEnvironment.isHeadless()) {
            close();
            return;
        }
        try {
            theme = StartupThemePreferences.read();
            systemAppearance = StartupAppearance.detect();
            BufferedImage icon = null;
            try (var input = StartupSplash.class.getResourceAsStream("/bootstrap/icon.png")) {
                icon = input == null ? null : ImageIO.read(input);
            } catch (IOException unavailable) { /* 图标损坏时仍显示标题和启动阶段。 */ }
            BufferedImage image = icon;
            onEventThread(() -> createWindow(image));
            if (closed.get()) return;
            Timer updates = new Timer("startup-splash", true);
            timer = updates;
            if (closed.get()) { updates.cancel(); return; }
            updates.schedule(new TimerTask() {
                private int ticks;
                @Override public void run() {
                    if (closed.get()) return;
                    try {
                        if (++ticks % 20 == 0) {
                            systemAppearance = StartupAppearance.detect();
                            theme = StartupThemePreferences.read();
                        }
                        repaint();
                    } catch (RuntimeException | LinkageError unavailable) { close(); }
                }
            }, 100, 100);
        } catch (RuntimeException | LinkageError unavailable) {
            close();
        }
    }

    private void createWindow(BufferedImage icon) {
        if (closed.get()) return;
        var environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        var pointer = MouseInfo.getPointerInfo();
        GraphicsConfiguration configuration = pointer == null
                ? environment.getDefaultScreenDevice().getDefaultConfiguration() : pointer.getDevice().getDefaultConfiguration();
        window = new Window(null, configuration);
        window.setName("pixivdownload-startup-splash");
        window.setType(Window.Type.UTILITY);
        window.setAutoRequestFocus(false);
        window.setFocusableWindowState(false);
        window.getAccessibleContext().setAccessibleName(AppInfo.NAME);
        view = new StartupSplashView(icon, systemAppearance.withTheme(theme), status);
        window.setLayout(new BorderLayout());
        window.add(view);
        handoff = event -> {
            if (event instanceof WindowEvent opened && opened.getID() == WindowEvent.WINDOW_OPENED
                    && opened.getWindow() != window && opened.getWindow().isVisible()
                    && opened.getWindow().getType() != Window.Type.POPUP) close();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(handoff, AWTEvent.WINDOW_EVENT_MASK);
        updateWindow();
        window.setVisible(true);
        var graphics = window.getGraphics();
        if (graphics != null) {
            try { window.paintAll(graphics); Toolkit.getDefaultToolkit().sync(); }
            finally { graphics.dispose(); }
        }
    }

    private void repaint() {
        if (closed.get() || !repaintPending.compareAndSet(false, true)) return;
        EventQueue.invokeLater(() -> {
            try {
                if (!closed.get() && window != null) updateWindow();
            } catch (RuntimeException | LinkageError unavailable) {
                close();
            } finally { repaintPending.set(false); }
        });
    }

    private void updateWindow() {
        StartupAppearance appearance = systemAppearance.withTheme(theme);
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        Rectangle available = usableBounds(configuration.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(configuration));
        String text = status;
        view.update(appearance, text, frame++);
        window.getAccessibleContext().setAccessibleDescription(text);
        if (!available.equals(workArea) || !appearance.equals(displayedAppearance) || !text.equals(displayedStatus)) {
            var layout = view.arrange(new Dimension(Math.max(1, available.width - 32), Math.max(1, available.height - 32)));
            Dimension desired = new Dimension(layout.width(), layout.height());
            Rectangle bounds = centeredBounds(available, desired);
            if (!desired.equals(contentSize) || !available.equals(workArea)) {
                window.removeAll();
                if (desired.height > bounds.height || desired.width > bounds.width) {
                    var scroll = new ScrollPane(ScrollPane.SCROLLBARS_AS_NEEDED);
                    scroll.setBackground(view.getBackground());
                    scroll.add(view);
                    window.add(scroll);
                } else window.add(view);
                window.setBounds(bounds);
                if (configuration.getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
                    try { window.setShape(new RoundRectangle2D.Double(0, 0, bounds.width, bounds.height, 20, 20)); }
                    catch (UnsupportedOperationException unsupported) { /* 窗口管理器不支持裁形时保留矩形。 */ }
                }
                window.validate();
            }
            window.setBackground(view.getBackground());
            contentSize = desired;
            workArea = available;
            displayedAppearance = appearance;
            displayedStatus = text;
        }
        view.repaint();
    }

    static Rectangle usableBounds(Rectangle screen, Insets insets) {
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                Math.max(1, screen.width - insets.left - insets.right), Math.max(1, screen.height - insets.top - insets.bottom));
    }

    static Rectangle centeredBounds(Rectangle available, Dimension desired) {
        int width = Math.min(desired.width, Math.max(1, available.width - 32));
        int height = Math.min(desired.height, Math.max(1, available.height - 32));
        return new Rectangle(available.x + (available.width - width) / 2,
                available.y + (available.height - height) / 2, width, height);
    }

    private static void onEventThread(Runnable action) {
        if (EventQueue.isDispatchThread()) { action.run(); return; }
        try { EventQueue.invokeAndWait(action); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Timer updates = timer;
        if (updates != null) updates.cancel();
        if (!started.get()) return;
        Runnable dispose = () -> {
            if (handoff != null) Toolkit.getDefaultToolkit().removeAWTEventListener(handoff);
            if (window != null) { window.dispose(); window = null; }
            view = null;
        };
        if (EventQueue.isDispatchThread()) dispose.run();
        else if (!GraphicsEnvironment.isHeadless()) EventQueue.invokeLater(dispose);
    }
}
