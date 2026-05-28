package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.util.Animator;
import com.formdev.flatlaf.util.CubicBezierEasing;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.OnboardingState;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * “首页 / 引导” 标签页：单步向导。每一步是一个独立 Panel，
 * 点击「下一步 / 完成」或后端信号自动推进时，用滑动动画切换到下一个 Panel。
 *
 * <p>四步：① 服务已就绪 → ② 在本页完成配置（管理员账号 + 模式，走 GUI 令牌通道）
 * → ③ 打开下载页（访问后自动推进）→ ④ 浏览画廊（网页操作指引完成后自动推进，
 * 并把 GUI 窗口带到前台）→ 完成页。引导未全部完成前每次启动都停留在首页。</p>
 */
@Slf4j
public class WelcomePanel extends JPanel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();
    private static final int POLL_INTERVAL_MS = 2000;

    private static final int STEP_SERVICE = 1;
    private static final int STEP_CONFIG = 2;
    private static final int STEP_PROXY = 3;
    private static final int STEP_START = 4;
    private static final int STEP_GALLERY = 5;
    private static final int STEP_ADVANCED = 6;
    private static final int STEP_DONE = 7;

    private final StatusPanel statusPanel;
    private final int serverPort;
    private final Runnable returnToGui;
    private final Runnable openStatusTab;

    private final SlideContainer slider = new SlideContainer();
    private Timer pulseTimer;
    private Timer pollTimer;
    private float pulsePhase;
    private volatile String currentScheme = "http";

    private int currentStep = STEP_SERVICE;
    private boolean bootstrapped;

    // 引导状态
    private boolean serviceReady;
    private boolean setupComplete;
    private boolean proxyConfigured = OnboardingState.isSeen() || OnboardingState.isProxyConfigured();
    private boolean batchVisited;
    private boolean galleryGuideCompleted;

    // 上一次对账时的快照，用于仅在相关状态真正变化时才重建当前面板
    // （否则每次轮询重建会打断配置步骤的输入焦点）
    private boolean lastServiceReady;
    private boolean lastSetupComplete;
    private boolean lastBatchVisited;
    private boolean lastGalleryGuideCompleted;

    private final StatusDot statusDot = new StatusDot();
    private final JLabel statusText = new JLabel();

    // 配置表单控件（跨重建复用）
    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JRadioButton soloRadio = new JRadioButton();
    private final JRadioButton multiRadio = new JRadioButton();
    private final JLabel configFeedback = new JLabel();
    private volatile boolean submitting;

    // 代理配置表单控件（跨重建复用）
    private final JCheckBox proxyEnabledCheck = new JCheckBox();
    private final JTextField proxyHostField = new JTextField(16);
    private final JTextField proxyPortField = new JTextField(8);
    private final JLabel proxyFeedback = new JLabel();
    private boolean proxyDefaultsLoaded;

    private final BackendLifecycleManager.Listener backendListener = s ->
            SwingUtilities.invokeLater(() -> {
                applyBackendState(s);
                reconcile(true);
            });

    public WelcomePanel(StatusPanel statusPanel, int serverPort,
                        Runnable returnToGui, Runnable openStatusTab) {
        this.statusPanel = statusPanel;
        this.serverPort = serverPort;
        this.returnToGui = returnToGui;
        this.openStatusTab = openStatusTab;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        add(slider, BorderLayout.CENTER);

        ButtonGroup g = new ButtonGroup();
        g.add(soloRadio);
        g.add(multiRadio);
        soloRadio.setSelected(true);

        proxyEnabledCheck.addItemListener(e -> updateProxyEnabledState());

        BackendLifecycleManager.addListener(backendListener);
        applyBackendState(BackendLifecycleManager.snapshot());
        startPulse();

        // 恢复上次保存的页码。把 bootstrapped 置位并提前快照标志位，
        // 这样首次 reconcile 不会再跳到 firstIncompleteStep —— 用户落在自己上次离开的那一页。
        // 下限取 firstIncompleteStep()：避免持久数据被外部清掉（如 setup 重置）后还停在过远的步骤。
        int saved = OnboardingState.loadProgress();
        int starting = Math.max(saved, firstIncompleteStep());
        if (starting < STEP_SERVICE) starting = STEP_SERVICE;
        if (starting > STEP_DONE) starting = STEP_DONE;
        currentStep = starting;
        bootstrapped = true;
        snapshotFlags();

        // 已彻底完成的引导不再发任何轮询；同时 MainFrame 在该状态下不会创建本面板，
        // 这里只是冗余防御。
        if (!OnboardingState.isFinished()) {
            startPolling();
        }

        slider.show(buildStep(currentStep), 0, false);
    }

    // ── 导航 ─────────────────────────────────────────────────────────────────
    private void goTo(int target, boolean animated) {
        if (target == currentStep) {
            return;
        }
        int dir = target > currentStep ? 1 : -1;
        currentStep = target;
        OnboardingState.saveProgress(target);
        if (target == STEP_DONE) {
            // 走到完成页：写入 finished 标记，下次启动起欢迎页就会被隐藏；同时停掉轮询，
            // 不再向已成功一次的后端发请求。
            OnboardingState.markFinished();
            stopPolling();
        }
        slider.show(buildStep(target), animated ? dir : 0, animated);
    }

    /**
     * 根据当前状态对账：
     * <ul>
     *   <li>未初始化时直接跳到首个未完成步骤（无动画）；</li>
     *   <li>配置步骤<strong>不自动切换</strong>——只能由用户点「完成配置 / 下一步」推进，
     *       避免用户正在填写时被轮询信号带走；</li>
     *   <li>「开始下载 → 浏览画廊 → 完成」按后端信号自动推进；</li>
     *   <li>其余情况仅在<strong>相关状态确实发生变化</strong>时原地刷新当前面板，
     *       不在每次轮询都重建（重建会打断输入焦点）。</li>
     * </ul>
     */
    private void reconcile(boolean animated) {
        if (!bootstrapped) {
            bootstrapped = true;
            currentStep = firstIncompleteStep();
            slider.show(buildStep(currentStep), 0, false);
            snapshotFlags();
            return;
        }

        boolean svcChanged = serviceReady != lastServiceReady;
        boolean setupChanged = setupComplete != lastSetupComplete;

        if (currentStep == STEP_SERVICE && serviceReady) {
            goTo(STEP_CONFIG, animated);
        } else if (currentStep == STEP_START && batchVisited) {
            goTo(STEP_GALLERY, animated);
        } else if (currentStep == STEP_GALLERY && galleryGuideCompleted) {
            OnboardingState.markSeen();
            goTo(STEP_ADVANCED, animated);
            if (returnToGui != null) {
                returnToGui.run();
            }
        } else if ((currentStep == STEP_SERVICE && svcChanged)
                || (currentStep == STEP_CONFIG && (svcChanged || setupChanged))) {
            // 配置步骤：服务起来→启用表单，或被 setup.html 后备完成→显示「已完成」，
            // 仅在这些状态真正变化时重建一次；绝不自动跳到下一步。
            slider.refreshCurrent(buildStep(currentStep));
        }
        snapshotFlags();
    }

    private void snapshotFlags() {
        lastServiceReady = serviceReady;
        lastSetupComplete = setupComplete;
        lastBatchVisited = batchVisited;
        lastGalleryGuideCompleted = galleryGuideCompleted;
    }

    private int firstIncompleteStep() {
        if (!serviceReady) return STEP_SERVICE;
        if (!setupComplete) return STEP_CONFIG;
        if (!proxyConfigured) return STEP_PROXY;
        if (!batchVisited) return STEP_START;
        if (!galleryGuideCompleted) return STEP_GALLERY;
        return STEP_ADVANCED;
    }

    // ── 步骤面板构建 ─────────────────────────────────────────────────────────
    private JComponent buildStep(int step) {
        return switch (step) {
            case STEP_SERVICE -> buildServiceStep();
            case STEP_CONFIG -> buildConfigStep();
            case STEP_PROXY -> buildProxyStep();
            case STEP_START -> buildStartStep();
            case STEP_GALLERY -> buildGalleryStep();
            case STEP_ADVANCED -> buildAdvancedStep();
            default -> buildDoneStep();
        };
    }

    private JComponent buildServiceStep() {
        StepLayout s = new StepLayout("gui.welcome.status.title", "gui.welcome.status.subtitle");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(statusDot);
        statusText.setFont(statusText.getFont().deriveFont(Font.BOLD, 15f));
        row.add(statusText);
        s.add(row);
        s.gap(10);
        s.bullet("gui.welcome.status.point1");
        s.bullet("gui.welcome.status.point2");
        s.bullet("gui.welcome.status.point3");
        s.footer(GuiMessages.get("gui.welcome.nav.next"), serviceReady,
                () -> goTo(STEP_CONFIG, true), false);
        return s.root;
    }

    private JComponent buildConfigStep() {
        StepLayout s = new StepLayout("gui.welcome.config.title", "gui.welcome.config.body");

        if (setupComplete) {
            JLabel done = new JLabel(GuiMessages.get("gui.welcome.config.done"));
            done.setFont(done.getFont().deriveFont(Font.BOLD, 15f));
            done.setForeground(new Color(0, 140, 0));
            s.add(done);
            s.gap(8);
            s.para("gui.welcome.config.done.body");
            s.bullet("gui.welcome.config.point.change");
            s.footer(GuiMessages.get("gui.welcome.nav.next"), true,
                    () -> goTo(STEP_PROXY, true), true);
            return s.root;
        }

        s.bullet("gui.welcome.config.point.account");
        s.bullet("gui.welcome.config.point.mode");
        s.gap(6);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 10);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0;
        g.gridy = 0;
        form.add(new JLabel(GuiMessages.get("gui.welcome.config.username")), g);
        g.gridx = 1;
        form.add(usernameField, g);
        g.gridx = 0;
        g.gridy = 1;
        form.add(new JLabel(GuiMessages.get("gui.welcome.config.password")), g);
        g.gridx = 1;
        form.add(passwordField, g);
        g.gridx = 0;
        g.gridy = 2;
        g.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel(GuiMessages.get("gui.welcome.config.mode")), g);

        JPanel modes = new JPanel();
        modes.setOpaque(false);
        modes.setLayout(new BoxLayout(modes, BoxLayout.Y_AXIS));
        soloRadio.setText(GuiMessages.get("gui.welcome.config.mode.solo"));
        soloRadio.setOpaque(false);
        multiRadio.setText(GuiMessages.get("gui.welcome.config.mode.multi"));
        multiRadio.setOpaque(false);
        JLabel soloHint = secondary(GuiMessages.get("gui.welcome.config.mode.solo.hint"));
        JLabel multiHint = secondary(GuiMessages.get("gui.welcome.config.mode.multi.hint"));
        soloRadio.setAlignmentX(LEFT_ALIGNMENT);
        soloHint.setAlignmentX(LEFT_ALIGNMENT);
        multiRadio.setAlignmentX(LEFT_ALIGNMENT);
        multiHint.setAlignmentX(LEFT_ALIGNMENT);
        modes.add(soloRadio);
        modes.add(soloHint);
        modes.add(Box.createVerticalStrut(4));
        modes.add(multiRadio);
        modes.add(multiHint);
        g.gridx = 1;
        form.add(modes, g);

        boolean editable = serviceReady && !submitting;
        usernameField.setEnabled(editable);
        passwordField.setEnabled(editable);
        soloRadio.setEnabled(editable);
        multiRadio.setEnabled(editable);

        s.add(form);
        s.gap(8);
        if (!serviceReady) {
            configFeedback.setForeground(Color.GRAY);
            configFeedback.setText(GuiMessages.get("gui.welcome.config.waiting"));
        }
        configFeedback.setAlignmentX(LEFT_ALIGNMENT);
        s.content.add(configFeedback);
        s.gap(8);
        s.bullet("gui.welcome.config.point.change");

        s.footer(GuiMessages.get("gui.welcome.config.submit"), editable,
                this::submitConfig, true);
        return s.root;
    }

    private JComponent buildProxyStep() {
        StepLayout s = new StepLayout("gui.welcome.proxy.title", "gui.welcome.proxy.body");
        s.bullet("gui.welcome.proxy.point.usage");
        s.bullet("gui.welcome.proxy.point.docker");
        s.gap(6);

        loadProxyDefaults();

        proxyEnabledCheck.setText(GuiMessages.get("gui.welcome.proxy.enabled"));
        proxyEnabledCheck.setOpaque(false);
        proxyEnabledCheck.setAlignmentX(LEFT_ALIGNMENT);
        s.add(proxyEnabledCheck);
        s.gap(6);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 10);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        g.gridy = 0;
        form.add(new JLabel(GuiMessages.get("gui.welcome.proxy.host")), g);
        g.gridx = 1;
        form.add(proxyHostField, g);
        g.gridx = 0;
        g.gridy = 1;
        form.add(new JLabel(GuiMessages.get("gui.welcome.proxy.port")), g);
        g.gridx = 1;
        form.add(proxyPortField, g);
        s.add(form);

        proxyEnabledCheck.setEnabled(true);
        updateProxyEnabledState();

        s.gap(8);
        proxyFeedback.setAlignmentX(LEFT_ALIGNMENT);
        s.content.add(proxyFeedback);
        s.gap(8);
        s.bullet("gui.welcome.proxy.point.change");

        s.footer(GuiMessages.get("gui.welcome.nav.next"), true, this::submitProxy, true);
        return s.root;
    }

    /** 从 config.yaml 读取当前代理配置填充控件，仅首次构建代理步骤时执行一次。 */
    private void loadProxyDefaults() {
        if (proxyDefaultsLoaded) {
            return;
        }
        proxyDefaultsLoaded = true;
        boolean enabled = true;
        String host = ProxyConfig.DEFAULT_HOST;
        String port = Integer.toString(ProxyConfig.DEFAULT_PORT);
        try {
            ConfigFileEditor editor = new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath());
            String e = editor.read(ProxyConfig.KEY_ENABLED);
            String h = editor.read(ProxyConfig.KEY_HOST);
            String p = editor.read(ProxyConfig.KEY_PORT);
            if (e != null && !e.isBlank()) {
                enabled = Boolean.parseBoolean(e.trim());
            }
            if (h != null && !h.isBlank()) {
                host = h.trim();
            }
            if (p != null && !p.isBlank()) {
                port = p.trim();
            }
        } catch (IOException ignored) {
            // 读取失败按默认值填充
        }
        proxyEnabledCheck.setSelected(enabled);
        proxyHostField.setText(host);
        proxyPortField.setText(port);
    }

    private void updateProxyEnabledState() {
        boolean fieldsEnabled = proxyEnabledCheck.isSelected();
        proxyHostField.setEnabled(fieldsEnabled);
        proxyPortField.setEnabled(fieldsEnabled);
    }

    /** 写入代理配置到 config.yaml 并热重载（best-effort），随后进入下一步。 */
    private void submitProxy() {
        boolean enabled = proxyEnabledCheck.isSelected();
        String host = proxyHostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(proxyPortField.getText().trim());
        } catch (NumberFormatException ex) {
            port = -1;
        }
        if (enabled) {
            if (host.isEmpty()) {
                showProxyError(GuiMessages.get("gui.welcome.proxy.invalid.host"));
                return;
            }
            if (port < 1 || port > 65535) {
                showProxyError(GuiMessages.get("gui.welcome.proxy.invalid.port"));
                return;
            }
        } else {
            // 关闭代理时仍落盘 host/port，缺省回退默认值以便后续开启复用
            if (host.isEmpty()) {
                host = ProxyConfig.DEFAULT_HOST;
            }
            if (port < 1 || port > 65535) {
                port = ProxyConfig.DEFAULT_PORT;
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put(ProxyConfig.KEY_ENABLED, Boolean.toString(enabled));
        values.put(ProxyConfig.KEY_HOST, host);
        values.put(ProxyConfig.KEY_PORT, Integer.toString(port));
        try {
            new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath()).writeAll(values);
        } catch (IOException ex) {
            log.warn(MessageBundles.get("gui.config.log.save-failed", ex.getMessage()));
            showProxyError(GuiMessages.get("gui.welcome.proxy.failed", safe(ex.getMessage())));
            GuiErrorDialog.show(this, GuiMessages.get("gui.dialog.error.title"),
                    GuiMessages.get("gui.welcome.proxy.failed", safe(ex.getMessage())));
            return;
        }

        proxyConfigured = true;
        OnboardingState.markProxyConfigured();
        triggerHotReloadAsync();
        goTo(STEP_START, true);
    }

    private void showProxyError(String msg) {
        proxyFeedback.setForeground(new Color(180, 60, 60));
        proxyFeedback.setText(msg);
        slider.refreshCurrent(buildStep(STEP_PROXY));
    }

    /** 异步触发 /api/gui/config/reload，让代理改动立即生效；失败仅记日志（下次启动仍会生效）。 */
    private void triggerHotReloadAsync() {
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                return postJson("/api/gui/config/reload", "{}", b -> {
                });
            }

            @Override
            protected void done() {
                try {
                    int status = get()[0];
                    if (status != 200) {
                        log.warn(MessageBundles.get("gui.config.log.hot-reload-failed", "status=" + status));
                    }
                } catch (Exception e) {
                    log.warn(MessageBundles.get("gui.config.log.hot-reload-failed", safe(e.getMessage())));
                }
            }
        }.execute();
    }

    private JComponent buildStartStep() {
        StepLayout s = new StepLayout("gui.welcome.start.title", "gui.welcome.start.body");
        s.bullet("gui.welcome.start.point.kinds");
        s.bullet("gui.welcome.start.point.keepopen");
        s.bullet("gui.welcome.start.point.formats");
        s.gap(8);
        JButton open = new JButton(GuiMessages.get("gui.welcome.start.button"));
        open.addActionListener(e -> openUrl(statusPanel.getBatchUrl()));
        s.add(open);
        s.gap(8);
        JLabel hint = secondary(GuiMessages.get("gui.welcome.start.waiting"));
        s.add(hint);
        s.footer(GuiMessages.get("gui.welcome.nav.next"), true,
                () -> goTo(STEP_GALLERY, true), true);
        return s.root;
    }

    private JComponent buildGalleryStep() {
        StepLayout s = new StepLayout("gui.welcome.gallery.title", "gui.welcome.gallery.body");
        s.bullet("gui.welcome.gallery.point.search");
        s.bullet("gui.welcome.gallery.point.collections");
        s.bullet("gui.welcome.gallery.point.guide");
        s.gap(8);
        JButton open = new JButton(GuiMessages.get("gui.welcome.gallery.button"));
        open.addActionListener(e -> openUrl(statusPanel.getGalleryUrl()));
        s.add(open);
        s.gap(8);
        JLabel hint = secondary(GuiMessages.get("gui.welcome.gallery.waiting"));
        s.add(hint);
        s.footer(GuiMessages.get("gui.welcome.nav.finish"), true, () -> {
            OnboardingState.markSeen();
            galleryGuideCompleted = true;
            goTo(STEP_ADVANCED, true);
        }, true);
        return s.root;
    }

    private JComponent buildAdvancedStep() {
        StepLayout s = new StepLayout("gui.welcome.advanced.title", "gui.welcome.advanced.body");

        // 进阶 · 油猴脚本
        s.heading("gui.welcome.scripts.title");
        s.para("gui.welcome.scripts.intro");
        s.bullet("gui.welcome.scripts.point.page");
        s.bullet("gui.welcome.scripts.point.toolbox");
        s.para("gui.welcome.scripts.install");

        // 进阶 · 动图支持
        s.heading("gui.welcome.ffmpeg.title");
        s.para("gui.welcome.ffmpeg.intro");
        boolean ffmpegReady = FfmpegLocator.locate().isPresent();
        JLabel state = new JLabel(GuiMessages.get("gui.welcome.ffmpeg.state",
                GuiMessages.get(ffmpegReady
                        ? "gui.welcome.ffmpeg.state.ready" : "gui.welcome.ffmpeg.state.missing")));
        state.setFont(state.getFont().deriveFont(Font.BOLD, 12f));
        state.setForeground(ffmpegReady ? new Color(0, 140, 0) : new Color(180, 100, 0));
        s.add(state);
        s.gap(6);
        s.para("gui.welcome.ffmpeg.install");

        // 重看网页操作指引
        s.heading("gui.welcome.done.reopen.title");
        s.para("gui.welcome.done.reopen");

        s.footer(GuiMessages.get("gui.welcome.nav.next"), true,
                () -> goTo(STEP_DONE, true), true);
        return s.root;
    }

    private JComponent buildDoneStep() {
        StepLayout s = new StepLayout("gui.welcome.done.title", "gui.welcome.done.body");
        s.bullet("gui.welcome.done.point.start");
        s.bullet("gui.welcome.done.point.advanced");
        s.footer(GuiMessages.get("gui.welcome.done.button"), true,
                () -> { if (openStatusTab != null) openStatusTab.run(); }, false);
        return s.root;
    }

    // ── 配置提交（走 GUI 令牌通道 /api/gui/setup/init）────────────────────────
    private void submitConfig() {
        if (submitting) {
            return;
        }
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        String mode = multiRadio.isSelected() ? "multi" : "solo";
        if (username.isEmpty()) {
            showConfigError(GuiMessages.get("gui.welcome.config.invalid.username"));
            return;
        }
        if (password.length() < 6) {
            showConfigError(GuiMessages.get("gui.welcome.config.invalid.password"));
            return;
        }

        submitting = true;
        configFeedback.setForeground(Color.GRAY);
        configFeedback.setText(GuiMessages.get("gui.welcome.config.submitting"));
        slider.refreshCurrent(buildStep(STEP_CONFIG));

        String payload;
        try {
            payload = MAPPER.writeValueAsString(new SetupPayload(username, password, mode));
        } catch (Exception e) {
            submitting = false;
            handleConfigFailure(e.getMessage());
            return;
        }

        new SwingWorker<int[], Void>() {
            private String errorBody = "";

            @Override
            protected int[] doInBackground() {
                return postJson("/api/gui/setup/init", payload, b -> errorBody = b);
            }

            @Override
            protected void done() {
                submitting = false;
                int status;
                try {
                    status = get()[0];
                } catch (Exception e) {
                    handleConfigFailure(safe(e.getMessage()));
                    return;
                }
                if (status == 200) {
                    setupComplete = true;
                    goTo(STEP_PROXY, true);
                } else if (status == 403) {
                    // 多为已通过 setup.html 后备完成；交给轮询对账
                    configFeedback.setForeground(Color.GRAY);
                    configFeedback.setText(GuiMessages.get("gui.welcome.config.submitting"));
                    pollOnboarding();
                } else {
                    handleConfigFailure(status + (errorBody.isBlank() ? "" : " " + errorBody));
                }
            }
        }.execute();
    }

    private void showConfigError(String msg) {
        configFeedback.setForeground(new Color(180, 60, 60));
        configFeedback.setText(msg);
        slider.refreshCurrent(buildStep(STEP_CONFIG));
    }

    private void handleConfigFailure(String detail) {
        log.warn(MessageBundles.get("gui.status.log.update.call-failed", "/api/gui/setup/init", detail));
        showConfigError(GuiMessages.get("gui.welcome.config.failed", detail == null ? "" : detail));
        GuiErrorDialog.show(this, GuiMessages.get("gui.dialog.error.title"),
                GuiMessages.get("gui.welcome.config.failed", detail == null ? "" : detail));
    }

    private record SetupPayload(String username, String password, String mode) {
    }

    // ── 后端联动 ─────────────────────────────────────────────────────────────
    private void applyBackendState(BackendLifecycleManager.Snapshot snapshot) {
        switch (snapshot.state()) {
            case RUNNING -> {
                serviceReady = true;
                statusText.setText(GuiMessages.get("gui.welcome.status.running"));
                statusText.setForeground(new Color(0, 140, 0));
                statusDot.setBaseColor(new Color(0, 170, 0));
            }
            case STARTING -> {
                serviceReady = false;
                statusText.setText(GuiMessages.get("gui.welcome.status.starting"));
                statusText.setForeground(new Color(180, 100, 0));
                statusDot.setBaseColor(new Color(220, 150, 0));
            }
            case FAILED -> {
                serviceReady = false;
                statusText.setText(GuiMessages.get("gui.welcome.status.failed"));
                statusText.setForeground(new Color(180, 60, 60));
                statusDot.setBaseColor(new Color(200, 60, 60));
            }
            default -> {
                serviceReady = false;
                statusText.setText(GuiMessages.get("gui.welcome.status.stopped"));
                statusText.setForeground(Color.GRAY);
                statusDot.setBaseColor(Color.GRAY);
            }
        }
    }

    private void startPolling() {
        pollTimer = new Timer(POLL_INTERVAL_MS, e -> pollOnboarding());
        pollTimer.setInitialDelay(800);
        pollTimer.start();
    }

    private void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
    }

    private void pollOnboarding() {
        if (pollTimer == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            JsonNode node = getJson("/api/gui/onboarding");
            if (node == null) {
                return;
            }
            SwingUtilities.invokeLater(() -> applyOnboarding(node));
        }, "gui-onboarding-poll");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyOnboarding(JsonNode node) {
        if (node.path("setupComplete").asBoolean(false)) {
            setupComplete = true;
        }
        if (node.path("batchVisited").asBoolean(false)) {
            batchVisited = true;
        }
        if (node.path("galleryGuideCompleted").asBoolean(false)) {
            galleryGuideCompleted = true;
        }
        reconcile(true);
        // 三个后端引导信号都已观测到之后，再轮询也只能拿到相同结果；停掉以免在后续步骤
        // 或后端被工具栏关停时还持续刷 WARN 日志。
        if (setupComplete && batchVisited && galleryGuideCompleted) {
            stopPolling();
        }
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            log.warn(MessageBundles.get("gui.status.log.open-browser-failed", url, e.getMessage()), e);
            GuiErrorDialog.show(this, GuiMessages.get("gui.dialog.error.title"),
                    GuiMessages.get("gui.error.open-browser", e.getMessage()));
        }
    }

    // ── 轻量 HTTP（携带 GUI 令牌，参照 StatusPanel 的 scheme 探测）──────────────
    private JsonNode getJson(String path) {
        String[] schemes = "https".equals(currentScheme)
                ? new String[]{"https", "http"} : new String[]{"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + path).toURL();
                conn = open(url);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    currentScheme = scheme;
                    try (InputStream is = conn.getInputStream()) {
                        return MAPPER.readTree(is);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch {} via {}", path, scheme, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    private int[] postJson(String path, String jsonBody, java.util.function.Consumer<String> errorSink) {
        String[] schemes = "https".equals(currentScheme)
                ? new String[]{"https", "http"} : new String[]{"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + path).toURL();
                conn = open(url);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                currentScheme = scheme;
                if (code >= 400) {
                    try (InputStream es = conn.getErrorStream()) {
                        if (es != null) {
                            errorSink.accept(new String(es.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                        log.debug("Failed to read error stream from {} response", path, e);
                    }
                }
                return new int[]{code};
            } catch (Exception e) {
                log.warn("Failed to POST {}", path, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new int[]{-1};
    }

    private static HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
            https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
            https.setHostnameVerifier((h, s) -> true);
        }
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(4000);
        String guiToken = GuiTokenHolder.get();
        if (guiToken != null) {
            conn.setRequestProperty(GuiTokenHolder.HEADER_NAME, guiToken);
        }
        return conn;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static JLabel secondary(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.GRAY);
        l.setFont(l.getFont().deriveFont(11f));
        return l;
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String htmlBody(String text) {
        return "<html><div style='width:540px'>" + esc(text) + "</div></html>";
    }

    private static String htmlBullet(String text) {
        return "<html><div style='width:528px'>• " + esc(text) + "</div></html>";
    }

    private void startPulse() {
        pulseTimer = new Timer(60, e -> {
            pulsePhase += 0.13f;
            statusDot.setPulse((float) (0.55 + 0.45 * Math.sin(pulsePhase)));
        });
        pulseTimer.start();
    }

    public void dispose() {
        slider.stopAnimation();
        if (pulseTimer != null) {
            pulseTimer.stop();
            pulseTimer = null;
        }
        stopPolling();
        BackendLifecycleManager.removeListener(backendListener);
    }

    // ── 单步面板布局：标题 + 副标题 + 内容 + 底部导航 ─────────────────────────
    private final class StepLayout {
        final JPanel root = new JPanel(new BorderLayout());
        final JPanel content = new JPanel();
        private final JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        StepLayout(String titleKey, String bodyKey) {
            root.setOpaque(false);
            JPanel head = new JPanel();
            head.setOpaque(false);
            head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
            head.setBorder(BorderFactory.createEmptyBorder(8, 4, 16, 4));
            JLabel title = new JLabel(GuiMessages.get(titleKey));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            title.setAlignmentX(LEFT_ALIGNMENT);
            JLabel body = new JLabel(htmlBody(GuiMessages.get(bodyKey)));
            body.setForeground(Color.GRAY);
            body.setAlignmentX(LEFT_ALIGNMENT);
            head.add(title);
            head.add(Box.createVerticalStrut(8));
            head.add(body);

            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

            south.setOpaque(false);
            south.setBorder(BorderFactory.createEmptyBorder(16, 4, 4, 4));

            JScrollPane scroll = new JScrollPane(content,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);

            root.add(head, BorderLayout.NORTH);
            root.add(scroll, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
        }

        /** 灰色正文段落。 */
        void para(String key) {
            JLabel l = new JLabel(htmlBody(GuiMessages.get(key)));
            l.setForeground(Color.GRAY);
            l.setAlignmentX(LEFT_ALIGNMENT);
            content.add(l);
            content.add(Box.createVerticalStrut(8));
        }

        /** 带项目符号的要点行。 */
        void bullet(String key) {
            JLabel l = new JLabel(htmlBullet(GuiMessages.get(key)));
            l.setForeground(Color.GRAY);
            l.setAlignmentX(LEFT_ALIGNMENT);
            content.add(l);
            content.add(Box.createVerticalStrut(5));
        }

        /** 小节标题（加粗、带上间距）。 */
        void heading(String key) {
            content.add(Box.createVerticalStrut(10));
            JLabel l = new JLabel(GuiMessages.get(key));
            l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
            l.setAlignmentX(LEFT_ALIGNMENT);
            content.add(l);
            content.add(Box.createVerticalStrut(6));
        }

        void add(JComponent c) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            content.add(c);
        }

        void gap(int h) {
            content.add(Box.createVerticalStrut(h));
        }

        void footer(String primaryText, boolean primaryEnabled, Runnable primaryAction,
                    boolean showBack) {
            south.removeAll();
            if (showBack && currentStep > STEP_SERVICE && currentStep < STEP_DONE) {
                JButton back = new JButton(GuiMessages.get("gui.welcome.nav.prev"));
                back.addActionListener(e -> goTo(currentStep - 1, true));
                south.add(back);
            }
            JButton primary = new JButton(primaryText);
            primary.setFont(primary.getFont().deriveFont(Font.BOLD));
            primary.setEnabled(primaryEnabled);
            primary.addActionListener(e -> primaryAction.run());
            south.add(primary);
        }
    }

    // ── 滑动容器：在两个 Panel 间做横向滑动切换 ───────────────────────────────
    private final class SlideContainer extends JPanel {
        private JComponent current;
        private Animator animator;

        SlideContainer() {
            setOpaque(false);
            setLayout(null);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (animator == null || !animator.isRunning()) {
                        layoutCurrentFull();
                    }
                }
            });
        }

        void layoutCurrentFull() {
            if (current != null) {
                current.setBounds(0, 0, getWidth(), getHeight());
            }
        }

        void refreshCurrent(JComponent rebuilt) {
            // 原地替换当前步骤内容（不滑动），用于刷新可交互态/反馈文案
            if (animator != null && animator.isRunning()) {
                return;
            }
            removeAll();
            current = rebuilt;
            add(rebuilt);
            layoutCurrentFull();
            revalidate();
            repaint();
        }

        void show(JComponent next, int dir, boolean animated) {
            int w = getWidth();
            int h = getHeight();
            if (current == null || !animated || dir == 0
                    || w == 0 || !Animator.useAnimation()) {
                stopAnimation();
                removeAll();
                current = next;
                add(next);
                layoutCurrentFull();
                revalidate();
                repaint();
                return;
            }
            stopAnimation();
            final JComponent out = current;
            add(next);
            out.setBounds(0, 0, w, h);
            next.setBounds(dir > 0 ? w : -w, 0, w, h);
            animator = new Animator(320, fraction -> {
                float e = CubicBezierEasing.EASE.interpolate(fraction);
                int off = Math.round(w * e);
                if (dir > 0) {
                    out.setBounds(-off, 0, w, h);
                    next.setBounds(w - off, 0, w, h);
                } else {
                    out.setBounds(off, 0, w, h);
                    next.setBounds(-w + off, 0, w, h);
                }
            }, () -> {
                remove(out);
                current = next;
                layoutCurrentFull();
                revalidate();
                repaint();
            });
            animator.setResolution(16);
            animator.start();
        }

        void stopAnimation() {
            if (animator != null) {
                animator.stop();
                animator = null;
            }
        }
    }

    // ── 呼吸状态点 ───────────────────────────────────────────────────────────
    private static final class StatusDot extends JComponent {
        private Color baseColor = Color.GRAY;
        private float pulse = 1f;

        StatusDot() {
            setPreferredSize(new Dimension(14, 14));
        }

        void setBaseColor(Color c) {
            this.baseColor = c;
            repaint();
        }

        void setPulse(float p) {
            this.pulse = p;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
                    (int) (60 * pulse)));
            g2.fillOval(x - 3, y - 3, d + 6, d + 6);
            g2.setColor(baseColor);
            g2.fillOval(x, y, d, d);
            g2.dispose();
        }
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }
                    }
            }, null);
            return ctx;
        } catch (Exception e) {
            log.warn("Failed to create trust-all SSL context", e);
            return null;
        }
    }
}
