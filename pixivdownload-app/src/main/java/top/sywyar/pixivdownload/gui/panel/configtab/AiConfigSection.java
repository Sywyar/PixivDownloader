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

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * 「AI 模型」分组标签页：文本模型与 TTS 模型合并，用模态下拉切换当前编辑的字段卡片。
 * <p>
 * 兼容迁移过渡层：入口由 {@link GuiConfigSectionResolver} 生成的 section contribution adapter 接入，
 * 用于保持既有 AI/TTS 富布局不发生用户可见变化。新的插件字段、preset 或测试动作应通过
 * {@code plugin.api.gui} 的声明式 section/action/preset contribution 表达，而不是继续扩展本类的
 * 字段前缀或供应商列表。
 */
@Slf4j
public final class AiConfigSection implements ConfigSection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int AI_TEST_READ_TIMEOUT_MS = 120_000;

    private final ConfigSectionContext ctx;
    private final String aiGroup = ConfigFieldRegistry.groupAi();
    private final String narrationTtsGroup = ConfigFieldRegistry.groupNarrationTts();

    private final AiPresetRegistry aiPresetRegistry = new AiPresetRegistry();
    private JComboBox<AiPreset> aiPresetCombo;
    private JButton aiTestButton;
    private AiPreset currentAiPreset;
    private boolean updatingAiPresetCombo;
    private JComboBox<String> narrationEngineCombo;

    public AiConfigSection(ConfigSectionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String group() {
        return aiGroup;
    }

    @Override
    public void onValuesLoaded() {
        resolveAiPresetFromCurrentBaseUrl();
    }

    @Override
    public void afterEnabledStates() {
        if (aiPresetCombo == null || currentAiPreset == null || currentAiPreset.isCustom()) {
            return;
        }
        ctx.lockField("ai.base-url");
    }

    private record AiModality(String id, String displayKey) {
    }

    @Override
    public JComponent build() {
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        Map<String, JComponent> cards = new LinkedHashMap<>();
        if (!fieldsByGroup(aiGroup).isEmpty()) {
            cards.put("text", buildAiTextCard());
        }
        if (!fieldsByGroup(narrationTtsGroup).isEmpty()) {
            cards.put("tts", buildAiTtsCard());
        }

        if (cards.size() > 1) {
            addModalitySwitcher(content, cards);
        } else if (!cards.isEmpty()) {
            content.add(cards.values().iterator().next());
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    private void addModalitySwitcher(JPanel content, Map<String, JComponent> cards) {
        List<AiModality> modalities = List.of(
                new AiModality("text", "gui.config.ai.modality.text"),
                new AiModality("tts", "gui.config.ai.modality.tts"));
        List<AiModality> available = modalities.stream()
                .filter(modality -> cards.containsKey(modality.id()))
                .toList();

        JComboBox<AiModality> modalityCombo =
                new JComboBox<>(available.toArray(new AiModality[0]));
        modalityCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiModality modality) {
                    label.setText(message(modality.displayKey()));
                }
                return label;
            }
        });
        JPanel comboRow = FieldRenderer.fieldPanel(
                message("gui.config.ai.modality.label") + message("gui.punctuation.colon"),
                modalityCombo, null, message("gui.config.ai.modality.help"));
        comboRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(comboRow);
        content.add(Box.createVerticalStrut(2));

        JPanel cardHost = manualSwapHost();
        modalityCombo.addActionListener(e -> {
            if (modalityCombo.getSelectedItem() instanceof AiModality modality) {
                GuiThemeRefresh.showCard(cardHost, cards.get(modality.id()));
            }
        });
        GuiThemeRefresh.showCard(cardHost, cards.get(available.get(0).id()));
        content.add(cardHost);
    }

    private JComponent buildAiTextCard() {
        JPanel content = ctx.newContentPanel();

        JPanel aiPresetPanel = buildAiPresetPanel();
        aiPresetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(aiPresetPanel);
        content.add(Box.createVerticalStrut(2));

        ctx.addFields(content, fieldsByGroup(aiGroup));

        JPanel aiTestPanel = buildAiTestPanel();
        aiTestPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(aiTestPanel);
        content.add(Box.createVerticalStrut(2));
        return content;
    }

    private JPanel buildAiPresetPanel() {
        aiPresetCombo = new JComboBox<>(aiPresetRegistry.all().toArray(new AiPreset[0]));
        aiPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiPreset preset) {
                    label.setText(message(preset.displayNameKey()));
                }
                return label;
            }
        });
        aiPresetCombo.addActionListener(e -> {
            if (updatingAiPresetCombo) {
                return;
            }
            if (aiPresetCombo.getSelectedItem() instanceof AiPreset preset) {
                applyAiPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.preset.label") + message("gui.punctuation.colon"),
                aiPresetCombo,
                null,
                message("gui.config.ai.preset.help"));
    }

    private JComponent buildAiTtsCard() {
        JPanel content = ctx.newContentPanel();

        ConfigFieldSpec engineSpec = ctx.findSpec("narration-tts.engine");
        if (engineSpec == null) {
            ctx.addFields(content, fieldsByGroup(narrationTtsGroup));
            return content;
        }

        JPanel enginePanel = buildNarrationEnginePanel(engineSpec);
        enginePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(enginePanel);
        content.add(Box.createVerticalStrut(2));

        Map<String, JComponent> cards = new LinkedHashMap<>();
        for (String engine : engineSpec.enumValues()) {
            cards.put(engine, buildNarrationEngineCard(engine));
        }
        JPanel engineCardHost = manualSwapHost();
        narrationEngineCombo.addActionListener(e -> {
            if (narrationEngineCombo.getSelectedItem() instanceof String engine) {
                GuiThemeRefresh.showCard(engineCardHost, cards.get(engine));
            }
        });
        String initial = narrationEngineCombo.getSelectedItem() instanceof String selected
                ? selected
                : engineSpec.defaultValue();
        GuiThemeRefresh.showCard(engineCardHost, cards.get(initial));
        content.add(engineCardHost);
        return content;
    }

    private JPanel buildNarrationEnginePanel(ConfigFieldSpec engineSpec) {
        narrationEngineCombo = new JComboBox<>(engineSpec.enumValues().toArray(new String[0]));
        narrationEngineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof String id) {
                    label.setText(message("gui.config.field.narration-tts.engine.value." + id));
                }
                return label;
            }
        });
        narrationEngineCombo.setSelectedItem(engineSpec.defaultValue());

        JPanel panel = FieldRenderer.fieldPanel(
                engineSpec.label() + message("gui.punctuation.colon"),
                narrationEngineCombo,
                ctx.effectLabel(engineSpec.requiresRestart()),
                engineSpec.helpText());

        FieldRenderer.RenderedField rf = new FieldRenderer.RenderedField(
                panel,
                () -> (String) narrationEngineCombo.getSelectedItem(),
                value -> narrationEngineCombo.setSelectedItem(value),
                narrationEngineCombo,
                ctx.hiddenValidationError());
        ctx.registerField(engineSpec, rf);
        return panel;
    }

    private JComponent buildNarrationEngineCard(String engine) {
        JPanel content = ctx.newContentPanel();
        ctx.addFields(content, narrationFieldsByEngine(engine));
        return content;
    }

    private JPanel buildAiTestPanel() {
        aiTestButton = new JButton(message("gui.config.ai.test-button.label"));
        aiTestButton.addActionListener(e -> sendAiTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.test-button.label") + message("gui.punctuation.colon"),
                aiTestButton,
                null,
                message("gui.config.ai.test-button.help"));
    }

    private void sendAiTest() {
        if (aiTestButton == null) {
            return;
        }
        aiTestButton.setEnabled(false);
        ctx.showNotice(message("gui.config.ai.test.notice.sending"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("baseUrl", ctx.currentFieldValue("ai.base-url"));
        payload.put("apiKey", ctx.currentFieldValue("ai.api-key"));
        payload.put("model", ctx.currentFieldValue("ai.model"));
        payload.put("useProxy", Boolean.parseBoolean(ctx.currentFieldValue("ai.use-proxy")));

        SwingWorker<AiTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected AiTestOutcome doInBackground() {
                return postAiTest(payload);
            }

            @Override
            protected void done() {
                try {
                    AiTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        ctx.showNotice(message("gui.config.ai.test.notice.unreachable"));
                    } else if (outcome.success()) {
                        ctx.showNotice(message("gui.config.ai.test.notice.success",
                                outcome.reply() == null ? "" : outcome.reply()));
                    } else {
                        ctx.showNotice(message("gui.config.ai.test.notice.failed",
                                outcome.error() == null ? "" : outcome.error()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.showNotice(message("gui.config.ai.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.ai.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    ctx.showNotice(message("gui.config.ai.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    aiTestButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void applyAiPreset(AiPreset preset, boolean userInitiated) {
        currentAiPreset = preset;
        if (preset == null) {
            return;
        }
        if (userInitiated && !preset.isCustom()) {
            ctx.setFieldValue("ai.base-url", preset.baseUrl());
            ctx.setFieldValue("ai.model", preset.defaultModel());
            ctx.setFieldValue("ai.use-proxy", Boolean.toString(preset.defaultUseProxy()));
        }
        ctx.updateEnabledStates();
    }

    private void resolveAiPresetFromCurrentBaseUrl() {
        if (aiPresetCombo == null) {
            return;
        }
        String baseUrl = ctx.currentFieldValue("ai.base-url");
        AiPreset preset = aiPresetRegistry.findByBaseUrl(baseUrl).orElseGet(aiPresetRegistry::custom);
        updatingAiPresetCombo = true;
        try {
            aiPresetCombo.setSelectedItem(preset);
        } finally {
            updatingAiPresetCombo = false;
        }
        applyAiPreset(preset, false);
    }

    private AiTestOutcome postAiTest(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new AiTestOutcome(true, false, e.getMessage(), null);
        }
        GuiConfigTestClient.Response resp = ctx.testClient().postJson("ai-test", body, AI_TEST_READ_TIMEOUT_MS);
        if (!resp.reachable()) {
            return new AiTestOutcome(false, false, null, null);
        }
        String responseBody = resp.body();
        if (resp.is2xx()) {
            boolean success = false;
            String error = null;
            String reply = null;
            if (responseBody != null && !responseBody.isBlank()) {
                try {
                    var node = MAPPER.readTree(responseBody);
                    success = node.path("success").asBoolean(false);
                    error = node.path("error").isMissingNode() || node.path("error").isNull()
                            ? null : node.path("error").asText();
                    reply = node.path("reply").isMissingNode() || node.path("reply").isNull()
                            ? null : node.path("reply").asText();
                } catch (IOException e) {
                    success = false;
                    error = e.getMessage();
                }
            }
            return new AiTestOutcome(true, success, error, reply);
        }
        return new AiTestOutcome(true, false,
                (responseBody == null || responseBody.isBlank()) ? ("HTTP " + resp.status()) : responseBody, null);
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

    private List<ConfigFieldSpec> narrationFieldsByEngine(String engine) {
        String prefix = "narration-tts." + engine + ".";
        return ctx.allFields().stream()
                .filter(field -> narrationTtsGroup.equals(field.group()))
                .filter(field -> field.key().startsWith(prefix))
                .toList();
    }

    private List<ConfigFieldSpec> fieldsByGroup(String group) {
        return ctx.allFields().stream()
                .filter(field -> group.equals(field.group()))
                .toList();
    }

    private record AiTestOutcome(boolean reachable, boolean success, String error, String reply) {
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

    private record AiPreset(String id, String displayNameKey, String baseUrl, String defaultModel,
                            boolean defaultUseProxy) {

        private static final String CUSTOM_ID = "custom";

        private boolean isCustom() {
            return CUSTOM_ID.equals(id);
        }
    }

    private static final class AiPresetRegistry {
        private final List<AiPreset> presets = List.of(
                preset("openai", "https://api.openai.com/v1", "gpt-5.4-mini", true),
                preset("anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5", true),
                preset("gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                        "gemini-2.5-flash", true),
                preset("xai", "https://api.x.ai/v1", "grok-4", true),
                preset("mistral", "https://api.mistral.ai/v1", "mistral-large-latest", true),
                preset("groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true),
                preset("deepseek", "https://api.deepseek.com", "deepseek-v4-flash", false),
                preset("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false),
                preset("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", false),
                preset("moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false),
                preset("doubao", "https://ark.cn-beijing.volces.com/api/v3",
                        "doubao-seed-1-6-250615", false),
                preset("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1",
                        "hunyuan-turbos-latest", false),
                preset("ernie", "https://qianfan.baidubce.com/v2", "ernie-4.0-turbo-8k", false),
                preset("spark", "https://spark-api-open.xf-yun.com/v1", "generalv3.5", false),
                preset("minimax", "https://api.minimaxi.com/v1", "MiniMax-M2", false),
                preset("ollama", "http://localhost:11434/v1", "llama3.1", false),
                preset("lmstudio", "http://localhost:1234/v1", "", false),
                preset("openrouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", true),
                preset("siliconflow", "https://api.siliconflow.cn/v1",
                        "Qwen/Qwen2.5-7B-Instruct", false),
                new AiPreset(AiPreset.CUSTOM_ID, "ai.preset.name." + AiPreset.CUSTOM_ID, "", "", false));

        private List<AiPreset> all() {
            return presets;
        }

        private Optional<AiPreset> findByBaseUrl(String baseUrl) {
            String normalized = normalize(baseUrl);
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
            return presets.stream()
                    .filter(preset -> !preset.isCustom())
                    .filter(preset -> normalize(preset.baseUrl()).equals(normalized))
                    .findFirst();
        }

        private AiPreset custom() {
            return presets.get(presets.size() - 1);
        }

        private static AiPreset preset(String id, String baseUrl, String defaultModel,
                                       boolean defaultUseProxy) {
            return new AiPreset(id, "ai.preset.name." + id, baseUrl, defaultModel, defaultUseProxy);
        }

        private static String normalize(String baseUrl) {
            if (baseUrl == null) {
                return "";
            }
            String normalized = baseUrl.trim().toLowerCase(Locale.ROOT);
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }
}
