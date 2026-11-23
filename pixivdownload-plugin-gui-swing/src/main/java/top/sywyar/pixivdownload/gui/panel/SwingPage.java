package top.sywyar.pixivdownload.gui.panel;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Supplier;

/**
 * 工具页与安全页共用的 Swing 版式：页面标题、分组标题、细分隔行与表单阅读宽度。
 * 字体和颜色在每次 LookAndFeel 更新时从 UIManager 重新读取，不保存某个主题的色值。
 */
final class SwingPage {
    /** 页面内容的最大宽度；更宽的窗口保留右侧留白，避免操作离说明过远。 */
    static final int PAGE_WIDTH = 1000;
    /** 普通表单的最大宽度。 */
    static final int FORM_WIDTH = 640;
    static final int ROW_PADDING = 14;

    private SwingPage() {
    }

    enum Style {
        TITLE(9f, Font.BOLD), SECTION(2f, Font.BOLD), STRONG(1f, Font.BOLD), BODY(0f, Font.PLAIN), SECONDARY(0f, Font.PLAIN);

        private final float delta;
        private final int weight;

        Style(float delta, int weight) {
            this.delta = delta;
            this.weight = weight;
        }
    }

    /** 可换行文字；HTML 渲染被关闭为纯文本转义，长译文按分配宽度增高。 */
    static final class Text extends JLabel {
        private final Style style;
        private Supplier<Color> color;

        Text(Style style, String text) {
            this.style = style;
            applyStyle();
            setText(text);
            addComponentListener(new ComponentAdapter() {
                private int width;
                @Override public void componentResized(ComponentEvent event) {
                    if (width != getWidth()) {
                        width = getWidth();
                        revalidate();
                    }
                }
            });
        }

        /** 使用语义颜色；颜色在主题切换后重新计算。 */
        Text color(Supplier<Color> color) {
            this.color = color;
            applyStyle();
            return this;
        }

        @Override public void updateUI() {
            super.updateUI();
            if (style != null) applyStyle();
        }

        private void applyStyle() {
            Font base = UIManager.getFont("Label.font");
            if (base != null) setFont(base.deriveFont(style.weight, base.getSize2D() + style.delta));
            setForeground(color != null ? color.get() : style == Style.SECONDARY ? secondaryColor() : UIManager.getColor("Label.foreground"));
        }

        @Override public void setText(String value) {
            super.setText(value == null || value.isEmpty() || value.startsWith("<html>") ? value
                    : "<html>" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br>") + "</html>");
        }

        @Override public Dimension getPreferredSize() {
            View view = (View) getClientProperty(BasicHTML.propertyKey);
            Insets insets = getInsets();
            if (view != null && getWidth() > insets.left + insets.right) {
                view.setSize(getWidth() - insets.left - insets.right, 0);
                return new Dimension(getWidth(), (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS))
                        + insets.top + insets.bottom);
            }
            return super.getPreferredSize();
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    static Text text(Style style, String value) {
        Text text = new Text(style, value);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        return text;
    }

    /** 次要文字：前景色向背景靠近，保持在深浅主题中都可辨认。 */
    static Color secondaryColor() {
        Color foreground = UIManager.getColor("Label.foreground");
        Color background = UIManager.getColor("Panel.background");
        if (foreground == null || background == null) return Color.GRAY;
        return mix(foreground, background, 0.62);
    }

    static Color separatorColor() {
        Color color = UIManager.getColor("Separator.foreground");
        if (color == null) color = UIManager.getColor("Component.borderColor");
        return color == null ? secondaryColor() : color;
    }

    static Color tint(Color accent) {
        Color background = UIManager.getColor("Panel.background");
        return background == null ? accent : mix(accent, background, 0.18);
    }

    static Color mix(Color first, Color second, double firstWeight) {
        double secondWeight = 1 - firstWeight;
        return new Color(
                (int) Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
                (int) Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
                (int) Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    static Color successColor() { return themed(new Color(0x1E, 0x8E, 0x3E), new Color(0x5C, 0xC9, 0x7B)); }
    static Color warningColor() { return themed(new Color(0xB2, 0x6A, 0x00), new Color(0xE8, 0xB3, 0x4B)); }
    static Color errorColor() { return themed(new Color(0xC6, 0x28, 0x28), new Color(0xF2, 0x7A, 0x7A)); }

    private static Color themed(Color light, Color dark) {
        Color background = UIManager.getColor("Panel.background");
        boolean darkTheme = background != null
                && (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114) / 1000 < 128;
        return darkTheme ? dark : light;
    }

    /** 页面滚动内容；每个直接子项左对齐并限制到页面宽度。 */
    static ScrollablePagePanel page() {
        ScrollablePagePanel content = new ScrollablePagePanel();
        content.setBorder(BorderFactory.createEmptyBorder(22, 32, 28, 32));
        return content;
    }

    static JScrollPane scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** 页面标题与简短说明。 */
    static JComponent header(String title, String subtitle, JComponent... extras) {
        Block header = new Block();
        header.add(text(Style.TITLE, title));
        if (subtitle != null && !subtitle.isBlank()) {
            header.add(Box.createVerticalStrut(6));
            header.add(text(Style.SECONDARY, subtitle));
        }
        for (JComponent extra : extras) {
            header.add(Box.createVerticalStrut(10));
            extra.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(extra);
        }
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 26, 0));
        return header;
    }

    /** 分组标题，可选说明和右侧操作；下方紧接该组的行。 */
    static JComponent section(String title, String description, JComponent... actions) {
        JPanel heading = new JPanel(new BorderLayout(16, 0));
        heading.setOpaque(false);
        Block texts = new Block();
        texts.add(text(Style.SECTION, title));
        if (description != null && !description.isBlank()) {
            texts.add(Box.createVerticalStrut(4));
            texts.add(text(Style.SECONDARY, description));
        }
        heading.add(texts, BorderLayout.CENTER);
        if (actions.length > 0) heading.add(actions(actions), BorderLayout.EAST);
        Block section = new Block();
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(heading);
        section.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return section;
    }

    /** 右对齐的一组按钮，按钮之间保持统一间距。 */
    static JPanel actions(JComponent... actions) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);
        for (JComponent action : actions) row.add(action);
        return row;
    }

