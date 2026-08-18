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
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.PageKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.ScrollPolicy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * GUI 主窗口（960x720，可调整大小）。
 * 根页面的顺序、标题与滚动策略来自宿主提供的 {@link DesktopUiDocument}，本类只负责 Swing 渲染。
 * 关闭窗口时缩回系统托盘，不退出进程。
 */
public class MainFrame extends JFrame {

    private static final Dimension DEFAULT_SIZE = new Dimension(960, 720);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 560);

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final Supplier<DesktopUiDocument> desktopUiDocumentSupplier;
    private final Supplier<GuiConfigContributionSnapshot> guiConfigContributionSupplier;
    private final Supplier<GuiWebEntrySnapshot> guiWebEntrySupplier;
    private final GuiOnboardingSnapshot guiOnboarding;

    private JTabbedPane tabs;
    private Map<PageKind, Integer> pageIndexes = Map.of();
    private WelcomePanel welcomePanel;
    private StatusPanel statusPanel;
    private ToolsPanel toolsPanel;
    private ConfigPanel configPanel;
    private PluginsPanel pluginsPanel;

    public MainFrame(int serverPort, String rootFolder, Path configPath,
                     Supplier<DesktopUiDocument> desktopUiDocumentSupplier,
                     Supplier<GuiConfigContributionSnapshot> guiConfigContributionSupplier,
                     Supplier<GuiWebEntrySnapshot> guiWebEntrySupplier,
                     GuiOnboardingSnapshot guiOnboarding) {
        super(GuiMessages.get("app.name"));
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.configPath = configPath;
        this.desktopUiDocumentSupplier = Objects.requireNonNull(
                desktopUiDocumentSupplier, "desktopUiDocumentSupplier");
        this.guiConfigContributionSupplier = guiConfigContributionSupplier == null
                ? GuiConfigContributionSnapshot::empty
                : guiConfigContributionSupplier;
        this.guiWebEntrySupplier = guiWebEntrySupplier == null
                ? GuiWebEntrySnapshot::empty
                : guiWebEntrySupplier;
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
        tabs.setSelectedIndex(0);
    }

    private JTabbedPane buildTabs() {
        DesktopUiDocument document = desktopUiDocument();
        tabs = new JTabbedPane();
        pageIndexes = new EnumMap<>(PageKind.class);
        welcomePanel = null;
        toolsPanel = null;
        configPanel = null;
        pluginsPanel = null;
        // 迁移下载目录同步了 download.root-folder 后，刷新配置页让其立即显示新值。
        Runnable onConfigChanged = () -> {
            if (configPanel != null) {
                configPanel.reloadFromDisk();
            }
        };
        GuiWebEntrySnapshot currentWebEntries = guiWebEntries();
        statusPanel = new StatusPanel(serverPort, rootFolder, configPath,
                this::reloadLocale, onConfigChanged, currentWebEntries);

        for (DesktopUiDocument.Page page : document.pages()) {
            JComponent content = switch (page.kind()) {
                case WELCOME -> welcomePanel = new WelcomePanel(statusPanel, serverPort, guiOnboarding,
                        () -> {
                            showWindow();
                            selectPage(PageKind.WELCOME);
                        },
                        () -> selectPage(PageKind.STATUS));
                case STATUS -> statusPanel;
                // Web URL 构造复用状态页（scheme 按 SSL、主机名按域名推导，不写死协议 / 主机）。
                case CONFIG -> configPanel = new ConfigPanel(configPath, serverPort, statusPanel::getWebUrl,
                        ConfigFieldRegistry.snapshot(guiConfigContributions()),
                        this::reloadLocale, statusPanel::isUpdateInstalling);
                case PLUGINS -> pluginsPanel = new PluginsPanel(serverPort, statusPanel::getWebUrl);
                case TOOLS -> toolsPanel = new ToolsPanel(configPath);
                case SECURITY -> new SecurityPanel(serverPort);
                case ABOUT -> new AboutPanel();
            };
            tabs.addTab(SwingHost.host().message(page.titleI18nKey()),
                    applyScrollPolicy(page.scrollPolicy(), content));
            pageIndexes.put(page.kind(), tabs.getTabCount() - 1);
        }
        return tabs;
    }

    private JComponent applyScrollPolicy(ScrollPolicy policy, JComponent content) {
        return switch (policy) {
            case NONE -> content;
            case SCROLL_PANE -> {
                JScrollPane scroll = new JScrollPane(content);
                scroll.setBorder(null);
                scroll.getVerticalScrollBar().setUnitIncrement(16);
                scroll.getHorizontalScrollBar().setUnitIncrement(16);
                yield scroll;
            }
        };
    }

    /**
     * 在 GUI 语言切换后重建所有面板与标签页文案，使新 locale 立即生效，
     * 无需重启 JVM。同时刷新 JFrame 标题和系统托盘菜单。
     * 必须在 EDT 上调用。
     */
    public void reloadLocale() {
        PageKind previousPage = selectedPage();

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

        if (previousPage != null) selectPage(previousPage);

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

    private DesktopUiDocument desktopUiDocument() {
        return Objects.requireNonNull(desktopUiDocumentSupplier.get(),
                "desktopUiDocumentSupplier returned null");
    }

    private PageKind selectedPage() {
        if (tabs == null) return null;
        int selectedIndex = tabs.getSelectedIndex();
        return pageIndexes.entrySet().stream()
                .filter(entry -> entry.getValue() == selectedIndex)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void selectPage(PageKind page) {
        Integer index = pageIndexes.get(page);
        if (tabs != null && index != null) tabs.setSelectedIndex(index);
    }

    private GuiWebEntrySnapshot guiWebEntries() {
        GuiWebEntrySnapshot snapshot = guiWebEntrySupplier.get();
        return snapshot == null ? GuiWebEntrySnapshot.empty() : snapshot;
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
        if (statusPanel != null) {
            statusPanel.dispose();
        }
        if (toolsPanel != null) {
            toolsPanel.dispose();
        }
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
