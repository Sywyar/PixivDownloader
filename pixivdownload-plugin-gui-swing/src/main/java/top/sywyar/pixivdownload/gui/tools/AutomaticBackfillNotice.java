package top.sywyar.pixivdownload.gui.tools;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillTask;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Locale;
import java.util.UUID;

/** 主窗口拥有的自动回填提示；复用工具轮询，不创建线程或持久记录。 */
public final class AutomaticBackfillNotice implements AutoCloseable {
    private final Window owner;
    private final Runnable viewProgress;
    private UUID dismissed;
    private BackfillTask current;
    private BackfillTask rendered;
    private Locale renderedLocale;
    private JDialog dialog;
    private boolean closed;

    public AutomaticBackfillNotice(Window owner, Runnable viewProgress) {
        this.owner = owner;
        this.viewProgress = viewProgress;
    }

    public void refresh(BackfillTask task, boolean onboardingComplete) {
        if (closed) return;
        if (current != null && task != null && current.executionId().equals(task.executionId())
                && task.revision() < current.revision()) task = current;
        current = task;
        if (!eligible(task) || !onboardingComplete || !owner.isShowing() || modalVisible()) {
            hide();
            return;
        }
        if (dialog == null && !owner.isActive()) return;
        if (dialog != null && rendered != null && rendered.executionId().equals(task.executionId())
                && rendered.state() == task.state() && GuiMessages.currentLocale().equals(renderedLocale)) return;
        if (dialog == null) {
            dialog = new JDialog(owner, Dialog.ModalityType.MODELESS);
            dialog.setAutoRequestFocus(false);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { dismiss(); }
            });
            dialog.getRootPane().registerKeyboardAction(event -> dismiss(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        boolean opening = !dialog.isVisible();
        rendered = task;
        renderedLocale = GuiMessages.currentLocale();
        dialog.setTitle(GuiMessages.get("gui.tools.backfill.notice.title"));
        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(
                GuiMessages.get("gui.tools.backfill.notice.title"),
                UIManager.getIcon("OptionPane.informationIcon"),
                SwingConstants.LEFT
        );
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(12));
        JTextArea detail = new JTextArea(
                GuiMessages.get(task.active() ? "gui.tools.backfill.notice.body" : "gui.tools.backfill.notice.finished"),
                3,
                32
        );
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setEditable(false);
        detail.setFocusable(false);
        detail.setOpaque(false);
        detail.setFont(UIManager.getFont("Label.font"));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(detail);
        body.add(Box.createVerticalStrut(10));
        JLabel status = new JLabel(GuiMessages.get("gui.tools.backfill.state." + task.state().name().toLowerCase(Locale.ROOT)));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(status);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        content.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(0, 1, 0, 8));
        JButton view = new JButton(GuiMessages.get(task.active()
                ? "gui.tools.backfill.notice.view" : "gui.tools.backfill.notice.results"));
        UUID executionId = task.executionId();
        view.addActionListener(event -> { if (dismiss(executionId)) viewProgress.run(); });
        JButton dismiss = new JButton(GuiMessages.get(task.active()
                ? "gui.tools.backfill.notice.background" : "gui.tools.backfill.notice.dismiss"));
        dismiss.addActionListener(event -> dismiss(executionId));
        actions.add(view);
        actions.add(dismiss);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        if (opening) {
            dialog.pack();
            dialog.setSize(Math.min(dialog.getWidth(), owner.getWidth()), Math.min(dialog.getHeight(), owner.getHeight()));
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
        } else {
            dialog.revalidate();
            dialog.repaint();
        }
    }

    private boolean eligible(BackfillTask task) {
        return task != null && task.automatic() && !task.executionId().equals(dismissed)
                && ((task.total() != null && task.total() > 0) || task.state() == BackfillState.FAILED);
    }

    private boolean modalVisible() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog other && other != dialog && other.isModal() && other.isVisible()) return true;
        }
        return false;
    }

    private void dismiss() {
        if (current != null) dismiss(current.executionId());
    }

    private boolean dismiss(UUID executionId) {
        if (!eligible(current) || !current.executionId().equals(executionId)) return false;
        dismissed = executionId;
        hide();
        return true;
    }

    private void hide() {
        if (dialog != null) dialog.dispose();
        dialog = null;
        rendered = null;
        renderedLocale = null;
    }

    @Override public void close() {
        closed = true;
        hide();
        current = null;
        dismissed = null;
    }
}
