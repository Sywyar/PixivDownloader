package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Combines multiple renderable blocks into one configuration group tab.
 */
final class CompositeConfigSection implements ConfigSection {

    private final ConfigSectionContext ctx;
    private final String group;
    private final List<ConfigSectionBlock> blocks;
    private final List<ConfigSection> builtSections = new ArrayList<>();

    CompositeConfigSection(ConfigSectionContext ctx, String group, List<ConfigSectionBlock> blocks) {
        this.ctx = ctx;
        this.group = group;
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public JComponent build() {
        builtSections.clear();
        Set<String> renderedKeys = new LinkedHashSet<>();
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        for (ConfigSectionBlock block : blocks) {
            ConsumingConfigSectionContext blockCtx =
                    new ConsumingConfigSectionContext(ctx, renderedKeys);
            ConfigSection section = block.createSection(blockCtx);
            builtSections.add(section);
            JComponent blockComponent = unwrapSectionContent(section.build());
            removeTrailingVerticalGlue(blockComponent);
            blockComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(blockComponent);
            content.add(Box.createVerticalStrut(2));
            renderedKeys.addAll(block.consumedFieldKeys(ctx));
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    @Override
    public void afterEnabledStates() {
        for (ConfigSection section : builtSections) {
            section.afterEnabledStates();
        }
    }

    @Override
    public void onValuesLoaded() {
        for (ConfigSection section : builtSections) {
            section.onValuesLoaded();
        }
    }

    @Override
    public boolean onSave() throws IOException {
        boolean changed = false;
        for (ConfigSection section : builtSections) {
            if (section.onSave()) {
                changed = true;
            }
        }
        return changed;
    }

    List<ConfigSectionBlock> blocks() {
        return blocks;
    }

    private static JComponent unwrapSectionContent(JComponent component) {
        if (component instanceof JScrollPane scrollPane
                && scrollPane.getViewport().getView() instanceof JComponent content) {
            return content;
        }
        return component;
    }

    private static void removeTrailingVerticalGlue(JComponent component) {
        if (!(component instanceof JPanel panel)) {
            return;
        }
        while (panel.getComponentCount() > 0) {
            Component last = panel.getComponent(panel.getComponentCount() - 1);
            if (!(last instanceof Box.Filler filler)
                    || filler.getMaximumSize().height < Short.MAX_VALUE) {
                return;
            }
            panel.remove(panel.getComponentCount() - 1);
        }
    }

    private static final class ConsumingConfigSectionContext implements ConfigSectionContext {
        private final ConfigSectionContext delegate;
        private final Set<String> renderedKeys;

        private ConsumingConfigSectionContext(ConfigSectionContext delegate, Set<String> renderedKeys) {
            this.delegate = delegate;
            this.renderedKeys = renderedKeys;
        }

        @Override
        public List<ConfigFieldSpec> allFields() {
            return delegate.allFields().stream()
                    .filter(field -> !renderedKeys.contains(field.key()))
                    .toList();
        }

        @Override
        public ConfigFieldSpec findSpec(String key) {
            return renderedKeys.contains(key) ? null : delegate.findSpec(key);
        }

        @Override
        public void registerField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf) {
            if (spec == null || !renderedKeys.add(spec.key())) {
                return;
            }
            delegate.registerField(spec, rf);
        }

        @Override
        public void addFields(JPanel content, List<ConfigFieldSpec> specs) {
            List<ConfigFieldSpec> remaining = specs == null ? List.of() : specs.stream()
                    .filter(spec -> spec != null)
                    .filter(spec -> !renderedKeys.contains(spec.key()))
                    .toList();
            delegate.addFields(content, remaining);
            remaining.forEach(spec -> renderedKeys.add(spec.key()));
        }

        @Override
        public String currentFieldValue(String key) {
            return delegate.currentFieldValue(key);
        }

        @Override
        public void setFieldValue(String key, String value) {
            delegate.setFieldValue(key, value);
        }

        @Override
        public void lockField(String key) {
            delegate.lockField(key);
        }

        @Override
        public JPanel newContentPanel() {
            return delegate.newContentPanel();
        }

        @Override
        public void resetScrollToTopOnFirstShow(JScrollPane sp) {
            delegate.resetScrollToTopOnFirstShow(sp);
        }

        @Override
        public JLabel effectLabel(boolean requiresRestart) {
            return delegate.effectLabel(requiresRestart);
        }

        @Override
        public JTextArea hiddenValidationError() {
            return delegate.hiddenValidationError();
        }

        @Override
        public void showNotice(String msg) {
            delegate.showNotice(msg);
        }

        @Override
        public void updateEnabledStates() {
            delegate.updateEnabledStates();
        }

        @Override
        public GuiConfigTestClient testClient() {
            return delegate.testClient();
        }
    }
}
