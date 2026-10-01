package top.sywyar.pixivdownload.gui.panel;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/** 页面只纵向滚动，说明文字按分配宽度换行。 */
final class ScrollablePagePanel extends JPanel implements Scrollable {
    ScrollablePagePanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    static JLabel secondaryLabel(String text) {
        JLabel label = new JLabel() {
            @Override public void setText(String value) {
                super.setText(value == null || value.startsWith("<html>") ? value : "<html>"
                        + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
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
        };
        label.setText(text);
        label.setForeground(Color.GRAY);
        label.addComponentListener(new ComponentAdapter() {
            private int width;
            @Override public void componentResized(ComponentEvent event) {
                if (width != label.getWidth()) {
                    width = label.getWidth();
                    label.revalidate();
                }
            }
        });
        return label;
    }
}
