package top.sywyar.pixivdownload.plugin.api.gui.document;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete toolkit-neutral description of the desktop application's root pages.
 * Page order is the rendered navigation order; providers only render each page's node tree.
 *
 * @param pages ordered root pages
 * @param dialogs ordered open modal dialogs
 * @param shortcuts application keyboard shortcuts active for this document
 * @param tray optional system-tray structure
 */
public record DesktopUiDocument(List<Page> pages, List<Dialog> dialogs,
                                List<KeyboardShortcut> shortcuts, Optional<Tray> tray) {
    /**
     * Creates a document without open dialogs or keyboard shortcuts.
     *
     * @param pages ordered root pages
     */
    public DesktopUiDocument(List<Page> pages) {
        this(pages, List.of(), List.of(), Optional.empty());
    }

    /**
     * Creates a document without application keyboard shortcuts.
     *
     * @param pages ordered root pages
     * @param dialogs ordered open modal dialogs
     */
    public DesktopUiDocument(List<Page> pages, List<Dialog> dialogs) {
        this(pages, dialogs, List.of(), Optional.empty());
    }

    /**
     * Creates a document without a system tray.
     *
     * @param pages ordered root pages
     * @param dialogs ordered open modal dialogs
     * @param shortcuts application keyboard shortcuts active for this document
     */
    public DesktopUiDocument(List<Page> pages, List<Dialog> dialogs,
                             List<KeyboardShortcut> shortcuts) {
        this(pages, dialogs, shortcuts, Optional.empty());
    }

    /**
     * Validates and defensively copies the page tree.
     *
     * @param pages ordered root pages
     * @param dialogs ordered open modal dialogs
     * @param shortcuts application keyboard shortcuts active for this document
     * @param tray optional system-tray structure
     */
    public DesktopUiDocument {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        dialogs = List.copyOf(Objects.requireNonNull(dialogs, "dialogs"));
        shortcuts = List.copyOf(Objects.requireNonNull(shortcuts, "shortcuts"));
        tray = Objects.requireNonNull(tray, "tray");
        if (pages.isEmpty()) throw new IllegalArgumentException("pages must not be empty");
        var ids = new HashSet<String>();
        var nodeIds = new HashSet<String>();
        for (Page page : pages) {
            if (!ids.add(page.id())) throw new IllegalArgumentException("duplicate page id: " + page.id());
            DesktopUiNode.validateTree(page.content(), nodeIds);
        }
        for (Dialog dialog : dialogs) {
            if (!ids.add(dialog.id())) throw new IllegalArgumentException("duplicate document id: " + dialog.id());
            DesktopUiNode.validateTree(dialog.content(), nodeIds);
        }
        for (KeyboardShortcut shortcut : shortcuts) {
            if (!ids.add(shortcut.id())) throw new IllegalArgumentException("duplicate document id: " + shortcut.id());
        }
        tray.ifPresent(descriptor -> descriptor.items().forEach(item -> {
            if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate document id: " + item.id());
        }));
    }

    /**
     * Returns every node kind required to render this document.
     *
     * @return immutable required node-kind set
     */
    public Set<DesktopUiNode.Kind> requiredNodeKinds() {
        var kinds = new HashSet<DesktopUiNode.Kind>();
        for (Page page : pages) kinds.addAll(DesktopUiNode.validateTree(page.content()));
        for (Dialog dialog : dialogs) kinds.addAll(DesktopUiNode.validateTree(dialog.content()));
        return Set.copyOf(kinds);
    }

    /**
     * System-tray descriptor. Providers render the same menu order and dispatch ordinary
     * actions through the document event channel.
     *
     * @param tooltip localized tray tooltip
     * @param items ordered tray menu items
     */
    public record Tray(DesktopUiNode.TextToken tooltip, List<TrayItem> items) {
        /**
         * Validates and defensively copies the tray menu.
         *
         * @param tooltip localized tray tooltip
         * @param items ordered tray menu items
         */
        public Tray {
            tooltip = Objects.requireNonNull(tooltip, "tooltip");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            var ids = new HashSet<String>();
            for (TrayItem item : items) {
                if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate tray item id: " + item.id());
            }
        }
    }

    /**
     * One system-tray menu item.
     *
     * @param id stable item identity
     * @param label localized label; separators carry an unused placeholder token
     * @param role provider behavior for the item
     * @param actionId action target for {@link TrayItemRole#DISPATCH}, otherwise empty
     */
    public record TrayItem(String id, DesktopUiNode.TextToken label,
                           TrayItemRole role, String actionId) {
        /**
         * Creates an item that activates the provider's main window.
         *
         * @param id stable item identity
         * @param label localized label
         * @return activation menu item
         */
        public static TrayItem activate(String id, DesktopUiNode.TextToken label) {
            return new TrayItem(id, label, TrayItemRole.ACTIVATE_WINDOW, "");
        }

        /**
         * Creates an item that dispatches an application action.
         *
         * @param id stable item identity
         * @param label localized label
         * @param actionId application action target
         * @return dispatch menu item
         */
        public static TrayItem dispatch(String id, DesktopUiNode.TextToken label, String actionId) {
            return new TrayItem(id, label, TrayItemRole.DISPATCH, actionId);
        }

        /**
         * Creates a visual separator.
         *
         * @param id stable item identity
         * @return separator menu item
         */
        public static TrayItem separator(String id) {
            return new TrayItem(id, DesktopUiNode.TextToken.raw("-"), TrayItemRole.SEPARATOR, "");
        }

        /**
         * Validates the item behavior and action target.
         *
         * @param id stable item identity
         * @param label localized label
         * @param role provider behavior for the item
         * @param actionId application action target when dispatching
         */
        public TrayItem {
            requireStableId(id, "id");
            label = Objects.requireNonNull(label, "label");
            role = Objects.requireNonNull(role, "role");
            actionId = actionId == null ? "" : actionId;
            if (role == TrayItemRole.DISPATCH) requireStableId(actionId, "actionId");
            else if (!actionId.isEmpty()) throw new IllegalArgumentException("actionId is only valid for dispatch items");
        }
    }

    /** Provider behavior for a system-tray menu item. */
    public enum TrayItemRole {
        /** Restore and focus the provider's main window. */ ACTIVATE_WINDOW,
        /** Emit the item's action through {@code DesktopUiContext.dispatchEvent}. */ DISPATCH,
        /** Render a native menu separator. */ SEPARATOR
    }

    /**
     * Open modal dialog descriptor. Buttons remain ordinary nodes inside {@code content}.
     * Closing through the window decoration emits {@code dismissActionId} when dismissible.
     */
    public record Dialog(String id, DesktopUiNode.TextToken title, DialogStyle style,
                         DesktopUiNode content, String dismissActionId, boolean dismissible,
                         int preferredWidth, int preferredHeight) {
        /**
         * Validates one modal dialog descriptor.
         *
         * @param id stable dialog identity
         * @param title localized dialog title
         * @param style semantic dialog role
         * @param content complete dialog content tree
         * @param dismissActionId action emitted when the dialog is dismissed
         * @param dismissible whether window-decoration dismissal is allowed
         * @param preferredWidth preferred logical width, or zero for toolkit default
         * @param preferredHeight preferred logical height, or zero for toolkit default
         */
        public Dialog {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("id must be a stable id");
            }
            title = Objects.requireNonNull(title, "title");
            style = style == null ? DialogStyle.INFO : style;
            content = Objects.requireNonNull(content, "content");
            if (dismissActionId == null || !dismissActionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("dismissActionId must be a stable id");
            }
            if (preferredWidth < 0 || preferredWidth > 4096
                    || preferredHeight < 0 || preferredHeight > 4096) {
                throw new IllegalArgumentException("preferred dialog size out of range");
            }
        }
    }

    /** Semantic dialog role rendered with native toolkit affordances. */
    public enum DialogStyle {
        /** Informational dialog. */ INFO,
        /** Successful-operation dialog. */ SUCCESS,
        /** Warning dialog. */ WARNING,
        /** Error dialog. */ ERROR,
        /** Confirmation question dialog. */ QUESTION
    }

    /**
     * Document-wide keyboard sequence. Physical-key identifiers include {@code ArrowUp},
     * {@code KeyA}, {@code Digit1}, {@code Enter}, or {@code F1}.
     *
     * @param id stable shortcut identity
     * @param sequence ordered non-empty key sequence
     * @param actionId action target emitted after the complete sequence
     * @param consume whether the final matching key should be consumed
     */
    public record KeyboardShortcut(String id, List<KeyStroke> sequence,
                                   String actionId, boolean consume) {
        /**
         * Validates and defensively copies the shortcut.
         *
         * @param id stable shortcut identity
         * @param sequence ordered non-empty key sequence
         * @param actionId action target emitted after the complete sequence
         * @param consume whether the final matching key should be consumed
         */
        public KeyboardShortcut {
            requireStableId(id, "id");
            sequence = List.copyOf(Objects.requireNonNull(sequence, "sequence"));
            if (sequence.isEmpty() || sequence.size() > 64) {
                throw new IllegalArgumentException("shortcut sequence size must be between 1 and 64");
            }
            requireStableId(actionId, "actionId");
        }

        /**
         * Advances this sequence, restarting from its first key after a mismatch.
         *
         * @param currentIndex next sequence index expected before this key
         * @param pressed physical key that was pressed
         * @return next matching state
         */
        public MatchResult advance(int currentIndex, KeyStroke pressed) {
            Objects.requireNonNull(pressed, "pressed");
            int index = Math.max(0, Math.min(currentIndex, sequence.size() - 1));
            if (!pressed.equals(sequence.get(index))) {
                return new MatchResult(pressed.equals(sequence.get(0)) ? 1 : 0, false);
            }
            int next = index + 1;
            return next == sequence.size() ? new MatchResult(0, true) : new MatchResult(next, false);
        }
    }

    /** Result of advancing one keyboard shortcut sequence. */
    public record MatchResult(int nextIndex, boolean completed) {
        /**
         * Validates the next sequence index.
         *
         * @param nextIndex next sequence index to match
         * @param completed whether the sequence completed on this key
         */
        public MatchResult {
            if (nextIndex < 0) throw new IllegalArgumentException("nextIndex must not be negative");
        }
    }

    /**
     * Toolkit-neutral physical key and modifier state.
     *
     * @param key physical-key identifier
     * @param alt whether Alt is pressed
     * @param control whether Control is pressed
     * @param shift whether Shift is pressed
     * @param meta whether the platform Meta key is pressed
     */
    public record KeyStroke(String key, boolean alt, boolean control, boolean shift, boolean meta) {
        /**
         * Creates an unmodified physical key.
         *
         * @param key physical-key identifier
         * @return unmodified key stroke
         */
        public static KeyStroke key(String key) { return new KeyStroke(key, false, false, false, false); }

        /**
         * Validates the physical-key identifier.
         *
         * @param key physical-key identifier
         * @param alt whether Alt is pressed
         * @param control whether Control is pressed
         * @param shift whether Shift is pressed
         * @param meta whether the platform Meta key is pressed
         */
        public KeyStroke {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]{0,31}")) {
                throw new IllegalArgumentException("key must be a physical-key identifier");
            }
        }
    }

    /**
     * Root page descriptor.
     *
     * @param id stable page identity used only for navigation state
     * @param title localized title token
     * @param content complete page content tree
     */
    public record Page(String id, DesktopUiNode.TextToken title, DesktopUiNode content) {
        /**
         * Validates the page descriptor.
         *
         * @param id stable page identity
         * @param title localized title token
         * @param content complete page content tree
         */
        public Page {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("id must be a stable id");
            }
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static void requireStableId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
    }
}
