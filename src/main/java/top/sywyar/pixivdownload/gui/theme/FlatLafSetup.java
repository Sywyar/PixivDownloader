package top.sywyar.pixivdownload.gui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FlatLaf 主题初始化、运行时切换与跨平台中文字体回退链。
 * <p>持有当前 {@link ThemePreference}；在 {@link ThemePreference#SYSTEM} 模式下启动一个 30s 间隔的
 * 轮询计时器跟踪操作系统浅/深变化并自动重涂。其它模式下计时器停止。
 */
@Slf4j
public final class FlatLafSetup {

    /**
     * 优先级（依次尝试，取首个可用）：
     * 1. Microsoft YaHei UI  (Windows 默认中文字体)
     * 2. PingFang SC         (macOS)
     * 3. Noto Sans CJK SC    (Linux 常见)
     * 4. Source Han Sans SC  (思源黑体备选)
     * 5. SimSun              (Windows 老版本兜底)
     * 6. Dialog              (Java 逻辑字体，最后兜底)
     */
    private static final String[] FONT_PRIORITY = {
            "Microsoft YaHei UI",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "SimSun",
            "Dialog"
    };

    // 仅用于捕捉用户在系统设置里手动切换浅 / 深色这类罕见事件；探测会 fork reg.exe / defaults，
    // 间隔取 30s 在响应性与开销之间折中，避免长时间驻留托盘时持续 fork 外部进程。
    private static final int SYSTEM_POLL_INTERVAL_MS = 30_000;

    private static volatile ThemePreference currentPreference = ThemePreference.SYSTEM;
    private static volatile boolean currentDark;
    private static Timer systemWatcher;
    /** 系统主题探测是否仍在进行，避免慢速 reg.exe / defaults 下后台线程逐 tick 堆积。 */
    private static final AtomicBoolean systemProbeInFlight = new AtomicBoolean(false);
    private static final List<Runnable> changeListeners = new ArrayList<>();

    private FlatLafSetup() {}

    /**
     * 必须在 EDT 上调用（创建 JFrame 之前）。按给定偏好选择并安装 Light/Dark LAF，
     * 然后应用中文字体回退。{@link ThemePreference#SYSTEM} 模式下会启动后台轮询，
     * 跟随操作系统切换。
     */
    public static void apply(ThemePreference preference) {
        currentPreference = preference == null ? ThemePreference.SYSTEM : preference;
        boolean dark = resolveDarkFor(currentPreference);
        installLaf(dark);
        currentDark = dark;
        applyChineseFont();
        updateSystemWatcher();
    }

    /** 兼容入口：默认按"跟随系统"应用。 */
    public static void apply() {
        apply(ThemePreference.SYSTEM);
    }

    /**
     * 运行时切换主题偏好：立即重新安装 LAF 并刷新所有已打开窗口；
     * 必须在 EDT 上调用。
     */
    public static void setPreference(ThemePreference preference) {
        ThemePreference next = preference == null ? ThemePreference.SYSTEM : preference;
        currentPreference = next;
        boolean dark = resolveDarkFor(next);
        applyDarkInternal(dark);
        updateSystemWatcher();
    }

    public static ThemePreference currentPreference() {
        return currentPreference;
    }

    public static boolean isCurrentDark() {
        return currentDark;
    }

    /** 注册主题变更回调（任意线程调用，回调本身在 EDT 上触发）。 */
    public static void addChangeListener(Runnable listener) {
        if (listener == null) return;
        synchronized (changeListeners) {
            changeListeners.add(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener == null) return;
        synchronized (changeListeners) {
            changeListeners.remove(listener);
        }
    }

    private static boolean resolveDarkFor(ThemePreference preference) {
        return switch (preference) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> SystemThemeDetector.isSystemDark();
        };
    }

    private static void installLaf(boolean dark) {
        try {
            // 走 FlatLaf 自身的 globalExtraDefaults 机制：在 setup() 之前注入 / 清空，
            // 让 FlatLaf 自己的属性解析器把 @background / @foreground 这类引用
            // 级联到所有派生键（Panel.background、MenuItem.foreground、List.foreground、ComboBox.background 等）。
            // 直接 UIManager.put 每个具体键无法覆盖到惰性创建的弹出菜单等组件，会出现
            // 切到深色后弹出框文本仍为黑色的现象。
            FlatLaf.setGlobalExtraDefaults(dark ? buildFrontendDarkExtras() : null);
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        } catch (Exception e) {
            log.warn(logMessage("gui.theme.log.flatlaf.init-failed", e.getMessage()));
        }
    }

    /**
     * 前端 dark 主题（{@code pixiv-gallery.css} 等）的 CSS 变量色号，
     * 作为 FlatLaf 全局 extra defaults 注入。
     * <p>注意：FlatLaf 的 globalExtraDefaults 只会按 key 直接 putAll，
     * 不会重新解析 base 属性中已经 resolve 过的 {@code $@background} 等级联引用。
     * 因此必须显式列出每个具体的 UIManager key（而不是仅写 {@code @background}），
     * 否则惰性创建的右键弹出菜单、组合框下拉等组件依旧拿到 FlatDarkLaf 的原始默认色，
     * 在深 ↔ 浅切换时观感不一致。
     * <p>对应的前端 dark 主题色号：
     * <ul>
     *   <li>{@code --bg #101216} → 主面板 / 标签页 / 视口背景</li>
     *   <li>{@code --surface #171a21} → 浮层背景（菜单 / 弹出菜单 / 下拉 / 工具提示）</li>
     *   <li>{@code --text #f4f7fb} → 主文本前景</li>
     *   <li>{@code --muted #8d98aa} → 次级 / 禁用文本</li>
     *   <li>{@code --brand #4bb3ff} → 高亮选择背景</li>
     *   <li>{@code --line #2a303b} → 分隔线 / 边框</li>
     * </ul>
     * 浅色模式调用 {@link FlatLaf#setGlobalExtraDefaults} 传 {@code null} 即可彻底清除，
     * FlatLightLaf 自身的默认色随之恢复。
     */
    private static Map<String, String> buildFrontendDarkExtras() {
        final String bg = "#101216";
        final String surface = "#171a21";
        final String text = "#f4f7fb";
        final String muted = "#8d98aa";
        final String brand = "#4bb3ff";
        final String line = "#2a303b";

        Map<String, String> extras = new LinkedHashMap<>();
        // 面板 / 视口 / 标签页 / 根面板 —— 主体背景
        extras.put("Panel.background", bg);
        extras.put("Panel.foreground", text);
        extras.put("TabbedPane.background", bg);
        extras.put("TabbedPane.contentAreaColor", bg);
        extras.put("TabbedPane.foreground", text);
        extras.put("Viewport.background", bg);
        extras.put("ScrollPane.background", bg);
        extras.put("SplitPane.background", bg);
        extras.put("RootPane.background", bg);

        // 文本通用前景
        extras.put("Label.foreground", text);
        extras.put("Label.disabledForeground", muted);
        extras.put("TitledBorder.titleColor", text);
        extras.put("ToolTip.background", surface);
        extras.put("ToolTip.foreground", text);
        extras.put("Separator.foreground", line);
        extras.put("Component.borderColor", line);

        // 右键 / 下拉菜单及其内部所有菜单项类型
        extras.put("MenuBar.background", surface);
        extras.put("MenuBar.foreground", text);
        extras.put("PopupMenu.background", surface);
        extras.put("PopupMenu.foreground", text);
        extras.put("PopupMenu.borderColor", line);
        extras.put("PopupMenuSeparator.background", surface);
        extras.put("Menu.background", surface);
        extras.put("Menu.foreground", text);
        extras.put("Menu.disabledForeground", muted);
        extras.put("Menu.selectionBackground", brand);
        extras.put("Menu.selectionForeground", "#ffffff");
        extras.put("MenuItem.background", surface);
        extras.put("MenuItem.foreground", text);
        extras.put("MenuItem.disabledForeground", muted);
        extras.put("MenuItem.acceleratorForeground", muted);
        extras.put("MenuItem.selectionBackground", brand);
        extras.put("MenuItem.selectionForeground", "#ffffff");
        extras.put("CheckBoxMenuItem.background", surface);
        extras.put("CheckBoxMenuItem.foreground", text);
        extras.put("CheckBoxMenuItem.selectionBackground", brand);
        extras.put("CheckBoxMenuItem.selectionForeground", "#ffffff");
        extras.put("RadioButtonMenuItem.background", surface);
        extras.put("RadioButtonMenuItem.foreground", text);
        extras.put("RadioButtonMenuItem.selectionBackground", brand);
        extras.put("RadioButtonMenuItem.selectionForeground", "#ffffff");

        // JComboBox 下拉与 JList
        extras.put("ComboBox.background", surface);
        extras.put("ComboBox.foreground", text);
        extras.put("ComboBox.buttonBackground", surface);
        extras.put("ComboBox.selectionBackground", brand);
        extras.put("ComboBox.selectionForeground", "#ffffff");
        extras.put("List.background", surface);
        extras.put("List.foreground", text);
        extras.put("List.selectionBackground", brand);
        extras.put("List.selectionForeground", "#ffffff");

        return extras;
    }

    private static void applyDarkInternal(boolean dark) {
        if (dark == currentDark) {
            // 无变化但仍允许偏好级别的切换持久化 / 监听器联动
            notifyChange();
            return;
        }
        installLaf(dark);
        applyChineseFont();
        currentDark = dark;
        try {
            // FlatLaf 的内置全局刷新：对当前所有 Window 调用 updateComponentTreeUI。
            FlatLaf.updateUI();
        } catch (Exception e) {
            log.warn(logMessage("gui.theme.log.update-ui.failed", e.getMessage()));
        }
        notifyChange();
    }

    private static void notifyChange() {
        List<Runnable> snapshot;
        synchronized (changeListeners) {
            snapshot = new ArrayList<>(changeListeners);
        }
        for (Runnable r : snapshot) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            } catch (Exception e) {
                log.debug(logMessage("gui.theme.log.listener-failed", e.getMessage()));
            }
        }
    }

    private static void updateSystemWatcher() {
        boolean shouldWatch = currentPreference == ThemePreference.SYSTEM;
        if (shouldWatch) {
            if (systemWatcher == null) {
                systemWatcher = new Timer(SYSTEM_POLL_INTERVAL_MS, e -> pollSystemTheme());
                systemWatcher.setInitialDelay(SYSTEM_POLL_INTERVAL_MS);
                systemWatcher.start();
            } else if (!systemWatcher.isRunning()) {
                systemWatcher.start();
            }
        } else if (systemWatcher != null && systemWatcher.isRunning()) {
            systemWatcher.stop();
        }
    }

    private static void pollSystemTheme() {
        if (currentPreference != ThemePreference.SYSTEM) {
            return;
        }
        // 上一次探测尚未返回时跳过本轮，避免后台线程堆积。
        if (!systemProbeInFlight.compareAndSet(false, true)) {
            return;
        }
        // 系统主题探测会 fork reg.exe / defaults，可能阻塞数百毫秒；
        // 在后台线程上完成，再把结果回投到 EDT 上比对 / 应用。
        Thread worker = new Thread(() -> {
            try {
                boolean dark = SystemThemeDetector.isSystemDark();
                SwingUtilities.invokeLater(() -> {
                    if (currentPreference != ThemePreference.SYSTEM) {
                        return;
                    }
                    if (dark != currentDark) {
                        applyDarkInternal(dark);
                    }
                });
            } finally {
                systemProbeInFlight.set(false);
            }
        }, "gui-theme-watcher");
        worker.setDaemon(true);
        worker.start();
    }

    private static void applyChineseFont() {
        String[] available = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        java.util.Set<String> availableSet = new java.util.HashSet<>(java.util.Arrays.asList(available));

        for (String name : FONT_PRIORITY) {
            if (availableSet.contains(name)) {
                Font font = new Font(name, Font.PLAIN, 13);
                UIManager.put("defaultFont", font);
                log.debug(logMessage("gui.theme.log.font.applied", name));
                return;
            }
        }
        log.warn(logMessage("gui.theme.log.font.fallback"));
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
