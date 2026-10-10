package top.sywyar.pixivdownload.gui.bootstrap;

import top.sywyar.pixivdownload.common.AppInfo;

import javax.accessibility.AccessibleContext;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 只绘制引导窗口；采用逻辑像素，设备变换交由 AWT 处理。 */
final class StartupSplashView extends Canvas {
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    private final BufferedImage icon;
    private final String fontFamily;
    private StartupAppearance appearance;
    private String status;
    private Layout layout;
    private BufferedImage buffer;
    private BufferedImage scaledIcon;
    private int frame;

    StartupSplashView(BufferedImage icon, StartupAppearance appearance, String status) {
        this.icon = icon;
        this.appearance = appearance;
        this.status = status;
        this.fontFamily = fontFamily(AppInfo.NAME + status, Locale.getDefault());
        updateAccessibleText();
    }

    void update(StartupAppearance appearance, String status, int frame) {
        this.appearance = appearance;
        this.status = status;
        this.frame = frame;
        updateAccessibleText();
    }

    Layout arrange(Dimension available) {
        layout = layout(available, appearance.textScale(), fontFamily, AppInfo.NAME, status);
        setBackground(new Color(StartupSplashColors.forAppearance(appearance).surface()));
        return layout;
    }

    @Override public Dimension getPreferredSize() {
        return layout == null ? new Dimension(440, 280) : new Dimension(layout.width(), layout.height());
    }

    @Override public void update(Graphics graphics) { paint(graphics); }

