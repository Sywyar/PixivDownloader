package top.sywyar.pixivdownload.gui.panel;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.panel.configtab.GuiConfigTestClient;
import top.sywyar.pixivdownload.gui.plugin.GuiPluginStatusModel;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.function.Function;

/**
 * “插件”页：在桌面端只读展示已发现插件的安装 / 运行状态（外置统计插件等），并把启用 / 停用 / 安装 / 卸载等写操作清楚地
 * 引导到 Web 插件管理页。
 *
 * <p><b>不自行扫描、不绕过后端</b>：状态全部来自 {@code GET /api/gui/plugins/status}（经 {@code GuiConfigTestClient} 的
 * 本机 + GUI token 通道），后端再委托核心 {@code PluginManagementService}——GUI 与 Web 管理页共享同一份状态语义。本页
 * <b>不</b>提供运行期生命周期动词按钮（load / start / quiesce / stop / unload / reload），那些经 Web 插件管理页的 ADMIN
 * 接口执行，本页不放宽任何鉴权 / 校验边界。
 *
 * <p>纯展示模型与标签解析在 {@link GuiPluginStatusModel}（无 Swing、可 headless 测试）；本类只负责渲染与按需刷新。
 */
@Slf4j
public class PluginsPanel extends JPanel {

    private static final String PLUGIN_MANAGE_PAGE = "/plugin-manage.html";
    private static final int READ_TIMEOUT_MS = 5000;

    private final GuiConfigTestClient client;
    private final Function<String, String> webUrlProvider;

    private final JLabel stateLabel = new JLabel();
    private final JPanel recoveryBanner = new JPanel(new BorderLayout());
    private final JLabel recoveryLabel = new JLabel();
    private final JPanel listPanel = new JPanel();
    private final JButton refreshButton = new JButton(message("gui.plugins.action.refresh"));

    private final BackendLifecycleManager.Listener backendListener = this::onBackendStateChanged;
    private volatile boolean disposed;
    private volatile boolean refreshing;

    /**
     * @param serverPort     本机后端端口（用于本机 {@code /api/gui/**} 调用）
     * @param webUrlProvider 由状态页提供的 Web URL 构造器（scheme / 主机名按 SSL 与域名推导、不写死），用于「打开 Web
     *                       插件管理页」按钮
     */
    public PluginsPanel(int serverPort, Function<String, String> webUrlProvider) {
        this.client = new GuiConfigTestClient(serverPort);
        this.webUrlProvider = webUrlProvider;
        buildUi();
        BackendLifecycleManager.addListener(backendListener);
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildActionButtons(), BorderLayout.SOUTH);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(message("gui.plugins.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea intro = new JTextArea(message("gui.plugins.intro"));
        intro.setEditable(false);
        intro.setOpaque(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setFocusable(false);
        intro.setBorder(null);
        intro.setFont(UIManager.getFont("Label.font"));
        intro.setForeground(Color.GRAY);
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);

        recoveryBanner.setOpaque(false);
        recoveryLabel.setFont(recoveryLabel.getFont().deriveFont(Font.BOLD));
        recoveryLabel.setForeground(new Color(180, 100, 0));
        recoveryBanner.add(recoveryLabel, BorderLayout.CENTER);
        recoveryBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 165, 70)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        recoveryBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        recoveryBanner.setVisible(false);

        header.add(title);
        header.add(Box.createVerticalStrut(8));
        header.add(intro);
        header.add(Box.createVerticalStrut(10));
        header.add(recoveryBanner);
        return header;
    }

    private JComponent buildCenter() {
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);

