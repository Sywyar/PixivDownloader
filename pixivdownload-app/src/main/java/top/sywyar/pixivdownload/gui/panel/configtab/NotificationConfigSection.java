package top.sywyar.pixivdownload.gui.panel.configtab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiThemeRefresh;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.notification.NotificationConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * 「通知」分组标签页：邮件 / SMTP 与推送插件字段合并到旧式服务切换布局，通知场景开关保持紧凑复选框网格。
 */
@Slf4j
public final class NotificationConfigSection implements ConfigSection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAIL_TEST_READ_TIMEOUT_MS = 30_000;
    private static final int MAIL_TEST_ALL_READ_TIMEOUT_MS = 180_000;
    private static final int PUSH_TEST_READ_TIMEOUT_MS = 30_000;
    private static final int PUSH_TEST_ALL_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private final ConfigSectionContext ctx;
    private final String notificationGroup = ConfigFieldRegistry.groupNotification();

    private final MailPresetRegistry mailPresetRegistry = new MailPresetRegistry();
    private JComboBox<MailPreset> mailPresetCombo;
    private JButton mailTestButton;
    private JButton mailTestAllButton;
    private MailPreset currentMailPreset;
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
        if (mailPresetCombo == null || currentMailPreset == null || currentMailPreset.isCustom()) {
            return;
        }
        ctx.lockField("mail.host");
        ctx.lockField("mail.port");
        ctx.lockField("mail.security");
    }

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

        List<NotificationService> services = availableServices();
        if (!services.isEmpty()) {
            JLabel hint = new JLabel(message("gui.config.notification.hint"));
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
            hint.setForeground(new Color(0, 128, 96));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(hint);
            content.add(Box.createVerticalStrut(4));
        }

        addPushEnabledField(content);
        addScenarioPanel(content);
        if (!services.isEmpty()) {
            addServiceSwitcher(content, services);
        }
        ctx.addFields(content, residualNotificationFields());
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    private List<NotificationService> availableServices() {
        return notificationServices().stream()
                .filter(service -> "mail".equals(service.id())
                        ? hasFieldsByPrefix("mail.")
                        : hasFieldsByPrefix("push." + service.id() + "."))
                .toList();
    }

    private void addPushEnabledField(JPanel content) {
        ConfigFieldSpec pushEnabledSpec = ctx.findSpec("push.enabled");
        if (pushEnabledSpec == null) {
            return;
        }
        FieldRenderer.RenderedField rf = FieldRenderer.render(pushEnabledSpec);
        ctx.registerField(pushEnabledSpec, rf);
        rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(rf.panel());
        content.add(Box.createVerticalStrut(2));
    }

    private void addScenarioPanel(JPanel content) {
        JPanel scenarioPanel = buildNotificationScenarioPanel();
        if (scenarioPanel == null) {
            return;
        }
        scenarioPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(scenarioPanel);
        content.add(Box.createVerticalStrut(2));
    }

    private void addServiceSwitcher(JPanel content, List<NotificationService> services) {
        JComboBox<NotificationService> serviceCombo =
                new JComboBox<>(services.toArray(new NotificationService[0]));
        serviceCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof NotificationService service) {
                    label.setText(message(service.displayKey()));
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

        Map<String, JComponent> cards = new LinkedHashMap<>();
        for (NotificationService service : services) {
            cards.put(service.id(), buildServiceCard(service));
        }
        JPanel cardHost = manualSwapHost();
        serviceCombo.addActionListener(e -> {
            if (serviceCombo.getSelectedItem() instanceof NotificationService service) {
                GuiThemeRefresh.showCard(cardHost, cards.get(service.id()));
            }
        });
        GuiThemeRefresh.showCard(cardHost, cards.get(services.get(0).id()));
        content.add(cardHost);
    }

    private JComponent buildServiceCard(NotificationService service) {
        JPanel content = ctx.newContentPanel();

        if ("mail".equals(service.id())) {
            JPanel preset = buildMailPresetPanel();
            preset.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(preset);
            content.add(Box.createVerticalStrut(2));
            ctx.addFields(content, fieldsByPrefix("mail."));
            addPanel(content, buildMailTestPanel());
            addPanel(content, buildMailTestAllPanel());
            return content;
        }

        ctx.addFields(content, fieldsByPrefix("push." + service.id() + "."));
        addPanel(content, buildPushChannelTestPanel(service.id()));
        addPanel(content, buildPushChannelTestAllPanel(service.id()));
        return content;
    }

    private static void addPanel(JPanel content, JPanel panel) {
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(panel);
        content.add(Box.createVerticalStrut(2));
    }

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

    private JPanel buildMailPresetPanel() {
        mailPresetCombo = new JComboBox<>(mailPresetRegistry.all().toArray(new MailPreset[0]));
        mailPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
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
            if (mailPresetCombo.getSelectedItem() instanceof MailPreset preset) {
                applyMailPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.preset.label") + message("gui.punctuation.colon"),
                mailPresetCombo,
                null,
                message("gui.config.mail.preset.help"));
    }

    private JPanel buildMailTestPanel() {
        mailTestButton = new JButton(message("gui.config.mail.test-button.label"));
        mailTestButton.addActionListener(e -> sendMailTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-button.label") + message("gui.punctuation.colon"),
                mailTestButton,
                null,
                message("gui.config.mail.test-button.help"));
    }

    private JPanel buildMailTestAllPanel() {
        mailTestAllButton = new JButton(message("gui.config.mail.test-all.button.label"));
        mailTestAllButton.addActionListener(e -> sendAllMailTemplates());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-all.button.label") + message("gui.punctuation.colon"),
                mailTestAllButton,
                null,
                message("gui.config.mail.test-all.button.help"));
    }

    private void applyMailPreset(MailPreset preset, boolean userInitiated) {
        currentMailPreset = preset;
        if (preset == null) {
            return;
        }
        if (userInitiated && !preset.isCustom()) {
            ctx.setFieldValue("mail.host", preset.host());
            ctx.setFieldValue("mail.port", String.valueOf(preset.port()));
            ctx.setFieldValue("mail.security", preset.security());
        }
        ctx.updateEnabledStates();
    }

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
                    if (!outcome.reachable()) {
                        ctx.showNotice(message("gui.config.mail.test.notice.unreachable"));
                    } else if (outcome.success()) {
                        ctx.showNotice(message("gui.config.mail.test.notice.success"));
                    } else {
                        ctx.showNotice(message("gui.config.mail.test.notice.failed",
                                outcome.error() == null ? "" : outcome.error()));
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
        return new MailTestOutcome(true, false,
                (responseBody == null || responseBody.isBlank()) ? ("HTTP " + resp.status()) : responseBody);
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
                            var failure = failures.get(i);
                            if (sb.length() > 0) {
                                sb.append("; ");
                            }
                            sb.append(failure.path("templateId").asText("-"))
                                    .append(": ")
                                    .append(failure.path("error").asText(""));
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
                                if (sb.length() > 0) {
                                    sb.append("; ");
                                }
                                sb.append(item.path("channel").asText("-"))
                                        .append(": ")
                                        .append(item.path("status").asText(""));
                                String detail = item.path("detail").asText("");
                                if (!detail.isBlank()) {
                                    sb.append(" (").append(detail).append(')');
                                }
                            }
                        }
                        if (sb.length() > 0) {
                            summary = sb.toString();
                        }
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

    private List<ConfigFieldSpec> residualNotificationFields() {
        return ctx.allFields().stream()
                .filter(field -> notificationGroup.equals(field.group()))
                .filter(field -> !field.key().startsWith(NotificationConfig.KEY_SCENARIO_PREFIX))
                .filter(field -> !"push.enabled".equals(field.key()))
                .filter(field -> !field.key().startsWith("mail."))
                .filter(field -> notificationServices().stream()
                        .noneMatch(service -> field.key().startsWith("push." + service.id() + ".")))
                .toList();
    }

    private List<ConfigFieldSpec> fieldsByPrefix(String prefix) {
        return ctx.allFields().stream()
                .filter(field -> notificationGroup.equals(field.group()))
                .filter(field -> field.key().startsWith(prefix))
                .toList();
    }

    private boolean hasFieldsByPrefix(String prefix) {
        return ctx.allFields().stream()
                .anyMatch(field -> notificationGroup.equals(field.group()) && field.key().startsWith(prefix));
    }

    private static JPanel manualSwapHost() {
        JPanel host = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        host.setOpaque(false);
        host.setAlignmentX(Component.LEFT_ALIGNMENT);
        return host;
    }

    private static int pushTestReadTimeout(String endpoint) {
        return "push-test-all".equals(endpoint)
                ? PUSH_TEST_ALL_READ_TIMEOUT_MS
                : PUSH_TEST_READ_TIMEOUT_MS;
    }

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

    private record MailTestOutcome(boolean reachable, boolean success, String error) {
    }

    private record MailTestAllOutcome(boolean reachable, boolean success, int total, int succeeded,
                                      String errorSummary) {
    }

    private record PushTestOutcome(boolean reachable, boolean success, int total, int succeeded, String summary) {
    }

    private record MailPreset(String id, String displayNameKey, String host, int port, String security) {

        private static final String CUSTOM_ID = "custom";

        private boolean isCustom() {
            return CUSTOM_ID.equals(id);
        }
    }

    private static final class MailPresetRegistry {
        private final List<MailPreset> presets = List.of(
                preset("netease-163", "smtp.163.com", 465, "ssl"),
                preset("netease-126", "smtp.126.com", 465, "ssl"),
                preset("netease-yeah", "smtp.yeah.net", 465, "ssl"),
                preset("qq", "smtp.qq.com", 465, "ssl"),
                preset("sina", "smtp.sina.com", 465, "ssl"),
                preset("gmail", "smtp.gmail.com", 587, "starttls"),
                preset("outlook", "smtp-mail.outlook.com", 587, "starttls"),
                preset("icloud", "smtp.mail.me.com", 587, "starttls"),
                preset("yahoo", "smtp.mail.yahoo.com", 465, "ssl"),
                preset("netease-qiye", "smtp.qiye.163.com", 465, "ssl"),
                preset("tencent-exmail", "smtp.exmail.qq.com", 465, "ssl"),
                preset("aliyun-qiye", "smtp.qiye.aliyun.com", 465, "ssl"),
                preset("ms365", "smtp.office365.com", 587, "starttls"),
                preset("google-workspace", "smtp.gmail.com", 587, "starttls"),
                new MailPreset(MailPreset.CUSTOM_ID, "mail.preset.name." + MailPreset.CUSTOM_ID,
                        "", 0, "starttls"));

        private List<MailPreset> all() {
            return presets;
        }

        private Optional<MailPreset> findByHost(String host) {
            if (host == null || host.isBlank()) {
                return Optional.empty();
            }
            String normalized = host.trim().toLowerCase(Locale.ROOT);
            return presets.stream()
                    .filter(preset -> !preset.isCustom())
                    .filter(preset -> preset.host().equalsIgnoreCase(normalized))
                    .findFirst();
        }

        private MailPreset custom() {
            return presets.get(presets.size() - 1);
        }

        private static MailPreset preset(String id, String host, int port, String security) {
            return new MailPreset(id, "mail.preset.name." + id, host, port, security);
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
