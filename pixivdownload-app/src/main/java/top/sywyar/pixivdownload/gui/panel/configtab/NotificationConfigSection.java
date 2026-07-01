package top.sywyar.pixivdownload.gui.panel.configtab;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiThemeRefresh;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.mail.preset.MailPreset;
import top.sywyar.pixivdownload.mail.preset.MailPresetRegistry;
import top.sywyar.pixivdownload.notification.NotificationConfig;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * 「通知」分组标签页：邮件 / SMTP + 多通道推送合并，用「通知服务」下拉切换编辑，所有已启用的服务同时生效。
 * <p>
 * 与普通分组一样是「单页整体滚动」——提示、推送总开关、需要通知的类型、服务下拉与所选服务的字段卡片全部放进
 * 同一个滚动容器，没有任何控件固定在顶部。服务下拉仅切换下方展示的卡片；所有服务的字段在构建时就已注册，
 * 因此所有已启用的服务同时持久化、同时发送。详见 {@code docs/claude/gui.md}「配置编辑」。
 */
@Slf4j
public final class NotificationConfigSection implements ConfigSection {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int MAIL_TEST_READ_TIMEOUT_MS = 30_000;
    private static final int MAIL_TEST_ALL_READ_TIMEOUT_MS = 180_000;
    private static final int PUSH_TEST_READ_TIMEOUT_MS = 30_000;
    private static final int PUSH_TEST_ALL_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private final ConfigSectionContext ctx;
    private final String notificationGroup = ConfigFieldRegistry.groupNotification();

    /** SMTP 预设注册中心（不可变）。 */
    private final MailPresetRegistry mailPresetRegistry = new MailPresetRegistry();
    /** 服务商预设下拉。 */
    private JComboBox<MailPreset> mailPresetCombo;
    /** "发送测试邮件" 按钮，发送中暂时禁用。 */
    private JButton mailTestButton;
    /** "发送所有邮件模板" 按钮，发送中暂时禁用。 */
    private JButton mailTestAllButton;
    /** 当前预设；非 custom 时锁定 host / port / security。 */
    private MailPreset currentMailPreset;
    /** 防止在程序性更新下拉时反向触发预设应用。 */
    private boolean updatingMailPresetCombo;

