package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.render.SwingDesktopUiNodeRenderer;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.MediaTracker;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Generic Swing renderer for the host-owned declarative desktop document. */
public final class MainFrame extends JFrame {
    private static final Dimension DEFAULT_SIZE = new Dimension(960, 720);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 560);

    enum CloseBehavior { HIDE, EXIT }

    private final DesktopUiContext context;
    private final Timer documentTimer;
    private final KeyEventDispatcher shortcutDispatcher = this::dispatchShortcut;
    private final Map<String, Integer> shortcutIndexes = new HashMap<>();
    private JTabbedPane tabs;
    private Map<String, Integer> pageIndexes = Map.of();
    private Map<String, DesktopUiDocument.Page> pageDescriptors = Map.of();
    private final Map<String, JDialog> dialogs = new LinkedHashMap<>();
    private Map<String, DesktopUiDocument.Dialog> dialogDescriptors = Map.of();
    private long renderedRevision = Long.MIN_VALUE;
    private volatile boolean closeToTray;

    public MainFrame(DesktopUiContext context) {
        super(context.applicationName());
        this.context = Objects.requireNonNull(context, "context");
        setSize(defaultWindowSize());
        setMinimumSize(minimumWindowSize());
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (closeBehavior(closeToTray) == CloseBehavior.HIDE) setVisible(false);
                else context.requestApplicationExit();
            }
        });
        Image icon = loadAppIcon();
        if (icon != null) setIconImages(List.of(scaled(icon, 16), scaled(icon, 24), scaled(icon, 32),
                scaled(icon, 48), scaled(icon, 64)));
        refreshDocument();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcutDispatcher);
        documentTimer = new Timer(250, event -> {
            if (renderedRevision != context.currentSnapshot().revision()) refreshDocument();
        });
        documentTimer.start();
    }

    private void refreshDocument() {
        String selected = selectedPage();
        Map<String, ComponentState> state = captureState(tabs);
        DesktopUiSnapshot snapshot = context.currentSnapshot();
        long targetRevision = snapshot.revision();
        DesktopUiDocument document = snapshot.document();
        boolean forceRender = targetRevision != renderedRevision;
        Function<DesktopUiNode.TextToken, String> textResolver = context::resolveText;
        SwingDesktopUiNodeRenderer renderer = new SwingDesktopUiNodeRenderer(
                textResolver, event -> context.dispatchEvent(targetRevision, event));
        Map<String, Integer> nextIndexes = new LinkedHashMap<>();
        Map<String, DesktopUiDocument.Page> nextDescriptors = new LinkedHashMap<>();
        for (int index = 0; index < document.pages().size(); index++) {
            DesktopUiDocument.Page page = document.pages().get(index);
            nextIndexes.put(page.id(), index);
            nextDescriptors.put(page.id(), page);
        }
        boolean navigationChanged = tabs == null || tabs.getTabCount() != document.pages().size()
                || nextIndexes.entrySet().stream().anyMatch(entry ->
                !Objects.equals(pageIndexes.get(entry.getKey()), entry.getValue()));
        if (navigationChanged) {
            JTabbedPane nextTabs = new JTabbedPane();
            for (DesktopUiDocument.Page page : document.pages()) {
                nextTabs.addTab(textResolver.apply(page.title()), renderer.render(page.content()));
            }
            tabs = nextTabs;
            setContentPane(nextTabs);
            renderer.withoutEvents(() -> restoreState(nextTabs, state));
        } else {
            for (int index = 0; index < document.pages().size(); index++) {
                DesktopUiDocument.Page page = document.pages().get(index);
                tabs.setTitleAt(index, textResolver.apply(page.title()));
                if (forceRender || !page.equals(pageDescriptors.get(page.id()))) {
                    JComponent replacement = renderer.render(page.content());
                    tabs.setComponentAt(index, replacement);
                    renderer.withoutEvents(() -> restoreState(replacement, state));
                }
            }
        }
        pageIndexes = Map.copyOf(nextIndexes);
        pageDescriptors = Map.copyOf(nextDescriptors);
        setTitle(context.applicationName());
        if (selected != null && pageIndexes.containsKey(selected)) tabs.setSelectedIndex(pageIndexes.get(selected));
        syncDialogs(document, renderer, textResolver, forceRender);
        renderedRevision = targetRevision;
        String persistedTheme = context.themePreference();
        if (!persistedTheme.equals(GuiThemeManager.configuredThemeId())) GuiThemeManager.applyThemeId(persistedTheme);
        SystemTrayManager.refreshLocale();
        revalidate();
        repaint();
    }

    private void syncDialogs(DesktopUiDocument document, SwingDesktopUiNodeRenderer renderer,
                             Function<DesktopUiNode.TextToken, String> textResolver,
                             boolean forceTextRefresh) {
        Map<String, DesktopUiDocument.Dialog> previous = dialogDescriptors;
        Map<String, DesktopUiDocument.Dialog> next = new LinkedHashMap<>();
        for (DesktopUiDocument.Dialog descriptor : document.dialogs()) next.put(descriptor.id(), descriptor);
        dialogDescriptors = Map.copyOf(next);
        dialogs.entrySet().removeIf(entry -> {
            if (next.containsKey(entry.getKey())) return false;
            entry.getValue().dispose();
            return true;
        });
        for (DesktopUiDocument.Dialog descriptor : document.dialogs()) {
            JDialog dialog = dialogs.get(descriptor.id());
            if (dialog != null && !forceTextRefresh && descriptor.equals(previous.get(descriptor.id()))) continue;
            Map<String, ComponentState> state = captureState(dialog == null ? null : dialog.getContentPane());
            Point location = dialog != null && dialog.isShowing() ? dialog.getLocation() : null;
            if (dialog == null) {
                dialog = createDialog(descriptor.id());
                dialogs.put(descriptor.id(), dialog);
            }
            dialog.setTitle(textResolver.apply(descriptor.title()));
            dialog.setContentPane(renderer.render(descriptor.content()));
            dialog.pack();
            Dimension preferred = dialog.getSize();
            dialog.setSize(dialogSize(preferred, descriptor.preferredWidth(), descriptor.preferredHeight()));
            Container dialogContent = dialog.getContentPane();
            renderer.withoutEvents(() -> restoreState(dialogContent, state));
            if (location == null) dialog.setLocationRelativeTo(this);
            else dialog.setLocation(location);
            dialog.revalidate();
            dialog.repaint();
            JDialog rendered = dialog;
            if (!rendered.isShowing()) javax.swing.SwingUtilities.invokeLater(() -> {
                if (dialogs.get(descriptor.id()) == rendered) rendered.setVisible(true);
            });
        }
    }

    private JDialog createDialog(String id) {
        JDialog dialog = new JDialog(this, java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                DesktopUiDocument.Dialog descriptor = dialogDescriptors.get(id);
                if (descriptor != null && descriptor.dismissible()) context.dispatchEvent(renderedRevision,
                        new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id,
                                DesktopUiNode.Value.empty()));
            }
        });
        return dialog;
    }

    static Map<String, ComponentState> captureState(Component root) {
        Map<String, ComponentState> state = new LinkedHashMap<>();
        visit(root, component -> {
            if (!(component instanceof JComponent value)) return;
            Object property = value.getClientProperty(SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY);
            if (!(property instanceof String id)) return;
            Integer tab = value instanceof JTabbedPane tabs ? tabs.getSelectedIndex() : null;
            Point scroll = value instanceof JScrollPane pane ? pane.getViewport().getViewPosition() : null;
            Integer divider = value instanceof JSplitPane split ? split.getDividerLocation() : null;
            Integer caret = value instanceof JTextComponent text ? text.getCaretPosition() : null;
            char[] password = value instanceof JPasswordField field ? field.getPassword() : null;
            state.put(id, new ComponentState(tab, scroll, divider, caret, value.isFocusOwner(), password));
        });
        return state;
    }

    static void restoreState(Component root, Map<String, ComponentState> state) {
        try {
            visit(root, component -> {
                if (!(component instanceof JComponent value)) return;
                Object property = value.getClientProperty(SwingDesktopUiNodeRenderer.NODE_ID_PROPERTY);
                ComponentState saved = property instanceof String id ? state.get(id) : null;
                if (saved == null) return;
                if (value instanceof JTabbedPane tabs && saved.tab() != null
                        && saved.tab() >= 0 && saved.tab() < tabs.getTabCount()) tabs.setSelectedIndex(saved.tab());
                if (value instanceof JScrollPane pane && saved.scroll() != null) {
                    javax.swing.SwingUtilities.invokeLater(() -> pane.getViewport().setViewPosition(saved.scroll()));
                }
                if (value instanceof JSplitPane split && saved.divider() != null) split.setDividerLocation(saved.divider());
                if (value instanceof JPasswordField password && saved.password() != null) {
                    password.setText(new String(saved.password()));
                }
                if (value instanceof JTextComponent text && saved.caret() != null) {
                    text.setCaretPosition(Math.min(saved.caret(), text.getDocument().getLength()));
                }
                if (saved.focused()) javax.swing.SwingUtilities.invokeLater(value::requestFocusInWindow);
            });
        } finally {
            state.values().stream().map(ComponentState::password).filter(Objects::nonNull)
                    .forEach(password -> java.util.Arrays.fill(password, '\0'));
        }
    }

    private static void visit(Component root, java.util.function.Consumer<Component> consumer) {
        if (root == null) return;
        consumer.accept(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) visit(child, consumer);
        }
    }

    String resolveText(DesktopUiNode.TextToken token) {
        return context.resolveText(token);
    }

    static Dimension defaultWindowSize() { return new Dimension(DEFAULT_SIZE); }

    static Dimension minimumWindowSize() { return new Dimension(MINIMUM_SIZE); }

    static Dimension dialogSize(Dimension preferred, int preferredWidth, int preferredHeight) {
        Objects.requireNonNull(preferred, "preferred");
        return new Dimension(preferredWidth > 0 ? preferredWidth : preferred.width,
                preferredHeight > 0 ? preferredHeight : preferred.height);
    }

    static CloseBehavior closeBehavior(boolean closeToTray) {
        return closeToTray ? CloseBehavior.HIDE : CloseBehavior.EXIT;
    }

    private String selectedPage() {
        if (tabs == null || tabs.getSelectedIndex() < 0) return null;
        int selected = tabs.getSelectedIndex();
        return pageIndexes.entrySet().stream().filter(entry -> entry.getValue() == selected)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Enables close-to-tray only after the provider installed a usable tray icon. */
    public void setCloseToTray(boolean closeToTray) { this.closeToTray = closeToTray; }

    public void showWindow() {
        if (!isVisible()) setVisible(true);
        int state = getExtendedState();
        if ((state & Frame.ICONIFIED) != 0) setExtendedState(state & ~Frame.ICONIFIED);
        toFront();
        requestFocus();
    }

    @Override public void dispose() {
        documentTimer.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcutDispatcher);
        dialogs.values().forEach(JDialog::dispose);
        dialogs.clear();
        super.dispose();
    }

    record ComponentState(Integer tab, Point scroll, Integer divider, Integer caret,
                          boolean focused, char[] password) { }

    private boolean dispatchShortcut(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED) return false;
        String key = physicalKey(event.getKeyCode());
        if (key == null) return false;
        DesktopUiDocument.KeyStroke pressed = new DesktopUiDocument.KeyStroke(
                key, event.isAltDown(), event.isControlDown(), event.isShiftDown(), event.isMetaDown());
        boolean consume = false;
        DesktopUiSnapshot snapshot = context.currentSnapshot();
        for (DesktopUiDocument.KeyboardShortcut shortcut : snapshot.document().shortcuts()) {
            DesktopUiDocument.MatchResult match = shortcut.advance(
                    shortcutIndexes.getOrDefault(shortcut.id(), 0), pressed);
            if (match.completed()) {
                context.dispatchEvent(snapshot.revision(), new DesktopUiNode.Event(
                        DesktopUiNode.EventType.ACTIVATE, shortcut.id(), DesktopUiNode.Value.empty()));
                consume |= shortcut.consume();
            }
            shortcutIndexes.put(shortcut.id(), match.nextIndex());
        }
        return consume;
    }

    private static String physicalKey(int code) {
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return "Key" + (char) code;
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return "Digit" + (char) code;
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) return "F" + (code - KeyEvent.VK_F1 + 1);
        return switch (code) {
            case KeyEvent.VK_UP -> "ArrowUp";
            case KeyEvent.VK_DOWN -> "ArrowDown";
            case KeyEvent.VK_LEFT -> "ArrowLeft";
            case KeyEvent.VK_RIGHT -> "ArrowRight";
            case KeyEvent.VK_ENTER -> "Enter";
            case KeyEvent.VK_ESCAPE -> "Escape";
            case KeyEvent.VK_SPACE -> "Space";
            case KeyEvent.VK_TAB -> "Tab";
            case KeyEvent.VK_BACK_SPACE -> "Backspace";
            case KeyEvent.VK_DELETE -> "Delete";
            case KeyEvent.VK_INSERT -> "Insert";
            case KeyEvent.VK_HOME -> "Home";
            case KeyEvent.VK_END -> "End";
            case KeyEvent.VK_PAGE_UP -> "PageUp";
            case KeyEvent.VK_PAGE_DOWN -> "PageDown";
            default -> null;
        };
    }

    private static Image loadAppIcon() {
        try (var stream = MainFrame.class.getResourceAsStream("/static/favicon.ico")) {
            if (stream == null) return null;
            Image image = Toolkit.getDefaultToolkit().createImage(stream.readAllBytes());
            MediaTracker tracker = new MediaTracker(new Canvas());
            tracker.addImage(image, 0);
            tracker.waitForAll();
            return tracker.isErrorAny() ? null : image;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Image scaled(Image source, int size) {
        return source.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }
}
