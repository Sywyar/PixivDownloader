package top.sywyar.pixivdownload.plugin.api.gui.document;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;

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
public sealed interface DesktopUiNode permits DesktopUiNode.Container, DesktopUiNode.AdaptiveGrid,
        DesktopUiNode.PagedRow, DesktopUiNode.Dock,
        DesktopUiNode.Surface, DesktopUiNode.Group, DesktopUiNode.Form, DesktopUiNode.Tabs, DesktopUiNode.Scroll,
        DesktopUiNode.Split, DesktopUiNode.Text, DesktopUiNode.Icon,
        DesktopUiNode.Image, DesktopUiNode.Separator, DesktopUiNode.Spacer, DesktopUiNode.Progress,
        DesktopUiNode.TextInput, DesktopUiNode.Toggle, DesktopUiNode.Choice,
        DesktopUiNode.NumberInput, DesktopUiNode.Table, DesktopUiNode.Tree,
        DesktopUiNode.Button, DesktopUiNode.Link {

    /** @return stable node identity within one document */
    String id();

    /** @return node kind used for renderer capability negotiation */
    Kind kind();

    /** @return direct child nodes used by document validation and renderers */
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
        Set<String> ids = new HashSet<>();
        return validateTree(root, ids);
    }

    /**
     * Validates one tree against document-wide ids and returns all required renderer kinds.
     *
     * @param root declarative root node
     * @param documentIds mutable document-wide id set
     * @return immutable required node-kind set
     */
    static Set<Kind> validateTree(DesktopUiNode root, Set<String> documentIds) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(documentIds, "documentIds");
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        validateNode(root, documentIds, kinds, 0);
        return Set.copyOf(kinds);
    }

    /**
     * @param root declarative root node
     * @return semantic renderer capabilities required by this node tree
     */
    static Set<DesktopUiCapability> requiredCapabilities(DesktopUiNode root) {
        Objects.requireNonNull(root, "root");
        EnumSet<DesktopUiCapability> capabilities = EnumSet.noneOf(DesktopUiCapability.class);
        collectCapabilities(root, capabilities);
        return Set.copyOf(capabilities);
    }

    /**
     * 返回当前节点自身要求的语义能力，不包含子节点。
     *
     * @param node 当前声明式节点
     * @return 不可变的直接能力集合
     */
    static Set<DesktopUiCapability> directRequiredCapabilities(DesktopUiNode node) {
        Objects.requireNonNull(node, "node");
        EnumSet<DesktopUiCapability> capabilities = EnumSet.noneOf(DesktopUiCapability.class);
        collectDirectCapabilities(node, capabilities);
        return Set.copyOf(capabilities);
    }

    /** General column, row, flow, or grid container. */
    record Container(String id, ContainerLayout layout, int columns, int gap,
                     Alignment alignment, List<DesktopUiNode> children) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param layout container layout
         * @param columns grid column count
         * @param gap logical child gap
         * @param alignment logical child alignment
         * @param children ordered child nodes
         */
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

    /** 按当前可用宽度调整列数的通用网格。 */
    record AdaptiveGrid(String id, int minimumColumnWidth, int maximumColumns,
                        int horizontalGap, int verticalGap,
                        List<DesktopUiNode> children) implements DesktopUiNode {
        public AdaptiveGrid {
            id = requireId(id, "id");
            requireRange(minimumColumnWidth, 80, 2048, "minimumColumnWidth");
            requireRange(maximumColumns, 1, 12, "maximumColumns");
            requireRange(horizontalGap, 0, 128, "horizontalGap");
            requireRange(verticalGap, 0, 128, "verticalGap");
            children = copyBounded(children, "children");
        }

        @Override public Kind kind() { return Kind.ADAPTIVE_GRID; }
        @Override public List<DesktopUiNode> childNodes() { return children; }
    }

    /** 每页固定显示四项并支持吸附翻页的横向区域。 */
    record PagedRow(String id, int itemsPerPage, int gap,
                    List<DesktopUiNode> children) implements DesktopUiNode {
        public static final int FIXED_ITEMS_PER_PAGE = 4;

        public PagedRow {
            id = requireId(id, "id");
            if (itemsPerPage != FIXED_ITEMS_PER_PAGE) {
                throw new IllegalArgumentException("itemsPerPage must be 4");
            }
            requireRange(gap, 0, 128, "gap");
            children = copyBounded(children, "children");
        }

        @Override public Kind kind() { return Kind.PAGED_ROW; }
        @Override public List<DesktopUiNode> childNodes() { return children; }
    }

    /** Border-layout style container with fixed edges and a growing center. */
    record Dock(String id, int gap, DesktopUiNode top, DesktopUiNode center,
                DesktopUiNode bottom, DesktopUiNode start, DesktopUiNode end) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param gap logical child gap
         * @param top optional top child
         * @param center optional growing center child
         * @param bottom optional bottom child
         * @param start optional leading child
         * @param end optional trailing child
         */
        public Dock {
            id = requireId(id, "id");
            requireRange(gap, 0, 128, "gap");
            if (top == null && center == null && bottom == null && start == null && end == null) {
                throw new IllegalArgumentException("dock requires at least one child");
            }
        }

        @Override public Kind kind() { return Kind.DOCK; }
        @Override public List<DesktopUiNode> childNodes() {
            return java.util.stream.Stream.of(top, center, bottom, start, end)
                    .filter(Objects::nonNull).toList();
        }
    }

    /** 受控词表中的主题感知图标。 */
    record Icon(String id, DesktopUiIcon icon, DesktopUiTone tone,
                TextToken accessibleLabel) implements DesktopUiNode {
        public Icon {
            id = requireId(id, "id");
            icon = Objects.requireNonNull(icon, "icon");
            tone = Objects.requireNonNull(tone, "tone");
            accessibleLabel = Objects.requireNonNull(accessibleLabel, "accessibleLabel");
        }

        @Override public Kind kind() { return Kind.ICON; }
    }

    /** Semantic visual surface with toolkit-neutral logical padding. */
    record Surface(String id, SurfaceStyle style, Insets padding,
                   boolean fillWidth, boolean fillHeight, DesktopUiNode content) implements DesktopUiNode {
        /**
         * Creates a width-aware surface that does not fill available height.
         *
         * @param id stable node id
         * @param style semantic surface style
         * @param padding logical padding
         * @param fillWidth whether to fill available width
         * @param content surface content
         */
        public Surface(String id, SurfaceStyle style, Insets padding,
                       boolean fillWidth, DesktopUiNode content) {
            this(id, style, padding, fillWidth, false, content);
        }

        /**
         * @param id stable node id
         * @param style semantic surface style
         * @param padding logical padding
         * @param fillWidth whether to fill available width
         * @param fillHeight whether to fill available height
         * @param content surface content
         */
        public Surface {
            id = requireId(id, "id");
            style = style == null ? SurfaceStyle.PLAIN : style;
            padding = padding == null ? Insets.NONE : padding;
            content = Objects.requireNonNull(content, "content");
        }

        @Override public Kind kind() { return Kind.SURFACE; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(content); }
    }

    /** Titled group container. */
    record Group(String id, TextToken title, DesktopUiNode content) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param title localized group title
         * @param content group content
         */
        public Group {
            id = requireId(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }

        @Override public Kind kind() { return Kind.GROUP; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(content); }
    }

    /**
     * Aligned form rows. The row owns the visible label and help text, so renderers render row content as the
     * control itself rather than repeating the control node's own label.
     */
    record Form(String id, FormStyle formStyle, TextToken labelSuffix,
                List<FormRow> rows) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param formStyle label sizing and density policy
         * @param labelSuffix optional localized label suffix
         * @param rows ordered form rows
         */
        public Form {
            id = requireId(id, "id");
            formStyle = formStyle == null ? FormStyle.RESPONSIVE : formStyle;
            rows = copyBounded(rows, "rows");
            if (rows.isEmpty()) throw new IllegalArgumentException("form rows must not be empty");
            requireUnique(rows.stream().map(FormRow::id).toList(), "form row id");
        }

        @Override public Kind kind() { return Kind.FORM; }
        @Override public List<DesktopUiNode> childNodes() {
            return rows.stream().flatMap(row -> java.util.stream.Stream.of(row.content(), row.trailing()))
                    .filter(Objects::nonNull).toList();
        }
    }

    /** Tabbed container. */
    record Tabs(String id, List<Tab> tabs) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param tabs ordered tab descriptors
         */
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
        /**
         * @param id stable node id
         * @param content scrollable content
         */
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
        /**
         * @param id stable node id
         * @param axis split orientation
         * @param resizeWeight proportion of extra space assigned to the first child
         * @param first first child
         * @param second second child
         */
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
                boolean wrap, boolean selectable, TextAlignment textAlignment) implements DesktopUiNode {
        /**
         * Creates start-aligned text.
         *
         * @param id stable node id
         * @param text localized text
         * @param style semantic text style
         * @param wrap whether text may wrap
         * @param selectable whether text may be selected
         */
        public Text(String id, TextToken text, TextStyle style, boolean wrap, boolean selectable) {
            this(id, text, style, wrap, selectable, TextAlignment.START);
        }

        /**
         * @param id stable node id
         * @param text localized text
         * @param style semantic text style
         * @param wrap whether text may wrap
         * @param selectable whether text may be selected
         * @param textAlignment logical text alignment
         */
        public Text {
            id = requireId(id, "id");
            text = Objects.requireNonNull(text, "text");
            style = style == null ? TextStyle.BODY : style;
            textAlignment = textAlignment == null ? TextAlignment.START : textAlignment;
        }

        @Override public Kind kind() { return Kind.TEXT; }
    }

    /** Bounded, materialized image with localized alternative text. */
    record Image(String id, ImageData image, TextToken altText,
                 int preferredWidth, int preferredHeight, ScaleMode scaleMode) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param image materialized image data
         * @param altText localized alternative text
         * @param preferredWidth preferred logical width
         * @param preferredHeight preferred logical height
         * @param scaleMode image scaling policy
         */
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
        /**
         * @param id stable node id
         * @param axis separator orientation
         */
        public Separator {
            id = requireId(id, "id");
            axis = Objects.requireNonNull(axis, "axis");
        }

        @Override public Kind kind() { return Kind.SEPARATOR; }
    }

    /** Fixed logical spacer. */
    record Spacer(String id, int width, int height) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param width logical width
         * @param height logical height
         */
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
        /**
         * @param id stable node id
         * @param progress determinate progress from zero to one
         * @param indeterminate whether progress is indeterminate
         * @param text optional localized progress text
         */
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
                     boolean enabled, long stateRevision) implements DesktopUiNode {
        /**
         * Creates a text-like input at the initial state revision.
         *
         * @param id stable node id
         * @param bindingId stable change-event target
         * @param label localized label
         * @param help optional localized help text
         * @param inputKind text input semantic type
         * @param value initial non-password value
         * @param columns preferred column count
         * @param rows preferred row count
         * @param enabled whether the control is enabled
         */
        public TextInput(String id, String bindingId, TextToken label, TextToken help,
                         InputKind inputKind, String value, int columns, int rows,
                         boolean enabled) {
            this(id, bindingId, label, help, inputKind, value, columns, rows, enabled, 0L);
        }

        /**
         * @param id stable node id
         * @param bindingId stable change-event target
         * @param label localized label
         * @param help optional localized help text
         * @param inputKind text input semantic type
         * @param value initial non-password value
         * @param columns preferred column count
         * @param rows preferred row count
         * @param enabled whether the control is enabled
         * @param stateRevision monotonic external state revision
         */
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
            if (stateRevision < 0) throw new IllegalArgumentException("stateRevision must not be negative");
        }

        @Override public Kind kind() { return Kind.TEXT_INPUT; }
    }

    /** Boolean checkbox or switch-style toggle. */
    record Toggle(String id, String bindingId, TextToken label, TextToken help,
                  ToggleStyle toggleStyle, boolean selected, boolean enabled) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param bindingId stable change-event target
         * @param label localized label
         * @param help optional localized help text
         * @param toggleStyle toggle presentation
         * @param selected current selected state
         * @param enabled whether the control is enabled
         */
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
        /**
         * @param id stable node id
         * @param bindingId stable selection-event target
         * @param label localized label
         * @param help optional localized help text
         * @param choiceStyle choice presentation
         * @param selectionMode single or multiple selection
         * @param options ordered selectable options
         * @param selectedIds selected option ids
         * @param enabled whether the control is enabled
         */
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
        /**
         * @param id stable node id
         * @param bindingId stable change-event target
         * @param label localized label
         * @param help optional localized help text
         * @param numberStyle numeric input presentation
         * @param value current value
         * @param minimum minimum accepted value
         * @param maximum maximum accepted value
         * @param step positive increment
         * @param enabled whether the control is enabled
         */
        public NumberInput {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            numberStyle = numberStyle == null ? NumberStyle.SPINNER : numberStyle;
            if (minimum > maximum) throw new IllegalArgumentException("minimum must not exceed maximum");
            if (value < minimum || value > maximum) throw new IllegalArgumentException("value out of range");
            if (step <= 0) throw new IllegalArgumentException("step must be positive");
            if (Math.floorMod((long) value - minimum, (long) step) != 0) {
                throw new IllegalArgumentException("value must align with minimum and step");
            }
        }

        @Override public Kind kind() { return Kind.NUMBER_INPUT; }
    }

    /** Read-only tabular data with optional row selection. */
    record Table(String id, String bindingId, List<TableColumn> columns,
                 List<TableRow> rows, SelectionMode selectionMode,
                 List<String> selectedRowIds, boolean enabled) implements DesktopUiNode {
        /**
         * @param id stable node id
         * @param bindingId stable selection-event target
         * @param columns ordered table columns
         * @param rows ordered table rows
         * @param selectionMode single or multiple row selection
         * @param selectedRowIds selected row ids
         * @param enabled whether selection is enabled
         */
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
        /**
         * @param id stable node id
         * @param bindingId stable selection-event target
         * @param items ordered root items
         * @param selectionMode single or multiple item selection
         * @param selectedIds selected item ids
         * @param enabled whether selection is enabled
         */
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
        /**
         * @param id stable node id
         * @param actionId stable activation-event target
         * @param label localized button label
         * @param help optional localized help text
         * @param buttonStyle command emphasis
         * @param enabled whether the button is enabled
         */
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
        /**
         * @param id stable node id
         * @param actionId stable activation-event target
         * @param label localized link label
         * @param help optional localized help text
         * @param enabled whether the link is enabled
         */
        public Link {
            id = requireId(id, "id");
            actionId = requireId(actionId, "actionId");
            label = Objects.requireNonNull(label, "label");
        }

        @Override public Kind kind() { return Kind.LINK; }
    }

    /** Localized text token; fallback is used when the namespace or key is unavailable. */
    record TextToken(String namespace, String key, String fallback, List<String> arguments) {
        /**
         * @param namespace optional plugin i18n namespace
         * @param key stable message key, or empty for raw fallback text
         * @param fallback fallback text
         * @param arguments message-format arguments
         */
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

        /**
         * @param key stable host message key
         * @return host message token using the key as fallback
         */
        public static TextToken key(String key) { return new TextToken(null, key, key, List.of()); }
        /**
         * @param text raw fallback text
         * @return text token without a message key
         */
        public static TextToken raw(String text) { return new TextToken(null, "", text, List.of()); }
    }

    /** Immutable materialized image bytes encoded as Base64. */
    record ImageData(String mediaType, String base64) {
        /**
         * @param mediaType image media type
         * @param base64 bounded Base64 image bytes
         */
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

        /** @return decoded image bytes */
        public byte[] bytes() { return Base64.getDecoder().decode(base64); }
    }

    /** Tab descriptor. */
    record Tab(String id, TextToken title, DesktopUiNode content) {
        /**
         * @param id stable tab id
         * @param title localized tab title
         * @param content complete tab content
         */
        public Tab {
            id = requireId(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** One aligned form row; trailing content is typically an effect badge or secondary action. */
    record FormRow(String id, TextToken label, TextToken help,
                   DesktopUiNode content, DesktopUiNode trailing) {
        /**
         * @param id stable row id
         * @param label localized row label
         * @param help optional localized help text
         * @param content row control or value content
         * @param trailing optional trailing content
         */
        public FormRow {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** Selectable option descriptor. */
    record Option(String id, TextToken label, boolean enabled) {
        /**
         * @param id stable option id
         * @param label localized option label
         * @param enabled whether the option is enabled
         */
        public Option {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
        }
    }

    /** Table column descriptor. */
    record TableColumn(String id, TextToken label, int preferredWidth) {
        /**
         * @param id stable column id
         * @param label localized column label
         * @param preferredWidth preferred logical width, or zero for toolkit default
         */
        public TableColumn {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            requireRange(preferredWidth, 0, 4096, "preferredWidth");
        }
    }

    /** Table row descriptor. */
    record TableRow(String id, List<String> cells) {
        /**
         * @param id stable row id
         * @param cells ordered plain-text cells
         */
        public TableRow {
            id = requireId(id, "id");
            cells = copyBoundedStrings(cells, "cells");
        }
    }

    /** Recursive tree item descriptor. */
    record TreeItem(String id, TextToken label, List<TreeItem> children) {
        /**
         * @param id stable item id
         * @param label localized item label
         * @param children ordered child items
         */
        public TreeItem {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            children = copyBounded(children, "children");
        }
    }

    /** Logical top/end/bottom/start padding shared by every renderer. */
    record Insets(int top, int end, int bottom, int start) {
        /** Zero padding. */
        public static final Insets NONE = new Insets(0, 0, 0, 0);

        /**
         * @param top logical top padding
         * @param end logical trailing padding
         * @param bottom logical bottom padding
         * @param start logical leading padding
         */
        public Insets {
            requireRange(top, 0, 512, "top");
            requireRange(end, 0, 512, "end");
            requireRange(bottom, 0, 512, "bottom");
            requireRange(start, 0, 512, "start");
        }

        /**
         * @param value padding for every edge
         * @return equal padding on every edge
         */
        public static Insets all(int value) { return new Insets(value, value, value, value); }
    }

    /** Typed event value projected by a renderer. */
    record Value(ValueKind kind, List<String> values) {
        /**
         * @param kind stable value kind
         * @param values bounded serialized values
         */
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

        /** @return empty event value */
        public static Value empty() { return new Value(ValueKind.NONE, List.of()); }
        /**
         * @param value text value
         * @return text event value
         */
        public static Value text(String value) { return new Value(ValueKind.TEXT, List.of(value == null ? "" : value)); }
        /**
         * @param value boolean value
         * @return boolean event value
         */
        public static Value bool(boolean value) { return new Value(ValueKind.BOOLEAN, List.of(Boolean.toString(value))); }
        /**
         * @param value numeric value
         * @return numeric event value
         */
        public static Value number(Number value) {
            return new Value(ValueKind.NUMBER, List.of(String.valueOf(Objects.requireNonNull(value, "value"))));
        }
        /**
         * @param value selected id, or {@code null}
         * @return single-selection event value
         */
        public static Value selection(String value) {
            return value == null ? new Value(ValueKind.SELECTION, List.of())
                    : new Value(ValueKind.SELECTION, List.of(value));
        }
        /**
         * @param values selected ids
         * @return multiple-selection event value
         */
        public static Value selections(List<String> values) { return new Value(ValueKind.MULTI_SELECTION, values); }
    }

    /**
     * Renderer 发往宿主且不含可执行插件回调的事件。
     * 宿主不得记录或持久化 {@link InputKind#PASSWORD} 节点产生的值。
     */
    record Event(long documentRevision, long interactionRevision,
                 EventType type, String nodeId, Value value) {
        /**
         * 创建必须由 {@code DesktopUiContext} 盖章后才能派发的事件意图。
         *
         * @param type renderer 事件类型
         * @param nodeId 产生事件的节点 id
         * @param value 类型化事件值
         */
        public Event(EventType type, String nodeId, Value value) {
            this(-1L, -1L, type, nodeId, value);
        }

        /**
         * @param documentRevision 产生控件时观察到的文档修订号
         * @param interactionRevision 产生值控件时观察到的交互契约修订号
         * @param type renderer 事件类型
         * @param nodeId 产生事件的节点 id
         * @param value 类型化事件值
         */
        public Event {
            if (documentRevision < -1L) throw new IllegalArgumentException("documentRevision must be -1 or greater");
            if (interactionRevision < -1L) {
                throw new IllegalArgumentException("interactionRevision must be -1 or greater");
            }
            type = Objects.requireNonNull(type, "type");
            nodeId = requireId(nodeId, "nodeId");
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

        /**
         * @param revision 渲染时观察到的文档修订号
         * @return 盖上文档修订号的事件意图
         */
        public Event atRevision(long revision) {
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
            return new Event(revision, -1L, type, nodeId, value);
        }

        /**
         * @param documentRevision 渲染时观察到的文档修订号
         * @param interactionRevision 渲染时观察到的交互契约修订号
         * @return 盖上两个观察修订号的事件意图
         */
        public Event atRevisions(long documentRevision, long interactionRevision) {
            if (documentRevision < 0L) throw new IllegalArgumentException("documentRevision must not be negative");
            if (interactionRevision < 0L) throw new IllegalArgumentException("interactionRevision must not be negative");
            return new Event(documentRevision, interactionRevision, type, nodeId, value);
        }
    }

    /** Supported node kinds. */
    enum Kind {
        /** General container. */ CONTAINER,
        /** 可按宽度自适应列数的网格。 */ ADAPTIVE_GRID,
        /** 固定页容量的吸附横向区域。 */ PAGED_ROW,
        /** Edge-and-center container. */ DOCK,
        /** Semantic visual surface. */ SURFACE,
        /** Titled group. */ GROUP,
        /** Aligned form. */ FORM,
        /** Tabbed container. */ TABS,
        /** Scrollable container. */ SCROLL,
        /** Two-pane split. */ SPLIT,
        /** Localized text. */ TEXT,
        /** 受控语义图标。 */ ICON,
        /** Materialized image. */ IMAGE,
        /** Separator. */ SEPARATOR,
        /** Fixed spacer. */ SPACER,
        /** Progress display. */ PROGRESS,
        /** Text-like input. */ TEXT_INPUT,
        /** Boolean input. */ TOGGLE,
        /** Choice input. */ CHOICE,
        /** Bounded numeric input. */ NUMBER_INPUT,
        /** Read-only table. */ TABLE,
        /** Hierarchical tree. */ TREE,
        /** Command button. */ BUTTON,
        /** Link-like command. */ LINK
    }
    /** General container layout. */
    enum ContainerLayout {
        /** Vertical column. */ COLUMN,
        /** Horizontal row. */ ROW,
        /** Wrapping flow. */ FLOW,
        /** Fixed-column grid. */ GRID
    }
    /** Logical child alignment. */
    enum Alignment {
        /** Leading edge. */ START,
        /** Center. */ CENTER,
        /** Trailing edge. */ END,
        /** Fill the cross axis. */ STRETCH
    }
    /** Semantic surface role mapped to native toolkit colors and borders. */
    enum SurfaceStyle {
        /** Unadorned surface. */ PLAIN,
        /** Grouped card surface. */ CARD,
        /** Informational surface. */ INFO,
        /** Success surface. */ SUCCESS,
        /** Warning surface. */ WARNING,
        /** Error surface. */ ERROR,
        /** De-emphasized surface. */ MUTED
    }
    /** Aligned-row density and label sizing policy. */
    enum FormStyle {
        /** Compact labels and controls. */ COMPACT,
        /** Responsive label width. */ RESPONSIVE,
        /** Read-only key/value presentation. */ KEY_VALUE
    }
    /** Logical orientation. */
    enum Axis {
        /** Horizontal axis. */ HORIZONTAL,
        /** Vertical axis. */ VERTICAL
    }
    /** Semantic text style. */
    enum TextStyle {
        /** Regular body text. */ BODY,
        /** Emphasized body text. */ EMPHASIS,
        /** De-emphasized body text. */ SECONDARY,
        /** Bulleted body text. */ BULLET,
        /** Small caption text. */ CAPTION,
        /** Page title text. */ TITLE,
        /** Section heading text. */ HEADING,
        /** Monospaced code text. */ CODE,
        /** Success text. */ SUCCESS,
        /** Warning text. */ WARNING,
        /** Error text. */ ERROR
    }
    /** Logical text alignment. */
    enum TextAlignment {
        /** Leading-aligned text. */ START,
        /** Centered text. */ CENTER,
        /** Trailing-aligned text. */ END
    }
    /** Image scaling policy. */
    enum ScaleMode {
        /** Preserve intrinsic dimensions. */ NONE,
        /** Fit within preferred bounds. */ FIT,
        /** Fill preferred bounds. */ FILL
    }
    /** Text input semantic type. */
    enum InputKind {
        /** Single-line text. */ TEXT,
        /** Numeric text. */ NUMBER,
        /** Secret text. */ PASSWORD,
        /** Multiline text. */ MULTILINE,
        /** Search text. */ SEARCH,
        /** Calendar date. */ DATE,
        /** Time of day. */ TIME,
        /** Date and time. */ DATE_TIME,
        /** File path. */ FILE,
        /** Directory path. */ DIRECTORY
    }
    /** Boolean input presentation. */
    enum ToggleStyle {
        /** Checkbox presentation. */ CHECKBOX,
        /** Switch presentation. */ SWITCH
    }
    /** Choice input presentation. */
    enum ChoiceStyle {
        /** Drop-down combo box. */ COMBO_BOX,
        /** Radio-button group. */ RADIO_BUTTONS,
        /** Checkbox group. */ CHECK_BOXES,
        /** Visible selection list. */ LIST
    }
    /** Single or multiple selection. */
    enum SelectionMode {
        /** At most one selected item. */ SINGLE,
        /** Multiple selected items. */ MULTIPLE
    }
    /** Numeric input presentation. */
    enum NumberStyle {
        /** Spinner control. */ SPINNER,
        /** Slider control. */ SLIDER
    }
    /** Command emphasis. */
    enum ButtonStyle {
        /** Normal command. */ NORMAL,
        /** Primary command. */ PRIMARY,
        /** Destructive command. */ DANGER
    }
    /** Stable event value kind. */
    enum ValueKind {
        /** No value. */ NONE,
        /** Text value. */ TEXT,
        /** Boolean value. */ BOOLEAN,
        /** Numeric value. */ NUMBER,
        /** Single selection. */ SELECTION,
        /** Multiple selection. */ MULTI_SELECTION
    }
    /** Stable renderer event type. */
    enum EventType {
        /** Input value changed. */ CHANGE,
        /** Selection changed. */ SELECTION,
        /** Command activated. */ ACTIVATE
    }

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

    private static void collectCapabilities(DesktopUiNode node, Set<DesktopUiCapability> capabilities) {
        collectDirectCapabilities(node, capabilities);
        for (DesktopUiNode child : node.childNodes()) collectCapabilities(child, capabilities);
    }

    private static void collectDirectCapabilities(DesktopUiNode node,
                                                  Set<DesktopUiCapability> capabilities) {
        if (node instanceof Split) capabilities.add(DesktopUiCapability.SPLIT_USER_RESIZABLE);
        if (node instanceof AdaptiveGrid) capabilities.add(DesktopUiCapability.LAYOUT_ADAPTIVE_GRID);
        if (node instanceof PagedRow) capabilities.add(DesktopUiCapability.PAGED_ROW_SNAP_NAVIGATION);
        if (node instanceof Tree tree) {
            capabilities.add(DesktopUiCapability.TREE_EXPAND_COLLAPSE);
            if (tree.selectionMode() == SelectionMode.MULTIPLE) {
                capabilities.add(DesktopUiCapability.SELECTION_MULTIPLE);
            }
        }
        if (node instanceof Table table) {
            capabilities.add(DesktopUiCapability.TABLE_LARGE_DATA_SCROLL);
            if (table.selectionMode() == SelectionMode.MULTIPLE) {
                capabilities.add(DesktopUiCapability.SELECTION_MULTIPLE);
            }
        }
        if (node instanceof Choice choice && choice.selectionMode() == SelectionMode.MULTIPLE) {
            capabilities.add(DesktopUiCapability.SELECTION_MULTIPLE);
        }
        if (node instanceof TextInput input) {
            switch (input.inputKind()) {
                case NUMBER -> capabilities.add(DesktopUiCapability.INPUT_NUMERIC);
                case DATE -> capabilities.add(DesktopUiCapability.INPUT_TEMPORAL_DATE);
                case TIME -> capabilities.add(DesktopUiCapability.INPUT_TEMPORAL_TIME);
                case DATE_TIME -> capabilities.add(DesktopUiCapability.INPUT_TEMPORAL_DATE_TIME);
                case FILE -> capabilities.add(DesktopUiCapability.INPUT_PATH_FILE);
                case DIRECTORY -> capabilities.add(DesktopUiCapability.INPUT_PATH_DIRECTORY);
                default -> { }
            }
        }
    }

    private static int maxCollectionSize() { return 10_000; }
    private static int maxTextLength() { return 65_536; }
    private static int maxImageBytes() { return 5 * 1024 * 1024; }
}
