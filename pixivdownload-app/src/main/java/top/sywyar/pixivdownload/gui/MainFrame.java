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

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * GUI 主窗口（960x720，可调整大小）。
 * 包含六个标签页：首页（引导未完成时）、状态、配置、工具、安全、关于。引导完成后首页标签页不显示，共五个标签页。
 * 关闭窗口时缩回系统托盘，不退出进程。
 */
public class MainFrame extends JFrame {

    private static final Dimension DEFAULT_SIZE = new Dimension(960, 720);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 560);

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final GuiConfigContributionSnapshot guiConfigContributions;
    private final GuiWebEntrySnapshot guiWebEntries;
    private final GuiOnboardingSnapshot guiOnboarding;

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
        super(GuiMessages.get("app.name"));
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.configPath = configPath;
        this.guiConfigContributions = guiConfigContributions == null
                ? GuiConfigContributionSnapshot.empty()
                : guiConfigContributions;
        this.guiWebEntries = guiWebEntries == null ? GuiWebEntrySnapshot.empty() : guiWebEntries;
        this.guiOnboarding = guiOnboarding == null ? GuiOnboardingSnapshot.empty() : guiOnboarding;
        setSize(DEFAULT_SIZE);
        setMinimumSize(MINIMUM_SIZE);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
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
        if (!OnboardingState.isComplete(rootFolder) && !OnboardingState.isSetupComplete(rootFolder)) {
            OnboardingState.clear();
        }
        tabs.setSelectedIndex(0);
    }

    private JTabbedPane buildTabs() {
        tabs = new JTabbedPane();
        // 迁移下载目录同步了 download.root-folder 后，刷新配置页让其立即显示新值。
        Runnable onConfigChanged = () -> {
            if (configPanel != null) {
                configPanel.reloadFromDisk();
            }
        };
        statusPanel = new StatusPanel(serverPort, rootFolder, configPath,
                this::reloadLocale, onConfigChanged, guiWebEntries);

        // 整套引导已走完后不再添加欢迎 tab，避免重复展示并消除针对后端的轮询请求。
        if (!OnboardingState.isComplete(rootFolder)) {
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
                ConfigFieldRegistry.snapshot(guiConfigContributions));
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

        if (previousTab >= 0 && previousTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(previousTab);
        }

        SystemTrayManager.refreshLocale();

        revalidate();
        repaint();
    }

    public String getMonitorUrl() {
        return statusPanel.getMonitorUrl();
    }

    public String getBatchUrl() {
        return statusPanel.getBatchUrl();
    }

    public String getWebUrl(String path) {
        return statusPanel.getWebUrl(path);
    }

    public List<GuiWebEntrySpec> getTrayWebActions() {
        return guiWebEntries.trayActions();
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