    public NotificationConfigSection(ConfigSectionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String group() {
        return notificationGroup;
    }

    @Override
    public void onValuesLoaded() {
        resolveMailPresetFromCurrentHost();
    }

    @Override
    public void afterEnabledStates() {
        // 选中非自定义预设时，host / port / security 即便满足 enabledWhen 也保持禁用。
        if (mailPresetCombo == null || currentMailPreset == null || currentMailPreset.isCustom()) {
            return;
        }
        ctx.lockField("mail.host");
        ctx.lockField("mail.port");
        ctx.lockField("mail.security");
    }

    // ── 通知服务下拉 ───────────────────────────────────────────────────────────

    /** 通知服务下拉项：id 用于切卡与推送测试，displayKey 为 i18n 显示名。 */
    private record NotificationService(String id, String displayKey) {
    }

    private static List<NotificationService> notificationServices() {
        return List.of(
                new NotificationService("mail", "gui.config.notification.service.mail"),
                new NotificationService("bark", "gui.config.notification.service.bark"),
                new NotificationService("dingtalk", "gui.config.notification.service.dingtalk"),
                new NotificationService("telegram", "gui.config.notification.service.telegram"),
                new NotificationService("feishu", "gui.config.notification.service.feishu"),
                new NotificationService("wecom", "gui.config.notification.service.wecom"),
                new NotificationService("pushplus", "gui.config.notification.service.pushplus"),
                new NotificationService("serverchan", "gui.config.notification.service.serverchan"),
                new NotificationService("webhook", "gui.config.notification.service.webhook"));
    }

    @Override
    public JComponent build() {
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel hint = new JLabel(message("gui.config.notification.hint"));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(0, 128, 96));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(hint);
        content.add(Box.createVerticalStrut(4));

        // 推送总开关（push.enabled）：随页面一同滚动，不再固定在顶部。
        ConfigFieldSpec pushEnabledSpec = ctx.findSpec("push.enabled");
        if (pushEnabledSpec != null) {
            FieldRenderer.RenderedField rf = FieldRenderer.render(pushEnabledSpec);
            ctx.registerField(pushEnabledSpec, rf);
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(rf.panel());
            content.add(Box.createVerticalStrut(2));
        }

        // 「需要通知的类型」：每个场景一个复选框，默认全部勾选；取消勾选后该类型的邮件与推送都不再发送。
        JPanel scenarioPanel = buildNotificationScenarioPanel();
        if (scenarioPanel != null) {
            scenarioPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(scenarioPanel);
            content.add(Box.createVerticalStrut(2));
        }

        JComboBox<NotificationService> serviceCombo =
                new JComboBox<>(notificationServices().toArray(new NotificationService[0]));
        serviceCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof NotificationService s) {
                    label.setText(message(s.displayKey()));
                }
                return label;
            }
        });
        JPanel comboRow = FieldRenderer.fieldPanel(
                message("gui.config.notification.service.label") + message("gui.punctuation.colon"),
                serviceCombo, null, message("gui.config.notification.service.help"));
        comboRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(comboRow);
        content.add(Box.createVerticalStrut(2));

        // 预构建并注册所有服务卡片（字段对全部服务统一加载 / 保存），但同一时刻只在 cardHost 中展示所选的一张。
        // 用「手动换卡」而非 CardLayout：CardLayout 会按最高的卡片（邮件）预留高度，切到字段较少的推送渠道时
        // 会在统一滚动页里留下大片空白；逐张替换则让滚动高度始终贴合当前卡片。
        Map<String, JComponent> cards = new LinkedHashMap<>();
        for (NotificationService s : notificationServices()) {
            cards.put(s.id(), buildServiceCard(s));
        }
        JPanel cardHost = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        cardHost.setOpaque(false);
        cardHost.setAlignmentX(Component.LEFT_ALIGNMENT);
        serviceCombo.addActionListener(e -> {
            if (serviceCombo.getSelectedItem() instanceof NotificationService s) {
                GuiThemeRefresh.showCard(cardHost, cards.get(s.id()));
            }
        });
        GuiThemeRefresh.showCard(cardHost, cards.get(notificationServices().get(0).id()));
        content.add(cardHost);
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 邮件卡片预设锁定会在 init 阶段触发 scrollRectToVisible 让视口偏离 (0,0)；首次显示该分组时强制回到顶部。
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    /**
     * 构建单个通知服务的编辑卡片（邮件含预设 + 测试；推送渠道含字段 + 单渠道测试）。
     * 卡片本身不再各自套滚动条：它会被嵌进统一滚动页，与上方控件一同滚动。
     */
    private JComponent buildServiceCard(NotificationService service) {
        JPanel content = ctx.newContentPanel();

        if ("mail".equals(service.id())) {
            JPanel preset = buildMailPresetPanel();
            preset.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(preset);
            content.add(Box.createVerticalStrut(2));
            ctx.addFields(content, fieldsByPrefix("mail."));
            JPanel test = buildMailTestPanel();
            test.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(test);
            content.add(Box.createVerticalStrut(2));
            JPanel testAll = buildMailTestAllPanel();
            testAll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(testAll);
            content.add(Box.createVerticalStrut(2));
        } else {
            ctx.addFields(content, fieldsByPrefix("push." + service.id() + "."));
            JPanel test = buildPushChannelTestPanel(service.id());
            test.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(test);
            content.add(Box.createVerticalStrut(2));
            JPanel testAll = buildPushChannelTestAllPanel(service.id());
            testAll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(testAll);
            content.add(Box.createVerticalStrut(2));
        }
        return content;
    }

    private List<ConfigFieldSpec> fieldsByPrefix(String prefix) {
        return ctx.allFields().stream()
                .filter(f -> notificationGroup.equals(f.group()))
                .filter(f -> f.key().startsWith(prefix))
                .toList();
    }

    /**
     * 「需要通知的类型」面板：把每个通知场景（{@code notification.scenario.<id>.enabled}）渲染成一个复选框，
     * 默认全部勾选；取消某个勾选即停发该类型的全部通知（邮件 + 推送）。场景为空时返回 {@code null}。
     */
    private JPanel buildNotificationScenarioPanel() {
        List<ConfigFieldSpec> specs = fieldsByPrefix(NotificationConfig.KEY_SCENARIO_PREFIX);
        if (specs.isEmpty()) {
            return null;
        }

        JPanel checkBoxGrid = new JPanel(new GridLayout(0, 2, 12, 2));
        checkBoxGrid.setOpaque(false);

        Map<ConfigFieldSpec, JCheckBox> checkBoxes = new LinkedHashMap<>();
        for (ConfigFieldSpec spec : specs) {
            JCheckBox checkBox = new JCheckBox(spec.label());
            checkBox.setSelected("true".equalsIgnoreCase(spec.defaultValue()));
            checkBox.setToolTipText(spec.helpText());
            checkBox.setOpaque(false);
            checkBoxes.put(spec, checkBox);
            checkBoxGrid.add(checkBox);
        }

        JPanel panel = FieldRenderer.fieldPanel(
                message("gui.config.notification.scenario.section.label") + message("gui.punctuation.colon"),
                checkBoxGrid,
                ctx.effectLabel(false),
                message("gui.config.notification.scenario.section.help"));

        for (Map.Entry<ConfigFieldSpec, JCheckBox> entry : checkBoxes.entrySet()) {
            JCheckBox checkBox = entry.getValue();
            FieldRenderer.RenderedField rf = new FieldRenderer.RenderedField(
                    panel,
                    () -> Boolean.toString(checkBox.isSelected()),
                    value -> checkBox.setSelected("true".equalsIgnoreCase(value)),
                    checkBox,
                    ctx.hiddenValidationError());
            ctx.registerField(entry.getKey(), rf);
        }

        return panel;
    }

    // ── 邮件特殊控件 ───────────────────────────────────────────────────────────

    /**
     * "服务商预设" 下拉：选中预设后自动填入并锁定 host / port / security 三项；选中 "自定义" 解锁。
     * 预设本身不入 config.yaml，由 host 反查推断。
     */
    private JPanel buildMailPresetPanel() {
        mailPresetCombo = new JComboBox<>(mailPresetRegistry.all().toArray(new MailPreset[0]));
        mailPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof MailPreset preset) {
                    label.setText(message(preset.displayNameKey()));
                }
                return label;
            }
        });
        mailPresetCombo.addActionListener(e -> {
            if (updatingMailPresetCombo) {
                return;
            }
            Object selected = mailPresetCombo.getSelectedItem();
            if (selected instanceof MailPreset preset) {
                applyMailPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.preset.label") + message("gui.punctuation.colon"),
                mailPresetCombo,
                null,
                message("gui.config.mail.preset.help"));
    }

    /** "发送测试邮件" 按钮行。 */
    private JPanel buildMailTestPanel() {
        mailTestButton = new JButton(message("gui.config.mail.test-button.label"));
        mailTestButton.addActionListener(e -> sendMailTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-button.label") + message("gui.punctuation.colon"),
                mailTestButton,
                null,
                message("gui.config.mail.test-button.help"));
    }

    /** "发送所有邮件模板" 按钮行：用示例数据遍历全部模板逐封发送。 */
    private JPanel buildMailTestAllPanel() {
        mailTestAllButton = new JButton(message("gui.config.mail.test-all.button.label"));
        mailTestAllButton.addActionListener(e -> sendAllMailTemplates());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-all.button.label") + message("gui.punctuation.colon"),
                mailTestAllButton,
                null,
                message("gui.config.mail.test-all.button.help"));
    }

    /**
     * 应用选中的预设：写入 host / port / security 字段，按是否自定义切换锁定状态。
     *
     * @param userInitiated 由用户操作触发时为 true（会覆盖三项值）；初始化反查时为 false（只更新锁定状态）
     */
    private void applyMailPreset(MailPreset preset, boolean userInitiated) {
        currentMailPreset = preset;
        if (preset == null) {
            return;
        }
        if (userInitiated && !preset.isCustom()) {
            ctx.setFieldValue("mail.host", preset.host());
            ctx.setFieldValue("mail.port", String.valueOf(preset.port()));
            ctx.setFieldValue("mail.security", preset.security().value());
        }
        ctx.updateEnabledStates();
    }

    /** 启动后按已有 host 反查预设；未命中落到 custom。 */
    private void resolveMailPresetFromCurrentHost() {
        if (mailPresetCombo == null) {
            return;
        }
        String host = ctx.currentFieldValue("mail.host");
        MailPreset preset = mailPresetRegistry.findByHost(host).orElseGet(mailPresetRegistry::custom);
        updatingMailPresetCombo = true;
        try {
            mailPresetCombo.setSelectedItem(preset);
        } finally {
            updatingMailPresetCombo = false;
        }
        applyMailPreset(preset, false);
    }

    private void sendMailTest() {
        if (mailTestButton == null) {
            return;
        }
        mailTestButton.setEnabled(false);
        ctx.showNotice(message("gui.config.mail.test.notice.sending"));

        ObjectNode payload = buildMailPayload();

        SwingWorker<MailTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected MailTestOutcome doInBackground() {
                return postMailTest(payload);
            }

            @Override
            protected void done() {
                try {
                    MailTestOutcome outcome = get();
                    if (outcome.reachable()) {
                        if (outcome.success()) {
                            ctx.showNotice(message("gui.config.mail.test.notice.success"));
                        } else {
                            ctx.showNotice(message("gui.config.mail.test.notice.failed",
                                    outcome.error() == null ? "" : outcome.error()));
                        }
                    } else {
                        ctx.showNotice(message("gui.config.mail.test.notice.unreachable"));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.mail.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    ctx.showNotice(message("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    mailTestButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void sendAllMailTemplates() {
        if (mailTestAllButton == null) {
            return;
        }
        mailTestAllButton.setEnabled(false);
        ctx.showNotice(message("gui.config.mail.test-all.notice.sending"));

        ObjectNode payload = buildMailPayload();

        SwingWorker<MailTestAllOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected MailTestAllOutcome doInBackground() {
                return postMailTestAll(payload);
            }

            @Override
            protected void done() {
                try {
                    MailTestAllOutcome outcome = get();
                    if (!outcome.reachable()) {
                        ctx.showNotice(message("gui.config.mail.test.notice.unreachable"));
                    } else if (outcome.success()) {
                        ctx.showNotice(message("gui.config.mail.test-all.notice.success",
                                String.valueOf(outcome.total())));
                    } else if (outcome.succeeded() > 0) {
                        ctx.showNotice(message("gui.config.mail.test-all.notice.partial",
                                String.valueOf(outcome.succeeded()),
                                String.valueOf(outcome.total()),
                                outcome.errorSummary() == null ? "" : outcome.errorSummary()));
                    } else {
                        ctx.showNotice(message("gui.config.mail.test.notice.failed",
                                outcome.errorSummary() == null ? "" : outcome.errorSummary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.mail.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    ctx.showNotice(message("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    mailTestAllButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private ObjectNode buildMailPayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("host", ctx.currentFieldValue("mail.host"));
        payload.put("port", parseIntOrZero(ctx.currentFieldValue("mail.port")));
        payload.put("security", ctx.currentFieldValue("mail.security"));
        payload.put("username", ctx.currentFieldValue("mail.username"));
        payload.put("password", ctx.currentFieldValue("mail.password"));
        payload.put("from", ctx.currentFieldValue("mail.from"));
        payload.put("to", ctx.currentFieldValue("mail.to"));
        payload.put("socksProxy", ctx.currentFieldValue("mail.socks-proxy"));
        payload.put("subjectPrefix", ctx.currentFieldValue("mail.subject-prefix"));
        return payload;
    }

    private MailTestOutcome postMailTest(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new MailTestOutcome(true, false, e.getMessage());
        }
        GuiConfigTestClient.Response resp = ctx.testClient().postJson("mail-test", body, MAIL_TEST_READ_TIMEOUT_MS);
        if (!resp.reachable()) {
            return new MailTestOutcome(false, false, null);
        }
        String responseBody = resp.body();
        if (resp.is2xx()) {
            boolean success = true;
            String error = null;
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    var node = MAPPER.readTree(responseBody);
                    success = node.path("success").asBoolean(false);
                    error = node.path("error").isMissingNode() || node.path("error").isNull()
                            ? null : node.path("error").asText();
                } catch (IOException e) {
                    success = false;
                    error = e.getMessage();
                }
            }
            return new MailTestOutcome(true, success, error);
        }
        // 非 2xx 但已连通；把 body 内容作为错误摘要
        return new MailTestOutcome(true, false,
                (responseBody == null || responseBody.isBlank()) ? ("HTTP " + resp.status()) : responseBody);
    }

    /** mail-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当后端发信成功。 */
    private record MailTestOutcome(boolean reachable, boolean success, String error) {
    }

    private MailTestAllOutcome postMailTestAll(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new MailTestAllOutcome(true, false, 0, 0, e.getMessage());
        }
        GuiConfigTestClient.Response resp =
                ctx.testClient().postJson("mail-test-all", body, MAIL_TEST_ALL_READ_TIMEOUT_MS);
        if (!resp.reachable()) {
            return new MailTestAllOutcome(false, false, 0, 0, null);
        }
        String responseBody = resp.body();
        if (resp.is2xx()) {
            boolean success = false;
            int total = 0;
            int succeeded = 0;
            String errorSummary = null;
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    var node = MAPPER.readTree(responseBody);
                    success = node.path("success").asBoolean(false);
                    total = node.path("total").asInt(0);
                    succeeded = node.path("succeeded").asInt(0);
                    var failures = node.path("failures");
                    if (failures.isArray() && !failures.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < failures.size(); i++) {
                            var f = failures.get(i);
                            if (sb.length() > 0) sb.append("; ");
                            sb.append(f.path("templateId").asText("-"))
                                    .append(": ")
                                    .append(f.path("error").asText(""));
                        }
                        errorSummary = sb.toString();
                    }
                } catch (IOException e) {
                    errorSummary = e.getMessage();
                }
            }
            return new MailTestAllOutcome(true, success, total, succeeded, errorSummary);
        }
        return new MailTestAllOutcome(true, false, 0, 0,
                (responseBody == null || responseBody.isBlank()) ? ("HTTP " + resp.status()) : responseBody);
    }

    /** mail-test-all 异步结果。reachable=false 表示后端连接不上；success=true 仅当全部模板都发信成功。 */
    private record MailTestAllOutcome(boolean reachable, boolean success, int total, int succeeded,
                                      String errorSummary) {
    }

    // ── 推送渠道测试 ───────────────────────────────────────────────────────────

    /** 某个推送渠道卡片底部的「测试此渠道」按钮行。 */
    private JPanel buildPushChannelTestPanel(String channelId) {
        JButton button = new JButton(message("gui.config.push.test-current-button.label"));
        button.addActionListener(e -> sendPushChannelTest(channelId, button));
        return FieldRenderer.fieldPanel(
                message("gui.config.push.test-current-button.label") + message("gui.punctuation.colon"),
                button,
                null,
                message("gui.config.push.test-current-button.help"));
    }

    private JPanel buildPushChannelTestAllPanel(String channelId) {
        JButton button = new JButton(message("gui.config.push.test-all.button.label"));
        button.addActionListener(e -> sendPushChannelTestAll(channelId, button));
        return FieldRenderer.fieldPanel(
                message("gui.config.push.test-all.button.label") + message("gui.punctuation.colon"),
                button,
                null,
                message("gui.config.push.test-all.button.help"));
    }

    /** 用当前表单值向 {@code channelId} 一个渠道发送全部通知消息模板（无需先保存），便于预览各类通知呈现。 */
    private void sendPushChannelTestAll(String channelId, JButton button) {
        button.setEnabled(false);
        ctx.showNotice(message("gui.config.push.test-all.notice.sending"));

        ObjectNode payload = buildPushPayload(channelId);
        SwingWorker<PushTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected PushTestOutcome doInBackground() {
                return postPushTest(payload, "push-test-all");
            }

            @Override
            protected void done() {
                try {
                    PushTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                    } else if (outcome.total() == 0
                            || (outcome.succeeded() == 0 && outcome.summary() != null
                                && outcome.summary().contains("SKIPPED"))) {
                        ctx.showNotice(message("gui.config.push.test-all.notice.skipped"));
                    } else if (outcome.success()) {
                        ctx.showNotice(message("gui.config.push.test-all.notice.success", outcome.total()));
                    } else {
                        ctx.showNotice(message("gui.config.push.test-all.notice.partial",
                                outcome.succeeded(), outcome.total(),
                                outcome.summary() == null ? "" : outcome.summary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.push.test-all.notice.partial",
                            0, 0, safeMessage(ex.getCause())), ex.getCause());
                    ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                } finally {
                    button.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /** 用当前表单值仅测试 {@code channelId} 一个渠道（无需先保存）。 */
    private void sendPushChannelTest(String channelId, JButton button) {
        button.setEnabled(false);
        ctx.showNotice(message("gui.config.push.test.notice.sending"));

        ObjectNode payload = buildPushPayload(channelId);
        SwingWorker<PushTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected PushTestOutcome doInBackground() {
                return postPushTest(payload, "push-test");
            }

            @Override
            protected void done() {
                try {
                    PushTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                    } else if (outcome.total() == 0) {
                        ctx.showNotice(message("gui.config.push.test.notice.none"));
                    } else if (outcome.success()) {
                        ctx.showNotice(message("gui.config.push.test.notice.current-success"));
                    } else if (outcome.summary() != null && outcome.summary().contains("SKIPPED")) {
                        ctx.showNotice(message("gui.config.push.test.notice.current-skipped"));
                    } else {
                        ctx.showNotice(message("gui.config.push.test.notice.current-failed",
                                outcome.summary() == null ? "" : outcome.summary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.push.test.notice.current-failed",
                            safeMessage(ex.getCause())), ex.getCause());
                    ctx.showNotice(message("gui.config.push.test.notice.unreachable"));
                } finally {
                    button.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /**
     * 构造 push-test 请求体：每个渠道都带上当前表单值，但只有 {@code onlyChannelId} 的 enabled=true，
     * 从而只测试当前所选渠道（与各渠道自身的「启用」勾选无关）。
     */
    private ObjectNode buildPushPayload(String onlyChannelId) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode bark = payload.putObject("bark");
        bark.put("enabled", "bark".equals(onlyChannelId));
        bark.put("server", ctx.currentFieldValue("push.bark.server"));
        bark.put("deviceKey", ctx.currentFieldValue("push.bark.device-key"));
        bark.put("sound", ctx.currentFieldValue("push.bark.sound"));
        bark.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.bark.use-proxy")));
        ObjectNode dingtalk = payload.putObject("dingtalk");
        dingtalk.put("enabled", "dingtalk".equals(onlyChannelId));
        dingtalk.put("accessToken", ctx.currentFieldValue("push.dingtalk.access-token"));
        dingtalk.put("secret", ctx.currentFieldValue("push.dingtalk.secret"));
        dingtalk.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.dingtalk.use-proxy")));
        ObjectNode telegram = payload.putObject("telegram");
        telegram.put("enabled", "telegram".equals(onlyChannelId));
        telegram.put("botToken", ctx.currentFieldValue("push.telegram.bot-token"));
        telegram.put("chatId", ctx.currentFieldValue("push.telegram.chat-id"));
        telegram.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.telegram.use-proxy")));
        ObjectNode feishu = payload.putObject("feishu");
        feishu.put("enabled", "feishu".equals(onlyChannelId));
        feishu.put("webhookKey", ctx.currentFieldValue("push.feishu.webhook-key"));
        feishu.put("secret", ctx.currentFieldValue("push.feishu.secret"));
        feishu.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.feishu.use-proxy")));
        ObjectNode wecom = payload.putObject("wecom");
        wecom.put("enabled", "wecom".equals(onlyChannelId));
        wecom.put("key", ctx.currentFieldValue("push.wecom.key"));
        wecom.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.wecom.use-proxy")));
        ObjectNode pushplus = payload.putObject("pushplus");
        pushplus.put("enabled", "pushplus".equals(onlyChannelId));
        pushplus.put("token", ctx.currentFieldValue("push.pushplus.token"));
        pushplus.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.pushplus.use-proxy")));
        ObjectNode serverchan = payload.putObject("serverchan");
        serverchan.put("enabled", "serverchan".equals(onlyChannelId));
        serverchan.put("sendKey", ctx.currentFieldValue("push.serverchan.send-key"));
        serverchan.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.serverchan.use-proxy")));
        ObjectNode webhook = payload.putObject("webhook");
        webhook.put("enabled", "webhook".equals(onlyChannelId));
        webhook.put("url", ctx.currentFieldValue("push.webhook.url"));
        webhook.put("contentType", ctx.currentFieldValue("push.webhook.content-type"));
        webhook.put("bodyTemplate", ctx.currentFieldValue("push.webhook.body-template"));
        webhook.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("push.webhook.use-proxy")));
        return payload;
    }

    /**
     * 调用 {@code /api/gui/<endpoint>}（{@code push-test} 单条测试 / {@code push-test-all} 全部模板）。
     * 单条测试沿用短读超时；全部模板会串行发送多条 webhook，读超时放宽到 10 分钟。
     */
    private PushTestOutcome postPushTest(ObjectNode payload, String endpoint) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new PushTestOutcome(true, false, 0, 0, e.getMessage());
        }
        GuiConfigTestClient.Response resp = ctx.testClient().postJson(endpoint, body, pushTestReadTimeout(endpoint));
        if (!resp.reachable()) {
            return new PushTestOutcome(false, false, 0, 0, null);
        }
        String responseBody = resp.body();
        if (resp.is2xx()) {
            boolean success = false;
            int total = 0;
            int succeeded = 0;
            String summary = null;
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    var node = MAPPER.readTree(responseBody);
                    success = node.path("success").asBoolean(false);
                    total = node.path("total").asInt(0);
                    succeeded = node.path("succeeded").asInt(0);
                    var results = node.path("results");
                    if (results.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (var item : results) {
                            if (!"OK".equals(item.path("status").asText(""))) {
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(item.path("channel").asText("-"))
                                        .append(": ")
                                        .append(item.path("status").asText(""));
                                String detail = item.path("detail").asText("");
                                if (!detail.isBlank()) {
                                    sb.append(" (").append(detail).append(')');
                                }
                            }
                        }
                        if (sb.length() > 0) summary = sb.toString();
                    }
                } catch (IOException e) {
                    summary = e.getMessage();
                }
            }
            return new PushTestOutcome(true, success, total, succeeded, summary);
        }
        return new PushTestOutcome(true, false, 0, 0,
                (responseBody == null || responseBody.isBlank()) ? ("HTTP " + resp.status()) : responseBody);
    }

    private static int pushTestReadTimeout(String endpoint) {
        return "push-test-all".equals(endpoint)
                ? PUSH_TEST_ALL_READ_TIMEOUT_MS
                : PUSH_TEST_READ_TIMEOUT_MS;
    }

    /** push-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当全部通道都发送成功。 */
    private record PushTestOutcome(boolean reachable, boolean success, int total, int succeeded, String summary) {
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static int parseIntOrZero(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
