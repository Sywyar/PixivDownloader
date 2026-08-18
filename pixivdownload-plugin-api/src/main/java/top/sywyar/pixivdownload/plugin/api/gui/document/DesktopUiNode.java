package top.sywyar.pixivdownload.plugin.api.gui.document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-JDK declarative UI node vocabulary shared by desktop renderers.
 * Nodes carry bounded values and stable ids only; they never carry toolkit components or executable callbacks.
 */
public sealed interface DesktopUiNode permits DesktopUiNode.Container, DesktopUiNode.Group,
        DesktopUiNode.Tabs, DesktopUiNode.Scroll, DesktopUiNode.Split, DesktopUiNode.Text,
        DesktopUiNode.Image, DesktopUiNode.Separator, DesktopUiNode.Spacer, DesktopUiNode.Progress,
        DesktopUiNode.TextInput, DesktopUiNode.Toggle, DesktopUiNode.Choice,
        DesktopUiNode.NumberInput, DesktopUiNode.Table, DesktopUiNode.Tree,
        DesktopUiNode.Button, DesktopUiNode.Link {

    /** Stable node identity within one document. */
    String id();

    /** Node kind used for renderer capability negotiation. */
    Kind kind();

    /** Direct child nodes used by document validation and renderers. */
    default List<DesktopUiNode> childNodes() {
        return List.of();
    }

    /**
     * Validates global node-id uniqueness and bounded tree depth/size, returning all required renderer kinds.
     *
     * @param root declarative root node
     * @return immutable required node-kind set
     */
    static Set<Kind> validateTree(DesktopUiNode root) {
        Objects.requireNonNull(root, "root");
        Set<String> ids = new HashSet<>();
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        validateNode(root, ids, kinds, 0);
        return Set.copyOf(kinds);
    }

    /** General column, row, flow, or grid container. */
    record Container(String id, ContainerLayout layout, int columns, int gap,
                     Alignment alignment, List<DesktopUiNode> children) implements DesktopUiNode {
        public Container {
            id = requireId(id, "id");
            layout = Objects.requireNonNull(layout, "layout");
            alignment = alignment == null ? Alignment.START : alignment;
            children = copyBounded(children, "children");
            requireRange(gap, 0, 128, "gap");
            if (layout == ContainerLayout.GRID) requireRange(columns, 1, 64, "columns");
            else columns = 1;
        }

        @Override public Kind kind() { return Kind.CONTAINER; }
        @Override public List<DesktopUiNode> childNodes() { return children; }
    }

    /** Titled group container. */
    record Group(String id, TextToken title, DesktopUiNode content) implements DesktopUiNode {
        public Group {
            id = requireId(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }

        @Override public Kind kind() { return Kind.GROUP; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(content); }
    }

    /** Tabbed container. */
    record Tabs(String id, List<Tab> tabs) implements DesktopUiNode {
        public Tabs {
            id = requireId(id, "id");
            tabs = copyBounded(tabs, "tabs");
            if (tabs.isEmpty()) throw new IllegalArgumentException("tabs must not be empty");
            requireUnique(tabs.stream().map(Tab::id).toList(), "tab id");
        }

        @Override public Kind kind() { return Kind.TABS; }
        @Override public List<DesktopUiNode> childNodes() { return tabs.stream().map(Tab::content).toList(); }
    }

    /** Scrollable child container. */
    record Scroll(String id, DesktopUiNode content) implements DesktopUiNode {
        public Scroll {
            id = requireId(id, "id");
            content = Objects.requireNonNull(content, "content");
        }

        @Override public Kind kind() { return Kind.SCROLL; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(content); }
    }

    /** Two-pane split container. */
    record Split(String id, Axis axis, double resizeWeight,
                 DesktopUiNode first, DesktopUiNode second) implements DesktopUiNode {
        public Split {
            id = requireId(id, "id");
            axis = Objects.requireNonNull(axis, "axis");
            if (!Double.isFinite(resizeWeight) || resizeWeight < 0d || resizeWeight > 1d) {
                throw new IllegalArgumentException("resizeWeight must be between 0 and 1");
            }
            first = Objects.requireNonNull(first, "first");
            second = Objects.requireNonNull(second, "second");
        }

        @Override public Kind kind() { return Kind.SPLIT; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(first, second); }
    }

    /** Plain localized text. Raw HTML is intentionally unsupported. */
    record Text(String id, TextToken text, TextStyle style,
                boolean wrap, boolean selectable) implements DesktopUiNode {
        public Text {
            id = requireId(id, "id");
            text = Objects.requireNonNull(text, "text");
            style = style == null ? TextStyle.BODY : style;
        }

        @Override public Kind kind() { return Kind.TEXT; }
    }

    /** Bounded, materialized image with localized alternative text. */
    record Image(String id, ImageData image, TextToken altText,
                 int preferredWidth, int preferredHeight, ScaleMode scaleMode) implements DesktopUiNode {
        public Image {
            id = requireId(id, "id");
            image = Objects.requireNonNull(image, "image");
            altText = Objects.requireNonNull(altText, "altText");
            requireRange(preferredWidth, 1, 4096, "preferredWidth");
            requireRange(preferredHeight, 1, 4096, "preferredHeight");
            scaleMode = scaleMode == null ? ScaleMode.FIT : scaleMode;
        }

        @Override public Kind kind() { return Kind.IMAGE; }
    }

    /** Horizontal or vertical separator. */
    record Separator(String id, Axis axis) implements DesktopUiNode {
        public Separator {
            id = requireId(id, "id");
            axis = Objects.requireNonNull(axis, "axis");
        }

        @Override public Kind kind() { return Kind.SEPARATOR; }
    }

    /** Fixed logical spacer. */
    record Spacer(String id, int width, int height) implements DesktopUiNode {
        public Spacer {
            id = requireId(id, "id");
            requireRange(width, 0, 4096, "width");
            requireRange(height, 0, 4096, "height");
        }

        @Override public Kind kind() { return Kind.SPACER; }
    }

    /** Determinate or indeterminate progress display. */
    record Progress(String id, double progress, boolean indeterminate,
                    TextToken text) implements DesktopUiNode {
        public Progress {
            id = requireId(id, "id");
            if (!indeterminate && (!Double.isFinite(progress) || progress < 0d || progress > 1d)) {
                throw new IllegalArgumentException("progress must be between 0 and 1");
            }
        }

        @Override public Kind kind() { return Kind.PROGRESS; }
    }

    /** Text-like input including password, multiline, search, temporal, file, and directory variants. */
    record TextInput(String id, String bindingId, TextToken label, TextToken help,
                     InputKind inputKind, String value, int columns, int rows,
                     boolean enabled) implements DesktopUiNode {
        public TextInput {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            inputKind = inputKind == null ? InputKind.TEXT : inputKind;
            value = boundedText(value, "value");
            if (inputKind == InputKind.PASSWORD && !value.isEmpty()) {
                throw new IllegalArgumentException("password input must not carry an initial value");
            }
            requireRange(columns, 1, 512, "columns");
            requireRange(rows, 1, 100, "rows");
        }

        @Override public Kind kind() { return Kind.TEXT_INPUT; }
    }

    /** Boolean checkbox or switch-style toggle. */
    record Toggle(String id, String bindingId, TextToken label, TextToken help,
                  ToggleStyle toggleStyle, boolean selected, boolean enabled) implements DesktopUiNode {
        public Toggle {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            toggleStyle = toggleStyle == null ? ToggleStyle.CHECKBOX : toggleStyle;
        }

        @Override public Kind kind() { return Kind.TOGGLE; }
    }

    /** Combo box, radio group, checkbox group, or list selection. */
    record Choice(String id, String bindingId, TextToken label, TextToken help,
                  ChoiceStyle choiceStyle, SelectionMode selectionMode,
                  List<Option> options, List<String> selectedIds,
                  boolean enabled) implements DesktopUiNode {
        public Choice {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            choiceStyle = choiceStyle == null ? ChoiceStyle.COMBO_BOX : choiceStyle;
            selectionMode = selectionMode == null ? SelectionMode.SINGLE : selectionMode;
            options = copyBounded(options, "options");
            selectedIds = copyBoundedStrings(selectedIds, "selectedIds");
            Set<String> optionIds = new HashSet<>(options.stream().map(Option::id).toList());
            requireUnique(options.stream().map(Option::id).toList(), "option id");
            if (!optionIds.containsAll(selectedIds)) throw new IllegalArgumentException("selectedIds must reference options");
            if (selectionMode == SelectionMode.SINGLE && selectedIds.size() > 1) {
                throw new IllegalArgumentException("single selection accepts at most one selected id");
            }
            if ((choiceStyle == ChoiceStyle.COMBO_BOX || choiceStyle == ChoiceStyle.RADIO_BUTTONS)
                    && selectionMode != SelectionMode.SINGLE) {
                throw new IllegalArgumentException(choiceStyle + " requires single selection");
            }
            if (choiceStyle == ChoiceStyle.CHECK_BOXES && selectionMode != SelectionMode.MULTIPLE) {
                throw new IllegalArgumentException("CHECK_BOXES requires multiple selection");
            }
        }

        @Override public Kind kind() { return Kind.CHOICE; }
    }

    /** Integer spinner or slider input. */
    record NumberInput(String id, String bindingId, TextToken label, TextToken help,
                       NumberStyle numberStyle, int value, int minimum, int maximum,
                       int step, boolean enabled) implements DesktopUiNode {
        public NumberInput {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            numberStyle = numberStyle == null ? NumberStyle.SPINNER : numberStyle;
            if (minimum > maximum) throw new IllegalArgumentException("minimum must not exceed maximum");
            if (value < minimum || value > maximum) throw new IllegalArgumentException("value out of range");
            if (step <= 0) throw new IllegalArgumentException("step must be positive");
        }

        @Override public Kind kind() { return Kind.NUMBER_INPUT; }
    }

    /** Read-only tabular data with optional row selection. */
    record Table(String id, String bindingId, List<TableColumn> columns,
                 List<TableRow> rows, SelectionMode selectionMode,
                 List<String> selectedRowIds, boolean enabled) implements DesktopUiNode {
        public Table {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            columns = copyBounded(columns, "columns");
            rows = copyBounded(rows, "rows");
            selectionMode = selectionMode == null ? SelectionMode.SINGLE : selectionMode;
            selectedRowIds = copyBoundedStrings(selectedRowIds, "selectedRowIds");
            if (columns.isEmpty()) throw new IllegalArgumentException("columns must not be empty");
            requireUnique(columns.stream().map(TableColumn::id).toList(), "column id");
            requireUnique(rows.stream().map(TableRow::id).toList(), "row id");
            for (TableRow row : rows) {
                if (row.cells().size() != columns.size()) {
                    throw new IllegalArgumentException("row cell count must match columns");
                }
            }
            Set<String> rowIds = new HashSet<>(rows.stream().map(TableRow::id).toList());
            if (!rowIds.containsAll(selectedRowIds)) {
                throw new IllegalArgumentException("selectedRowIds must reference rows");
            }
            if (selectionMode == SelectionMode.SINGLE && selectedRowIds.size() > 1) {
                throw new IllegalArgumentException("single selection accepts at most one selected row");
            }
        }

        @Override public Kind kind() { return Kind.TABLE; }
    }

    /** Hierarchical data tree with stable item ids. */
    record Tree(String id, String bindingId, List<TreeItem> items,
                SelectionMode selectionMode, List<String> selectedIds,
                boolean enabled) implements DesktopUiNode {
        public Tree {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            items = copyBounded(items, "items");
            selectionMode = selectionMode == null ? SelectionMode.SINGLE : selectionMode;
            selectedIds = copyBoundedStrings(selectedIds, "selectedIds");
            List<String> ids = new ArrayList<>();
            collectTreeIds(items, ids, 0);
            requireUnique(ids, "tree item id");
            if (!new HashSet<>(ids).containsAll(selectedIds)) {
                throw new IllegalArgumentException("selectedIds must reference tree items");
            }
            if (selectionMode == SelectionMode.SINGLE && selectedIds.size() > 1) {
                throw new IllegalArgumentException("single selection accepts at most one selected item");
            }
        }

        @Override public Kind kind() { return Kind.TREE; }
    }

    /** Command button emitting an activation event. */
    record Button(String id, String actionId, TextToken label, TextToken help,
                  ButtonStyle buttonStyle, boolean enabled) implements DesktopUiNode {
        public Button {
            id = requireId(id, "id");
            actionId = requireId(actionId, "actionId");
            label = Objects.requireNonNull(label, "label");
            buttonStyle = buttonStyle == null ? ButtonStyle.NORMAL : buttonStyle;
        }

        @Override public Kind kind() { return Kind.BUTTON; }
    }

    /** Link-like command emitting an activation event without embedding an arbitrary URI. */
    record Link(String id, String actionId, TextToken label, TextToken help,
                boolean enabled) implements DesktopUiNode {
        public Link {
            id = requireId(id, "id");
            actionId = requireId(actionId, "actionId");
            label = Objects.requireNonNull(label, "label");
        }

        @Override public Kind kind() { return Kind.LINK; }
    }

    /** Localized text token; fallback is used when the namespace or key is unavailable. */
    record TextToken(String namespace, String key, String fallback, List<String> arguments) {
        public TextToken {
            namespace = blankToNull(boundedText(namespace, "namespace"));
            if (namespace != null) namespace = requireId(namespace, "namespace");
            key = boundedText(key, "key").trim();
            if (!key.isBlank()) key = requireId(key, "key");
            fallback = boundedText(fallback, "fallback");
            arguments = copyBoundedStrings(arguments, "arguments");
            if (key.isBlank() && fallback.isBlank()) {
                throw new IllegalArgumentException("text token requires key or fallback");
            }
        }

        public static TextToken key(String key) { return new TextToken(null, key, key, List.of()); }
        public static TextToken raw(String text) { return new TextToken(null, "", text, List.of()); }
    }

    /** Immutable materialized image bytes encoded as Base64. */
    record ImageData(String mediaType, String base64) {
        public ImageData {
            mediaType = mediaType == null ? "" : mediaType.trim().toLowerCase();
            if (!mediaType.startsWith("image/")) throw new IllegalArgumentException("mediaType must be image/*");
            base64 = base64 == null ? "" : base64.trim();
            int maximumEncodedLength = ((maxImageBytes() + 2) / 3) * 4 + 4;
            if (base64.length() > maximumEncodedLength) {
                throw new IllegalArgumentException("image data size out of range");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("base64 must contain valid image data", invalid);
            }
            if (decoded.length == 0 || decoded.length > maxImageBytes()) {
                throw new IllegalArgumentException("image data size out of range");
            }
        }

        public byte[] bytes() { return Base64.getDecoder().decode(base64); }
    }

    /** Tab descriptor. */
    record Tab(String id, TextToken title, DesktopUiNode content) {
        public Tab {
            id = requireId(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** Selectable option descriptor. */
    record Option(String id, TextToken label, boolean enabled) {
        public Option {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
        }
    }

    /** Table column descriptor. */
    record TableColumn(String id, TextToken label, int preferredWidth) {
        public TableColumn {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            requireRange(preferredWidth, 0, 4096, "preferredWidth");
        }
    }

    /** Table row descriptor. */
    record TableRow(String id, List<String> cells) {
        public TableRow {
            id = requireId(id, "id");
            cells = copyBoundedStrings(cells, "cells");
        }
    }

    /** Recursive tree item descriptor. */
    record TreeItem(String id, TextToken label, List<TreeItem> children) {
        public TreeItem {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            children = copyBounded(children, "children");
        }
    }

    /** Typed event value projected by a renderer. */
    record Value(ValueKind kind, List<String> values) {
        public Value {
            kind = Objects.requireNonNull(kind, "kind");
            values = copyBoundedStrings(values, "values");
            if (kind == ValueKind.NONE && !values.isEmpty()) throw new IllegalArgumentException("NONE value must be empty");
            if (kind != ValueKind.MULTI_SELECTION && values.size() > 1) {
                throw new IllegalArgumentException(kind + " accepts at most one value");
            }
            if (kind == ValueKind.BOOLEAN && !values.isEmpty()
                    && !values.get(0).equals("true") && !values.get(0).equals("false")) {
                throw new IllegalArgumentException("BOOLEAN value must be true or false");
            }
            if (kind == ValueKind.NUMBER && !values.isEmpty()) {
                try {
                    new BigDecimal(values.get(0));
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("NUMBER value must be numeric", invalid);
                }
            }
            if (kind == ValueKind.SELECTION || kind == ValueKind.MULTI_SELECTION) {
                values = values.stream().map(value -> requireId(value, "selection value")).toList();
                requireUnique(values, "selection value");
            }
        }

        public static Value empty() { return new Value(ValueKind.NONE, List.of()); }
        public static Value text(String value) { return new Value(ValueKind.TEXT, List.of(value == null ? "" : value)); }
        public static Value bool(boolean value) { return new Value(ValueKind.BOOLEAN, List.of(Boolean.toString(value))); }
        public static Value number(Number value) {
            return new Value(ValueKind.NUMBER, List.of(String.valueOf(Objects.requireNonNull(value, "value"))));
        }
        public static Value selection(String value) {
            return value == null ? new Value(ValueKind.SELECTION, List.of())
                    : new Value(ValueKind.SELECTION, List.of(value));
        }
        public static Value selections(List<String> values) { return new Value(ValueKind.MULTI_SELECTION, values); }
    }

    /**
     * Renderer-to-host event containing no executable plugin callback.
     * Hosts must not log or persist values emitted by {@link InputKind#PASSWORD} nodes.
     */
    record Event(EventType type, String nodeId, String targetId, Value value) {
        public Event {
            type = Objects.requireNonNull(type, "type");
            nodeId = requireId(nodeId, "nodeId");
            targetId = requireId(targetId, "targetId");
            value = value == null ? Value.empty() : value;
            if (type == EventType.ACTIVATE && value.kind() != ValueKind.NONE) {
                throw new IllegalArgumentException("ACTIVATE event must not carry a value");
            }
            if (type == EventType.SELECTION
                    && value.kind() != ValueKind.SELECTION && value.kind() != ValueKind.MULTI_SELECTION) {
                throw new IllegalArgumentException("SELECTION event requires a selection value");
            }
            if (type == EventType.CHANGE
                    && value.kind() != ValueKind.TEXT && value.kind() != ValueKind.BOOLEAN
                    && value.kind() != ValueKind.NUMBER) {
                throw new IllegalArgumentException("CHANGE event requires a text, boolean, or number value");
            }
        }
    }

    /** Supported node kinds. */
    enum Kind { CONTAINER, GROUP, TABS, SCROLL, SPLIT, TEXT, IMAGE, SEPARATOR, SPACER, PROGRESS,
        TEXT_INPUT, TOGGLE, CHOICE, NUMBER_INPUT, TABLE, TREE, BUTTON, LINK }
    /** General container layout. */
    enum ContainerLayout { COLUMN, ROW, FLOW, GRID }
    /** Logical child alignment. */
    enum Alignment { START, CENTER, END, STRETCH }
    /** Logical orientation. */
    enum Axis { HORIZONTAL, VERTICAL }
    /** Semantic text style. */
    enum TextStyle { BODY, CAPTION, TITLE, HEADING, CODE, SUCCESS, WARNING, ERROR }
    /** Image scaling policy. */
    enum ScaleMode { NONE, FIT, FILL }
    /** Text input semantic type. */
    enum InputKind { TEXT, PASSWORD, MULTILINE, SEARCH, DATE, TIME, DATE_TIME, FILE, DIRECTORY }
    /** Boolean input presentation. */
    enum ToggleStyle { CHECKBOX, SWITCH }
    /** Choice input presentation. */
    enum ChoiceStyle { COMBO_BOX, RADIO_BUTTONS, CHECK_BOXES, LIST }
    /** Single or multiple selection. */
    enum SelectionMode { SINGLE, MULTIPLE }
    /** Numeric input presentation. */
    enum NumberStyle { SPINNER, SLIDER }
    /** Command emphasis. */
    enum ButtonStyle { NORMAL, PRIMARY, DANGER }
    /** Stable event value kind. */
    enum ValueKind { NONE, TEXT, BOOLEAN, NUMBER, SELECTION, MULTI_SELECTION }
    /** Stable renderer event type. */
    enum EventType { CHANGE, SELECTION, ACTIVATE }

    private static String requireId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
        return value;
    }

    private static String boundedText(String value, String name) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > maxTextLength()) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " out of range");
    }

    private static <T> List<T> copyBounded(List<T> values, String name) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > maxCollectionSize()) throw new IllegalArgumentException(name + " is too large");
        return copy;
    }

    private static List<String> copyBoundedStrings(List<String> values, String name) {
        List<String> copy = copyBounded(values == null ? List.of() : values, name);
        return copy.stream().map(value -> boundedText(Objects.requireNonNull(value, name), name)).toList();
    }

    private static void requireUnique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("duplicate " + name);
    }

    private static void collectTreeIds(List<TreeItem> items, List<String> ids, int depth) {
        if (depth > 64 && !items.isEmpty()) throw new IllegalArgumentException("tree depth exceeds 64");
        for (TreeItem item : items) {
            ids.add(item.id());
            if (ids.size() > maxCollectionSize()) throw new IllegalArgumentException("tree is too large");
            collectTreeIds(item.children(), ids, depth + 1);
        }
    }

    private static void validateNode(DesktopUiNode node, Set<String> ids, Set<Kind> kinds, int depth) {
        if (depth > 64) throw new IllegalArgumentException("UI tree depth exceeds 64");
        if (!ids.add(node.id())) throw new IllegalArgumentException("duplicate node id: " + node.id());
        if (ids.size() > maxCollectionSize()) throw new IllegalArgumentException("UI tree is too large");
        kinds.add(node.kind());
        for (DesktopUiNode child : node.childNodes()) validateNode(child, ids, kinds, depth + 1);
    }

    private static int maxCollectionSize() { return 10_000; }
    private static int maxTextLength() { return 65_536; }
    private static int maxImageBytes() { return 5 * 1024 * 1024; }
}
