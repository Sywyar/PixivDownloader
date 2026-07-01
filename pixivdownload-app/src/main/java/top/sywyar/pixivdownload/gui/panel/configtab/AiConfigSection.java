package top.sywyar.pixivdownload.gui.panel.configtab;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.ai.preset.AiPreset;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiThemeRefresh;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * 「AI 模型」分组标签页：文本模型（{@code ai.*}，通用 OpenAI 兼容大语言模型）+ TTS 模型
 * （{@code narration-tts.*}，多角色听小说朗读引擎）合并，用「模态」下拉切换编辑，两类配置同时持久化、同时生效。
 * <p>
 * 与通知页一致是「单页整体滚动」：模态下拉、TTS 引擎下拉、各卡片字段全部放进同一个 {@code GroupContentPanel} /
 * {@code JScrollPane}，没有任何控件固定在顶部（不用 {@code BorderLayout.NORTH}）；模态与引擎都用「手动换卡」
 * （{@code GuiThemeRefresh.showCard(...)}）而非 {@code CardLayout}，让滚动高度始终贴合当前卡片、避免预留最高卡片高度。
 * 所有模态 / 引擎卡片在构建时一次性创建并注册字段，因此同一时刻只展示一张也不影响全部配置的加载 / 保存。
 * <p>
 * {@code narration-tts.*} 字段仍挂在 {@code ConfigFieldRegistry.groupNarrationTts()} 分组下，但该分组不进
 * {@code groups()} 标签列表，而是由本页「TTS 模型」卡片按分组过滤渲染——找不到独立的「AI 听小说朗读」标签是预期行为。
 */
@Slf4j
public final class AiConfigSection implements ConfigSection {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    /** ai-test 读超时放宽到 120s：大模型尤其是推理模型响应可能较慢。 */
    private static final int AI_TEST_READ_TIMEOUT_MS = 120_000;

    private final ConfigSectionContext ctx;
    private final String aiGroup = ConfigFieldRegistry.groupAi();
    private final String narrationTtsGroup = ConfigFieldRegistry.groupNarrationTts();

