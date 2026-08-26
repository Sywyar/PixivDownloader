package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.GuiConfigContributionSnapshot;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntrySnapshot;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntrySpec;
import top.sywyar.pixivdownload.gui.onboarding.GuiOnboardingSnapshot;
import top.sywyar.pixivdownload.gui.panel.AboutPanel;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;
import top.sywyar.pixivdownload.gui.panel.PluginsPanel;
import top.sywyar.pixivdownload.gui.panel.SecurityPanel;
import top.sywyar.pixivdownload.gui.panel.StatusPanel;
import top.sywyar.pixivdownload.gui.panel.ToolsPanel;
import top.sywyar.pixivdownload.gui.panel.WelcomePanel;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * GUI 主窗口（960x720，可调整大小）。
 * 包含六个标签页：首页（引导未完成时）、状态、配置、工具、安全、关于。引导完成后首页标签页不显示，共五个标签页。
 * 关闭窗口时缩回系统托盘，不退出进程。
 */
public class MainFrame extends JFrame {

    private static final Dimension DEFAULT_SIZE = new Dimension(960, 720);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 560);
    static final String STATE_KEY_PROPERTY = "pixivdownload.swing.stateKey";
    static final String PASSWORD_STATE_KEY_PROPERTY = "pixivdownload.swing.passwordStateKey";
    private volatile boolean trayAvailable;
    private Dimension normalWindowSize;

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final Supplier<GuiConfigContributionSnapshot> guiConfigContributionSupplier;
    private final Supplier<GuiWebEntrySnapshot> guiWebEntrySupplier;
    private final GuiOnboardingSnapshot guiOnboarding;
    private final Map<String, String> interfacePreferenceDraft = new LinkedHashMap<>();

    private static final int STATUS_TAB_INDEX = 1;

    private JTabbedPane tabs;
    private WelcomePanel welcomePanel;
    private StatusPanel statusPanel;
    private ToolsPanel toolsPanel;
    private ConfigPanel configPanel;
    private PluginsPanel pluginsPanel;

    public MainFrame(int serverPort, String rootFolder, Path configPath) {
        this(serverPort, rootFolder, configPath,
                GuiConfigContributionSnapshot.empty(), GuiWebEntrySnapshot.empty(), GuiOnboardingSnapshot.empty());
    }

    public MainFrame(int serverPort, String rootFolder, Path configPath,
                     GuiConfigContributionSnapshot guiConfigContributions) {
        this(serverPort, rootFolder, configPath, guiConfigContributions,
                GuiWebEntrySnapshot.empty(), GuiOnboardingSnapshot.empty());
    }

    public MainFrame(int serverPort, String rootFolder, Path configPath,
                     GuiConfigContributionSnapshot guiConfigContributions,
                     GuiWebEntrySnapshot guiWebEntries,
                     GuiOnboardingSnapshot guiOnboarding) {
        this(serverPort, rootFolder, configPath, fixedConfigSnapshot(guiConfigContributions),
                fixedWebEntrySnapshot(guiWebEntries), guiOnboarding);
    }

    public MainFrame(int serverPort, String rootFolder, Path configPath,
                     Supplier<GuiConfigContributionSnapshot> guiConfigContributionSupplier,
                     GuiWebEntrySnapshot guiWebEntries,
                     GuiOnboardingSnapshot guiOnboarding) {
        this(serverPort, rootFolder, configPath, guiConfigContributionSupplier,
                fixedWebEntrySnapshot(guiWebEntries), guiOnboarding);
    }

    public MainFrame(int serverPort, String rootFolder, Path configPath,
                     Supplier<GuiConfigContributionSnapshot> guiConfigContributionSupplier,
                     Supplier<GuiWebEntrySnapshot> guiWebEntrySupplier,
                     GuiOnboardingSnapshot guiOnboarding) {
        super(GuiMessages.get("app.name"));
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.configPath = configPath;
        this.guiConfigContributionSupplier = guiConfigContributionSupplier == null
                ? GuiConfigContributionSnapshot::empty
                : guiConfigContributionSupplier;
        this.guiWebEntrySupplier = guiWebEntrySupplier == null
                ? GuiWebEntrySnapshot::empty
                : guiWebEntrySupplier;
        this.guiOnboarding = guiOnboarding == null ? GuiOnboardingSnapshot.empty() : guiOnboarding;
        WindowStateSnapshot savedWindowState = SwingHost.host().loadWindowState().orElse(null);
        setMinimumSize(MINIMUM_SIZE);
        setSize(restoredWindowSize(savedWindowState));
        normalWindowSize = getSize();
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalWindowSize();
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                persistWindowState();
                if (closeBehavior(trayAvailable) == CloseBehavior.HIDE) {
                    setVisible(false);
                } else {
                    SwingHost.context().requestApplicationExit();
                }
            }
        });

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

        setContentPane(buildTabs());

        // 彩蛋：任意界面连续按出「上上下下左右左右 BABA」即解锁「配置 → 服务器」下的调试模式复选框。
        KonamiCodeListener.install(DebugUnlockState::unlock);

        // 引导未完成前每次启动都停留在引导首页（含未完成配置的情况）；
        // “完成” = 首次安装已完成 且 整套引导已走到最后一页。任一不满足都还要展示引导。
        // setup 未完成却存在残留的旧标记 → 复位，避免未配置用户被错误地带过引导。
        // 整套引导已完成时 buildTabs() 不会再添加欢迎 tab，状态页位于第 0 个标签。
        var onboardingState = SwingHost.host().onboardingState(rootFolder);
        if (!onboardingState.complete() && !onboardingState.setupComplete()) {
            SwingHost.host().clearOnboardingState();
            onboardingState = SwingHost.host().onboardingState(rootFolder);
        }
        tabs.setSelectedIndex(0);
        if (savedWindowState != null && savedWindowState.maximized()) {
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
    }

    private JTabbedPane buildTabs() {
        tabs = new JTabbedPane();
        // 迁移下载目录同步了 download.root-folder 后，刷新配置页让其立即显示新值。
        Runnable onConfigChanged = () -> {
            if (configPanel != null) {
                configPanel.reloadFromDisk();
            }
        };
        GuiWebEntrySnapshot currentWebEntries = guiWebEntries();
        statusPanel = new StatusPanel(serverPort, rootFolder, configPath,
                this::reloadLocale, onConfigChanged, currentWebEntries);

        // 整套引导已走完后不再添加欢迎 tab，避免重复展示并消除针对后端的轮询请求。
        var onboardingState = SwingHost.host().onboardingState(rootFolder);
        if (!onboardingState.complete()) {
            welcomePanel = new WelcomePanel(statusPanel, serverPort, guiOnboarding,
                    () -> {
                        showWindow();
                        if (tabs != null) {
                            tabs.setSelectedIndex(0);
                        }
                    },
                    () -> { if (tabs != null) tabs.setSelectedIndex(STATUS_TAB_INDEX); });
            tabs.addTab(GuiMessages.get("gui.tab.welcome"), welcomePanel);
        } else {
            welcomePanel = null;
        }

        toolsPanel = new ToolsPanel(configPath);
        // Web URL 构造复用状态页（scheme 按 SSL、主机名按域名推导，不写死协议 / 主机），用于「打开 Web 插件市场 / 管理页」。
        configPanel = new ConfigPanel(configPath, serverPort, statusPanel::getWebUrl,
                ConfigFieldRegistry.snapshot(guiConfigContributions()),
                this::reloadLocale, statusPanel::isUpdateInstalling, interfacePreferenceDraft);
        pluginsPanel = new PluginsPanel(serverPort, statusPanel::getWebUrl);
        tabs.addTab(GuiMessages.get("gui.tab.status"), scrollableStatusPanel(statusPanel));
        tabs.addTab(GuiMessages.get("gui.tab.config"), configPanel);
        tabs.addTab(GuiMessages.get("gui.tab.plugins"), pluginsPanel);
        tabs.addTab(GuiMessages.get("gui.tab.tools"), toolsPanel);
        tabs.addTab(GuiMessages.get("gui.tab.security"), new SecurityPanel(serverPort));
        tabs.addTab(GuiMessages.get("gui.tab.about"), new AboutPanel());
        return tabs;
    }

    private JScrollPane scrollableStatusPanel(StatusPanel panel) {
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /**
     * 在 GUI 语言切换后重建所有面板与标签页文案，使新 locale 立即生效，
     * 无需重启 JVM。同时刷新 JFrame 标题和系统托盘菜单。
     * 必须在 EDT 上调用。
     */
    public void reloadLocale() {
        int previousTab = tabs == null ? 0 : tabs.getSelectedIndex();
        Map<String, ComponentState> componentState = captureState(tabs);

        if (welcomePanel != null) {
            welcomePanel.dispose();
        }
        if (statusPanel != null) {
            statusPanel.dispose();
        }
        if (toolsPanel != null) {
            toolsPanel.dispose();
        }
        if (pluginsPanel != null) {
            pluginsPanel.dispose();
        }

        setTitle(GuiMessages.get("app.name"));
        setContentPane(buildTabs());
        restoreState(tabs, componentState);

        if (previousTab >= 0 && previousTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(previousTab);
        }

        SystemTrayManager.refreshLocale();

        revalidate();
        repaint();
    }

    public String getBatchUrl() {
        return statusPanel.getBatchUrl();
    }

    public String getWebUrl(String path) {
        return statusPanel.getWebUrl(path);
    }

    public List<GuiWebEntrySpec> getTrayWebActions() {
        return guiWebEntries().trayActions();
    }

    private GuiConfigContributionSnapshot guiConfigContributions() {
        GuiConfigContributionSnapshot snapshot = guiConfigContributionSupplier.get();
        return snapshot == null ? GuiConfigContributionSnapshot.empty() : snapshot;
    }

    private GuiWebEntrySnapshot guiWebEntries() {
        GuiWebEntrySnapshot snapshot = guiWebEntrySupplier.get();
        return snapshot == null ? GuiWebEntrySnapshot.empty() : snapshot;
    }

    private static Supplier<GuiConfigContributionSnapshot> fixedConfigSnapshot(
            GuiConfigContributionSnapshot guiConfigContributions) {
        GuiConfigContributionSnapshot fixed = guiConfigContributions == null
                ? GuiConfigContributionSnapshot.empty()
                : guiConfigContributions;
        return () -> fixed;
    }

    private static Supplier<GuiWebEntrySnapshot> fixedWebEntrySnapshot(GuiWebEntrySnapshot guiWebEntries) {
        GuiWebEntrySnapshot fixed = guiWebEntries == null ? GuiWebEntrySnapshot.empty() : guiWebEntries;
        return () -> fixed;
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

    public void setTrayAvailable(boolean available) {
        trayAvailable = available;
    }

    public void persistWindowState() {
        rememberNormalWindowSize();
        Dimension size = normalWindowSize == null ? new Dimension(DEFAULT_SIZE) : normalWindowSize;
        SwingHost.host().saveWindowState(new WindowStateSnapshot(
                size.width,
                size.height,
                (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
        ));
    }

    private void rememberNormalWindowSize() {
        if ((getExtendedState() & (Frame.MAXIMIZED_BOTH | Frame.ICONIFIED)) == 0) {
            normalWindowSize = getSize();
        }
    }

    static Dimension defaultWindowSize() {
        return new Dimension(DEFAULT_SIZE);
    }

    static Dimension minimumWindowSize() {
        return new Dimension(MINIMUM_SIZE);
    }

    static Dimension restoredWindowSize(WindowStateSnapshot state) {
        if (state == null) return defaultWindowSize();
        return new Dimension(
                Math.max(MINIMUM_SIZE.width, state.width()),
                Math.max(MINIMUM_SIZE.height, state.height())
        );
    }

    static CloseBehavior closeBehavior(boolean trayAvailable) {
        return trayAvailable ? CloseBehavior.HIDE : CloseBehavior.EXIT;
    }

    static Map<String, ComponentState> captureState(Component root) {
        Map<String, ComponentState> state = new LinkedHashMap<>();
        captureState(root, "root", state);
        return state;
    }

    private static void captureState(Component component, String path, Map<String, ComponentState> state) {
        if (component == null) {
            return;
        }
        if (component instanceof JComponent value) {
            String key = stateKey(value, path);
            Integer tab = value instanceof JTabbedPane pane ? pane.getSelectedIndex() : null;
            Point scroll = value instanceof JScrollPane pane ? pane.getViewport().getViewPosition() : null;
            Integer divider = value instanceof JSplitPane pane ? pane.getDividerLocation() : null;
            Integer caret = value instanceof javax.swing.text.JTextComponent text ? text.getCaretPosition() : null;
            char[] password = null;
            if (value instanceof JPasswordField field) {
                char[] current = field.getPassword();
                try {
                    password = Arrays.copyOf(current, current.length);
                } finally {
                    Arrays.fill(current, '\0');
                }
            }
            boolean focused = value.isFocusOwner();
            if (tab != null || scroll != null || divider != null || caret != null || focused) {
                state.put(key, new ComponentState(tab, scroll, divider, caret, focused, password));
            }
        }
        if (component instanceof Container container) {
            Component[] children = container.getComponents();
            for (int index = 0; index < children.length; index++) {
                Component child = children[index];
                captureState(child, path + '/' + index + ':' + child.getClass().getName(), state);
            }
        }
    }

    static void restoreState(Component root, Map<String, ComponentState> state) {
        Objects.requireNonNull(state, "state");
        try {
            restoreState(root, "root", state);
        } finally {
            state.values().stream()
                    .map(ComponentState::password)
                    .filter(Objects::nonNull)
                    .forEach(password -> Arrays.fill(password, '\0'));
        }
    }

    private static void restoreState(Component component, String path, Map<String, ComponentState> state) {
        if (component == null) {
            return;
        }
        if (component instanceof JComponent value) {
            ComponentState saved = state.get(stateKey(value, path));
            if (saved != null) {
                if (value instanceof JTabbedPane pane && saved.tab() != null
                        && saved.tab() >= 0 && saved.tab() < pane.getTabCount()) {
                    pane.setSelectedIndex(saved.tab());
                }
                if (value instanceof JScrollPane pane && saved.scroll() != null) {
                    SwingUtilities.invokeLater(() -> pane.getViewport().setViewPosition(saved.scroll()));
                }
                if (value instanceof JSplitPane pane && saved.divider() != null) {
                    pane.setDividerLocation(saved.divider());
                }
                if (value instanceof JPasswordField field && saved.password() != null) {
                    field.setText(new String(saved.password()));
                }
                if (value instanceof javax.swing.text.JTextComponent text && saved.caret() != null) {
                    text.setCaretPosition(Math.min(saved.caret(), text.getDocument().getLength()));
                }
                if (saved.focused()) {
                    SwingUtilities.invokeLater(value::requestFocusInWindow);
                }
            }
        }
        if (component instanceof Container container) {
            Component[] children = container.getComponents();
            for (int index = 0; index < children.length; index++) {
                Component child = children[index];
                restoreState(child, path + '/' + index + ':' + child.getClass().getName(), state);
            }
        }
    }

    private static String stateKey(JComponent component, String path) {
        Object explicit = component instanceof JPasswordField
                ? component.getClientProperty(PASSWORD_STATE_KEY_PROPERTY)
                : component.getClientProperty(STATE_KEY_PROPERTY);
        return explicit instanceof String key && !key.isBlank()
                ? "explicit:" + key
                : "path:" + path + ':' + component.getClass().getName();
    }

    record ComponentState(
            Integer tab,
            Point scroll,
            Integer divider,
            Integer caret,
            boolean focused,
            char[] password
    ) {
    }

    enum CloseBehavior { HIDE, EXIT }

    @Override
    public void dispose() {
        if (welcomePanel != null) {
            welcomePanel.dispose();
        }
        statusPanel.dispose();
        toolsPanel.dispose();
        if (pluginsPanel != null) {
            pluginsPanel.dispose();
        }
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
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Image scaled(Image src, int size) {
        return src.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }
}