    @Override public void paint(Graphics graphics) {
        if (layout == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            double scaleX = Math.abs(g.getTransform().getScaleX());
            double scaleY = Math.abs(g.getTransform().getScaleY());
            int width = Math.max(1, (int) Math.ceil(layout.width() * scaleX));
            int height = Math.max(1, (int) Math.ceil(layout.height() * scaleY));
            if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
                buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }
            // AWT Canvas 没有默认双缓冲，完整绘制后一次提交，避免动画清背景时闪烁。
            Graphics2D buffered = buffer.createGraphics();
            try {
                buffered.scale(scaleX, scaleY);
                draw(buffered, layout, iconAtScale(scaleX, scaleY), appearance, frame);
            } finally { buffered.dispose(); }
            g.drawImage(buffer, 0, 0, layout.width(), layout.height(), null);
        } finally { g.dispose(); }
    }

    private BufferedImage iconAtScale(double scaleX, double scaleY) {
        if (icon == null || layout.iconSize() == 0) {
            scaledIcon = null;
            return null;
        }
        int width = Math.max(1, (int) Math.ceil(layout.iconSize() * scaleX));
        int height = Math.max(1, (int) Math.ceil(layout.iconSize() * scaleY));
        if (scaledIcon != null && scaledIcon.getWidth() == width && scaledIcon.getHeight() == height) {
            return scaledIcon;
        }
        // 大幅缩小时逐级滤除细碎纹理，预乘透明度避免透明像素的颜色污染边缘。
        BufferedImage current = icon;
        while (current.getWidth() != width || current.getHeight() != height) {
            int nextWidth = Math.max(width, current.getWidth() / 2);
            int nextHeight = Math.max(height, current.getHeight() / 2);
            var next = new BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D graphics = next.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(current, 0, 0, nextWidth, nextHeight, null);
            } finally { graphics.dispose(); }
            current = next;
        }
        scaledIcon = current;
        return scaledIcon;
    }

    private void updateAccessibleText() {
        AccessibleContext context = getAccessibleContext();
        context.setAccessibleName(AppInfo.NAME);
        context.setAccessibleDescription(status);
    }

    static Layout layout(Dimension available, double textScale, String family, String title, String status) {
        float scale = (float) StartupAppearance.validTextScale(textScale);
        int width = Math.max(64, Math.min(available.width, (int) Math.ceil(440 * Math.sqrt(scale))));
        int padding = Math.min(24, Math.max(4, width / 12));
        List<TextLayout> titles = lines(title, new Font(family, Font.BOLD, 22).deriveFont(22 * scale), width - 2 * padding);
        List<TextLayout> statuses = lines(status, new Font(family, Font.PLAIN, 14).deriveFont(14 * scale), width - 2 * padding - 28);
        int textHeight = (int) Math.ceil(height(titles) + height(statuses));
        int iconSize = Math.max(0, Math.min(104, Math.min(width - 2 * padding, available.height - textHeight - 104)));
        int titleTop = padding + iconSize + (iconSize > 0 ? 20 : 0);
        int statusTop = titleTop + (int) Math.ceil(height(titles)) + 22;
        int height = Math.max(Math.min(280, available.height), statusTop + (int) Math.ceil(height(statuses)) + padding);
        return new Layout(width, height, padding, iconSize, titleTop, statusTop, titles, statuses);
    }

    static void draw(Graphics2D g, Layout layout, BufferedImage icon, StartupAppearance appearance, int frame) {
        StartupSplashColors colors = StartupSplashColors.forAppearance(appearance);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(new Color(colors.surface()));
        g.fillRect(0, 0, layout.width(), layout.height());
        g.setColor(new Color(colors.border()));
        g.drawRoundRect(0, 0, layout.width() - 1, layout.height() - 1, 20, 20);
        if (icon != null && layout.iconSize() > 0) {
            g.drawImage(icon, (layout.width() - layout.iconSize()) / 2, layout.padding(),
                    layout.iconSize(), layout.iconSize(), null);
        }
        g.setColor(new Color(colors.text()));
        drawLines(g, layout.titles(), layout.width(), layout.titleTop(), 0);
        g.setColor(new Color(colors.secondaryText()));
        drawLines(g, layout.statuses(), layout.width(), layout.statusTop(), 28);
        float statusWidth = layout.statuses().stream().map(TextLayout::getAdvance).max(Float::compare).orElse(0f);
        float spinnerX = (layout.width() - statusWidth - 28) / 2 + 7;
        float spinnerY = layout.statusTop() + (layout.statuses().isEmpty() ? 8 : layout.statuses().get(0).getAscent() / 2);
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 0; index < 12; index++) {
            double angle = Math.toRadians(index * 30 - 90);
            int alpha = appearance.reducedMotion() ? 255 : 45 + Math.floorMod(index - frame, 12) * 19;
            g.setColor(new Color((alpha << 24) | colors.accent(), true));
            g.draw(new Line2D.Double(spinnerX + Math.cos(angle) * 4, spinnerY + Math.sin(angle) * 4,
                    spinnerX + Math.cos(angle) * 7, spinnerY + Math.sin(angle) * 7));
        }
    }

    private static void drawLines(Graphics2D graphics, List<TextLayout> lines, int width, int top, int leadingSpace) {
        float baseline = top;
        for (TextLayout line : lines) {
            baseline += line.getAscent();
            line.draw(graphics, (width - line.getAdvance() + leadingSpace) / 2, baseline);
            baseline += line.getDescent() + line.getLeading();
        }
    }

    private static List<TextLayout> lines(String text, Font font, int width) {
        if (text == null || text.isEmpty()) return List.of();
        var attributed = new AttributedString(text);
        attributed.addAttribute(TextAttribute.FONT, font);
        var iterator = attributed.getIterator();
        var measurer = new LineBreakMeasurer(iterator, FONT_CONTEXT);
        var result = new ArrayList<TextLayout>();
        while (measurer.getPosition() < iterator.getEndIndex()) result.add(measurer.nextLayout(Math.max(1, width)));
        return List.copyOf(result);
    }

    private static float height(List<TextLayout> lines) {
        float height = 0;
        for (TextLayout line : lines) height += line.getAscent() + line.getDescent() + line.getLeading();
        return height;
    }

    static String fontFamily(String text, Locale locale) {
        List<String> preferred = switch (locale.getLanguage()) {
            case "ko" -> List.of("Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans CJK KR", Font.DIALOG);
            case "ja" -> List.of("Yu Gothic UI", "Hiragino Sans", "Noto Sans CJK JP", Font.DIALOG);
            default -> List.of("Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", Font.DIALOG);
        };
        for (String name : preferred) {
            Font font = new Font(name, Font.PLAIN, 14);
            if ((name.equals(Font.DIALOG) || !font.getFamily().equals(Font.DIALOG)) && font.canDisplayUpTo(text) == -1) return name;
        }
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (new Font(name, Font.PLAIN, 14).canDisplayUpTo(text) == -1) return name;
        }
        return Font.DIALOG;
    }

    record Layout(int width, int height, int padding, int iconSize, int titleTop, int statusTop,
                  List<TextLayout> titles, List<TextLayout> statuses) { }
}
