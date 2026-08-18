package top.sywyar.pixivdownload.gui.theme;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * Shared helpers for applying the current Swing theme to manually swapped components.
 */
public final class GuiThemeRefresh {

    private GuiThemeRefresh() {
    }

    public static void showCard(JPanel host, JComponent card) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(card, "card");
        if (!SwingUtilities.isEventDispatchThread()) {
            runOnEdt(host, card);
            return;
        }
        host.removeAll();
        SwingUtilities.updateComponentTreeUI(card);
        GuiInputStyleNormalizer.apply(card);
        host.add(card, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private static void runOnEdt(JPanel host, JComponent card) {
        try {
            SwingUtilities.invokeAndWait(() -> showCard(host, card));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while switching GUI card on the EDT", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to switch GUI card on the EDT", cause);
        }
    }
}