    /** 左对齐的一组按钮或状态，用于行内次要操作。 */
    static JPanel inline(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent component : components) row.add(component);
        return row;
    }

    /**
     * 标准列表行：标题与说明在左，操作在右。窄窗口时说明换行，操作保持可见。
     */
    static Row row(String title, String description, JComponent... trailing) {
        Row row = new Row();
        row.texts.add(text(Style.STRONG, title));
        if (description != null && !description.isBlank()) {
            row.texts.add(Box.createVerticalStrut(4));
            row.texts.add(text(Style.SECONDARY, description));
        }
        if (trailing.length > 0) row.add(actions(trailing), BorderLayout.EAST);
        return row;
    }

    static final class Row extends JPanel {
        final Block texts = new Block();

        Row() {
            super(new BorderLayout(18, 0));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(ROW_PADDING, 0, ROW_PADDING, 0));
            add(texts, BorderLayout.CENTER);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** 纵向堆叠的透明容器，高度不被 BoxLayout 拉伸。 */
    static class Block extends JPanel {
        Block() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override public Component add(Component component) {
            if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
            return super.add(component);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(PAGE_WIDTH, getPreferredSize().height);
        }
    }

    /** 相邻行之间画细分隔线，不把每行做成独立卡片；空的或隐藏的子项不产生分隔线。 */
    static class RowList extends Block {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            graphics.setColor(separatorColor());
            boolean first = true;
            for (Component child : getComponents()) {
                if (!child.isVisible() || child.getHeight() == 0 || child instanceof Box.Filler) continue;
                if (!first) graphics.fillRect(child.getX(), child.getY(), child.getWidth(), 1);
                first = false;
            }
        }
    }

    /** 按标签列与输入列排布的表单，宽度不超过 {@link #FORM_WIDTH}。 */
    static final class Form extends JPanel {
        private int row;
        private final int maxWidth;

        Form() {
            this(FORM_WIDTH);
        }

        Form(int maxWidth) {
            super(new GridBagLayout());
            this.maxWidth = maxWidth;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        Form field(String label, JComponent input, JComponent... trailing) {
            JLabel caption = new JLabel(label);
            caption.setLabelFor(input);
            GridBagConstraints constraints = constraints(0);
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            boolean singleLine = input instanceof JTextField || input instanceof JComboBox || input instanceof JSpinner;
            constraints.anchor = singleLine ? GridBagConstraints.WEST : GridBagConstraints.NORTHWEST;
            constraints.insets = new Insets(input instanceof JScrollPane ? 9 : 5, 0, 5, 14);
            add(caption, constraints);
            JComponent value = input;
            if (trailing.length > 0) {
                JPanel combined = new JPanel(new BorderLayout(8, 0));
                combined.setOpaque(false);
                combined.add(input, BorderLayout.CENTER);
                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                buttons.setOpaque(false);
                buttons.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
                for (JComponent button : trailing) buttons.add(button);
                combined.add(buttons, BorderLayout.EAST);
                value = combined;
            }
            constraints = constraints(1);
            add(value, constraints);
            row++;
            return this;
        }

        /** 跨越输入列的说明或选项，与输入框左缘对齐。 */
        Form note(JComponent component) {
            GridBagConstraints constraints = constraints(1);
            add(component, constraints);
            row++;
            return this;
        }

        /** 跨越整行的内容。 */
        Form full(JComponent component) {
            GridBagConstraints constraints = constraints(0);
            constraints.gridwidth = 2;
            add(component, constraints);
            row++;
            return this;
        }

        private GridBagConstraints constraints(int column) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = column;
            constraints.gridy = row;
            constraints.weightx = column == 1 ? 1 : 0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(5, 0, 5, 0);
            return constraints;
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(maxWidth, getPreferredSize().height);
        }
    }

    /** 把内容限制在指定宽度并靠左放置；外层仍按页面宽度布局。 */
    static JComponent capped(JComponent content, int maxWidth) {
        JPanel wrapper = new JPanel(new LayoutManager() {
            @Override public void addLayoutComponent(String name, Component component) { }
            @Override public void removeLayoutComponent(Component component) { }
            @Override public Dimension preferredLayoutSize(Container parent) {
                Dimension size = content.isVisible() ? content.getPreferredSize() : new Dimension();
                Insets insets = parent.getInsets();
                return new Dimension(Math.min(size.width, maxWidth) + insets.left + insets.right,
                        size.height + insets.top + insets.bottom);
            }
            @Override public Dimension minimumLayoutSize(Container parent) {
                Dimension size = content.isVisible() ? content.getMinimumSize() : new Dimension();
                Insets insets = parent.getInsets();
                return new Dimension(Math.min(size.width, maxWidth) + insets.left + insets.right,
                        size.height + insets.top + insets.bottom);
            }
            @Override public void layoutContainer(Container parent) {
                Insets insets = parent.getInsets();
                int width = Math.min(maxWidth, parent.getWidth() - insets.left - insets.right);
                content.setBounds(insets.left, insets.top, Math.max(0, width),
                        Math.max(0, parent.getHeight() - insets.top - insets.bottom));
            }
        }) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(content);
        return wrapper;
    }

    /** 主要动作：沿用现有 Swing 页的粗体按钮表达主次，不引入额外配色。 */
    static JButton primary(JButton button) {
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.putClientProperty("swing.page.primary", Boolean.TRUE);
        return button;
    }

    /** 链接样式的文字按钮；颜色在主题切换后重新读取。 */
    static JButton link(String text) {
        JButton button = new JButton(text) {
            @Override public void updateUI() {
                super.updateUI();
                Color color = UIManager.getColor("Component.linkColor");
                setForeground(color == null ? UIManager.getColor("Label.foreground") : color);
            }
        };
        return plain(button);
    }

    /** 无边框的文字按钮，用于返回与展开等导航动作。 */
    static <T extends AbstractButton> T plain(T button) {
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setMargin(new Insets(2, PLAIN_INSET, 2, PLAIN_INSET));
        return button;
    }

    /** 无边框按钮的左右内边距；行内链接据此向左偏移，使文字与正文左缘对齐。 */
    private static final int PLAIN_INSET = 4;

    /** 行内链接按钮组：文字与上方正文左缘对齐，悬停背景仍保留内边距。 */
    static JPanel links(JComponent... buttons) {
        JPanel row = inline(buttons);
        row.setBorder(BorderFactory.createEmptyBorder(0, -8 - PLAIN_INSET - 1, 0, 0));
        return row;
    }

    /** 圆点状态图标，颜色在绘制时读取，跟随主题与状态变化。 */
    static final class Dot implements Icon {
        private Supplier<Color> color;

        Dot(Supplier<Color> color) {
            this.color = color;
        }

        void color(Supplier<Color> next) {
            this.color = next;
        }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color.get());
                int size = getIconWidth();
                g.fillOval(x, y + 1, size - 2, size - 2);
            } finally {
                g.dispose();
            }
        }

        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }
    }

    /** 在标签旁显示的小号状态标记，带浅色底以区分于正文。 */
    static final class Badge extends JLabel {
        private Supplier<Color> accent;

        Badge(String text, Supplier<Color> accent) {
            super(text);
            this.accent = accent;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            applyStyle();
        }

        void accent(Supplier<Color> next) {
            this.accent = next;
            applyStyle();
            repaint();
        }

        @Override public void updateUI() {
            super.updateUI();
            if (accent != null) applyStyle();
        }

        private void applyStyle() {
            Font base = UIManager.getFont("Label.font");
            if (base != null) setFont(base.deriveFont(Font.PLAIN, base.getSize2D() - 1f));
            setForeground(accent.get());
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(tint(accent.get()));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