        stateLabel.setForeground(Color.GRAY);
        stateLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 2));
        wrapper.add(stateLabel, BorderLayout.NORTH);
        wrapper.add(listPanel, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildActionButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttons.setOpaque(false);

        refreshButton.addActionListener(e -> refresh());

        JButton openWeb = new JButton(message("gui.plugins.action.open-web"));
        openWeb.addActionListener(e -> openPluginManagePage());

        buttons.add(refreshButton);
        buttons.add(openWeb);
        return buttons;
    }

    /** 触发一次状态刷新：后台线程拉取 {@code /api/gui/plugins/status}，回到 EDT 渲染。重入安全（已在刷新时忽略）。 */
    private void refresh() {
        if (disposed || refreshing) {
            return;
        }
        refreshing = true;
        refreshButton.setEnabled(false);
        stateLabel.setText(message("gui.plugins.state.loading"));
        Thread worker = new Thread(() -> {
            GuiConfigTestClient.Response response = client.getJson("plugins/status", READ_TIMEOUT_MS);
            GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(
                    response.reachable(), response.status(), response.body());
            SwingUtilities.invokeLater(() -> {
                refreshing = false;
                if (!disposed) {
                    renderModel(model);
                }
            });
        }, "gui-plugins-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void onBackendStateChanged(BackendLifecycleManager.Snapshot snapshot) {
        if (disposed) {
            return;
        }
        if (snapshot.state() == BackendLifecycleManager.State.RUNNING) {
            refresh();
        } else {
            renderModel(GuiPluginStatusModel.offline());
        }
    }

    private void renderModel(GuiPluginStatusModel model) {
        refreshButton.setEnabled(true);
        recoveryBanner.setVisible(model.recoveryMode());
        if (model.recoveryMode()) {
            recoveryLabel.setText(message("gui.plugins.recovery"));
        }

        listPanel.removeAll();
        switch (model.outcome()) {
            case OK -> {
                if (model.rows().isEmpty()) {
                    stateLabel.setText(message("gui.plugins.state.empty"));
                } else {
                    stateLabel.setText(" ");
                    for (GuiPluginStatusModel.Row row : model.rows()) {
                        listPanel.add(buildPluginCard(row));
                        listPanel.add(Box.createVerticalStrut(8));
                    }
                }
            }
            case OFFLINE -> stateLabel.setText(message("gui.plugins.state.offline"));
            case FORBIDDEN -> stateLabel.setText(message("gui.plugins.state.forbidden"));
            case ERROR -> stateLabel.setText(message("gui.plugins.state.error"));
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JComponent buildPluginCard(GuiPluginStatusModel.Row row) {
        JPanel card = new JPanel(new BorderLayout(8, 4));
        card.setOpaque(false);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color separator = UIManager.getColor("Separator.foreground");
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(separator != null ? separator : Color.GRAY),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JLabel name = new JLabel(row.name());
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));

        JLabel status = new JLabel(GuiPluginStatusModel.statusLabel(row.statusCode()));
        status.setForeground(statusColor(row.statusCode()));

        JPanel headerRow = new JPanel(new BorderLayout(8, 0));
        headerRow.setOpaque(false);
        headerRow.add(name, BorderLayout.WEST);
        headerRow.add(status, BorderLayout.EAST);

        JLabel secondary = new JLabel(buildSecondaryText(row));
        secondary.setForeground(Color.GRAY);
        secondary.setFont(secondary.getFont().deriveFont(11f));

        card.add(headerRow, BorderLayout.NORTH);
        card.add(secondary, BorderLayout.SOUTH);
        // 在 BoxLayout(Y_AXIS) 里限制卡片纵向不被拉伸（宽度仍随视口）；高度须在子组件加入后计算。
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    /** 卡片次行：来源 + 必选标记 + 运行期阶段（仅受管插件）+ 版本，缺省项自动省略。 */
    private static String buildSecondaryText(GuiPluginStatusModel.Row row) {
        StringBuilder sb = new StringBuilder();
        sb.append(GuiPluginStatusModel.sourceLabel(row.source()));
        if (row.required()) {
            sb.append("  ·  ").append(message("gui.plugins.tag.required"));
        }
        if (row.managed() && row.phaseCode() != null && !row.phaseCode().isBlank()) {
            sb.append("  ·  ").append(GuiPluginStatusModel.phaseLabel(row.phaseCode()));
        }
        if (row.version() != null && !row.version().isBlank()) {
            sb.append("  ·  ").append(message("gui.plugins.version", row.version()));
        }
        return sb.toString();
    }

    private static Color statusColor(String statusCode) {
        if (statusCode == null) {
            return Color.GRAY;
        }
        return switch (statusCode) {
            case "STARTED" -> new Color(0, 140, 0);
            case "FAILED", "INCOMPATIBLE", "MISSING_REQUIRED", "INCOMPATIBLE_REQUIRED" -> new Color(180, 60, 60);
            case "DISABLED", "STOPPED", "UNLOADED" -> Color.GRAY;
            default -> new Color(180, 100, 0);
        };
    }

    private void openPluginManagePage() {
        try {
            Desktop.getDesktop().browse(new URI(webUrlProvider.apply(PLUGIN_MANAGE_PAGE)));
        } catch (Exception e) {
            log.warn(MessageBundles.get("gui.status.log.open-browser-failed", PLUGIN_MANAGE_PAGE, e.getMessage()), e);
            GuiErrorDialog.show(this, message("gui.dialog.error.title"),
                    message("gui.error.open-browser", e.getMessage()));
        }
    }

    public void dispose() {
        disposed = true;
        BackendLifecycleManager.removeListener(backendListener);
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }
}