    /** AI 服务商预设注册中心（不可变）。 */
    private final AiPresetRegistry aiPresetRegistry = new AiPresetRegistry();
    /** AI 服务商预设下拉。 */
    private JComboBox<AiPreset> aiPresetCombo;
    /** "测试 AI 连接" 按钮，测试中暂时禁用。 */
    private JButton aiTestButton;
    /** 当前 AI 预设；非 custom 时锁定 base-url。 */
    private AiPreset currentAiPreset;
    /** 防止在程序性更新下拉时反向触发预设应用。 */
    private boolean updatingAiPresetCombo;
    /** 「朗读引擎」下拉（友好名渲染，对齐文本模型服务商预设下拉）；切换仅改变当前编辑的引擎卡片。 */
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
        // 选中非自定义预设时 base-url 保持禁用。
        if (aiPresetCombo == null || currentAiPreset == null || currentAiPreset.isCustom()) {
            return;
        }
        ctx.lockField("ai.base-url");
    }

    // ── 模态下拉（文本模型 / TTS 模型）─────────────────────────────────────────

    /** AI 模态项：id 用于手动换卡，displayKey 为 i18n 显示名。 */
    private record AiModality(String id, String displayKey) {
    }

    private static List<AiModality> aiModalities() {
        return List.of(
                new AiModality("text", "gui.config.ai.modality.text"),
                new AiModality("tts", "gui.config.ai.modality.tts"));
    }

    @Override
    public JComponent build() {
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JComboBox<AiModality> modalityCombo =
                new JComboBox<>(aiModalities().toArray(new AiModality[0]));
        modalityCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiModality m) {
                    label.setText(message(m.displayKey()));
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

        // 预构建两张模态卡片（字段构建时即注册），手动换卡而非 CardLayout：避免按最高卡片预留高度、在统一滚动页留白。
        Map<String, JComponent> cards = new LinkedHashMap<>();
        cards.put("text", buildAiTextCard());
        cards.put("tts", buildAiTtsCard());
        JPanel cardHost = manualSwapHost();
        modalityCombo.addActionListener(e -> {
            if (modalityCombo.getSelectedItem() instanceof AiModality m) {
                GuiThemeRefresh.showCard(cardHost, cards.get(m.id()));
            }
        });
        GuiThemeRefresh.showCard(cardHost, cards.get(aiModalities().get(0).id()));
        content.add(cardHost);
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 预设锁定会在 init 阶段触发 scrollRectToVisible 让视口偏离 (0,0)；首次显示该分组时强制回到顶部。
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    /** 「文本模型」卡片：服务商预设 + {@code ai.*} 字段 + 连接测试。不自带滚动条，随分组单页整体滚动。 */
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

    /**
     * 「TTS 模型」卡片：「朗读引擎」下拉 + 按引擎手动换卡，仅显示并校验所选引擎的
     * {@code narration-tts.<engine>.*} 参数（多角色听小说朗读的语音合成引擎）。不自带滚动条，随分组单页整体滚动；
     * 所有引擎的字段在构建时即注册并同时持久化。引擎下拉本身也随页滚动，不固定在顶部。
     */
    private JComponent buildAiTtsCard() {
        JPanel content = ctx.newContentPanel();

        ConfigFieldSpec engineSpec = ctx.findSpec("narration-tts.engine");
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
        String initial = narrationEngineCombo.getSelectedItem() instanceof String s ? s : engineSpec.defaultValue();
        GuiThemeRefresh.showCard(engineCardHost, cards.get(initial));
        content.add(engineCardHost);
        return content;
    }

    /**
     * 「朗读引擎」下拉：用友好名（i18n）渲染各引擎，与文本模型「服务商预设」下拉格式一致；
     * 选中值仍为引擎 id 并注册为 {@code narration-tts.engine} 的字段。
     */
    private JPanel buildNarrationEnginePanel(ConfigFieldSpec engineSpec) {
        narrationEngineCombo = new JComboBox<>(engineSpec.enumValues().toArray(new String[0]));
        narrationEngineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
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

    /** 单个朗读引擎的参数卡片：仅含 {@code narration-tts.<engine>.*} 字段。不自带滚动条，随分组单页整体滚动。 */
    private JComponent buildNarrationEngineCard(String engine) {
        JPanel content = ctx.newContentPanel();
        ctx.addFields(content, narrationFieldsByEngine(engine));
        return content;
    }

    /** 手动换卡的容器：BorderLayout 承载当前卡片，最大高度贴合首选高度，避免在 BoxLayout 里被纵向拉伸。 */
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

    /** 取某朗读引擎的全部参数字段（{@code narration-tts.<engine>.*}，不含 {@code narration-tts.engine} 自身）。 */
    private List<ConfigFieldSpec> narrationFieldsByEngine(String engine) {
        String prefix = "narration-tts." + engine + ".";
        return ctx.allFields().stream()
                .filter(f -> narrationTtsGroup.equals(f.group()))
                .filter(f -> f.key().startsWith(prefix))
                .toList();
    }

    private List<ConfigFieldSpec> fieldsByGroup(String group) {
        return ctx.allFields().stream()
                .filter(f -> group.equals(f.group()))
                .toList();
    }

    // ── AI 文本模型特殊控件 ─────────────────────────────────────────────────────

    /**
     * "服务商预设" 下拉：选中预设后自动填入 base-url（锁定）/ 模型 / 是否走代理；选中 "自定义" 解锁。
     * 预设本身不入 config.yaml，由 base-url 反查推断。
     */
    private JPanel buildAiPresetPanel() {
        aiPresetCombo = new JComboBox<>(aiPresetRegistry.all().toArray(new AiPreset[0]));
        aiPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
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
            Object selected = aiPresetCombo.getSelectedItem();
            if (selected instanceof AiPreset preset) {
                applyAiPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.preset.label") + message("gui.punctuation.colon"),
                aiPresetCombo,
                null,
                message("gui.config.ai.preset.help"));
    }

    /** "测试 AI 连接" 按钮行。 */
    private JPanel buildAiTestPanel() {
        aiTestButton = new JButton(message("gui.config.ai.test-button.label"));
        aiTestButton.addActionListener(e -> sendAiTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.test-button.label") + message("gui.punctuation.colon"),
                aiTestButton,
                null,
                message("gui.config.ai.test-button.help"));
    }

    /**
     * 应用选中的 AI 预设：base-url 写入并锁定；模型 / 是否走代理仅作为建议默认值回填（不锁定，用户可改）。
     *
     * @param userInitiated 由用户操作触发时为 true（会覆盖回填值）；初始化反查时为 false（只更新锁定状态）
     */
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

    /** 启动后按已有 base-url 反查预设；未命中落到 custom。 */
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

    /** ai-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当成功拿到模型回复。 */
    private record AiTestOutcome(boolean reachable, boolean success, String error, String reply) {
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

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
