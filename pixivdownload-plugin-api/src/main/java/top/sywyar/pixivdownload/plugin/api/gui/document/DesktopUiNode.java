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
 * 桌面 renderer 共享的纯 JDK 声明式 UI 节点词汇。
 * 节点只携带有界纯值与稳定标识，绝不携带工具包组件或可执行回调。
 */
public sealed interface DesktopUiNode permits DesktopUiNode.Container, DesktopUiNode.AdaptiveGrid,
        DesktopUiNode.PagedRow, DesktopUiNode.Dock,
        DesktopUiNode.Surface, DesktopUiNode.Group, DesktopUiNode.Form, DesktopUiNode.Tabs, DesktopUiNode.Scroll,
        DesktopUiNode.Split, DesktopUiNode.Text, DesktopUiNode.Icon,
        DesktopUiNode.Image, DesktopUiNode.Separator, DesktopUiNode.Spacer, DesktopUiNode.Progress,
        DesktopUiNode.TextInput, DesktopUiNode.Toggle, DesktopUiNode.Choice,
        DesktopUiNode.NumberInput, DesktopUiNode.Table, DesktopUiNode.Tree,
        DesktopUiNode.Button, DesktopUiNode.Link {

    /** @return 单份文档内稳定的节点标识 */
    String id();

    /** @return 用于 renderer 能力协商的节点类型 */
    Kind kind();

    /** @return 供文档校验与 renderer 使用的直接子节点 */
    default List<DesktopUiNode> childNodes() {
        return List.of();
    }

    /**
     * 校验全局节点标识唯一性以及树深度和大小边界，并返回全部必需的 renderer 节点类型。
     *
     * @param root 声明式根节点
     * @return 不可变的必需节点类型集合
     */
    static Set<Kind> validateTree(DesktopUiNode root) {
        Set<String> ids = new HashSet<>();
        return validateTree(root, ids);
    }

    /**
     * 使用文档级标识集合校验一棵树，并返回全部必需的 renderer 节点类型。
     *
     * @param root 声明式根节点
     * @param documentIds 可变的文档级标识集合
     * @return 不可变的必需节点类型集合
     */
    static Set<Kind> validateTree(DesktopUiNode root, Set<String> documentIds) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(documentIds, "documentIds");
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        validateNode(root, documentIds, kinds, 0);
        return Set.copyOf(kinds);
    }

    /**
     * @param root 声明式根节点
     * @return 当前节点树要求的 renderer 语义能力
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

    /** 通用列、行、流式或网格容器。 */
    record Container(String id, ContainerLayout layout, int columns, int gap,
                     Alignment alignment, List<DesktopUiNode> children) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param layout 容器布局
         * @param columns 网格列数
         * @param gap 子节点逻辑间距
         * @param alignment 子节点逻辑对齐方式
         * @param children 有序子节点
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
        /**
         * @param id 稳定节点标识
         * @param minimumColumnWidth 单列最小逻辑宽度
         * @param maximumColumns 最大列数
         * @param horizontalGap 水平逻辑间距
         * @param verticalGap 垂直逻辑间距
         * @param children 有序子节点
         */
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
        /** 每页固定项数。 */
        public static final int FIXED_ITEMS_PER_PAGE = 4;

        /**
         * @param id 稳定节点标识
         * @param itemsPerPage 每页项数，必须等于 {@link #FIXED_ITEMS_PER_PAGE}
         * @param gap 子节点逻辑间距
         * @param children 有序子节点
         */
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

    /** 边缘固定、中心可扩展的边界布局容器。 */
    record Dock(String id, int gap, DesktopUiNode top, DesktopUiNode center,
                DesktopUiNode bottom, DesktopUiNode start, DesktopUiNode end) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param gap 子节点逻辑间距
         * @param top 可选顶部子节点
         * @param center 可选的可扩展中心子节点
         * @param bottom 可选底部子节点
         * @param start 可选起始侧子节点
         * @param end 可选结束侧子节点
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
        /**
         * @param id 稳定节点标识
         * @param icon 受控图标
         * @param tone 语义色调
         * @param accessibleLabel 无障碍标签
         */
        public Icon {
            id = requireId(id, "id");
            icon = Objects.requireNonNull(icon, "icon");
            tone = Objects.requireNonNull(tone, "tone");
            accessibleLabel = Objects.requireNonNull(accessibleLabel, "accessibleLabel");
        }

        @Override public Kind kind() { return Kind.ICON; }
    }

    /** 带工具包无关逻辑内边距的语义视觉表面。 */
    record Surface(String id, SurfaceStyle style, Insets padding,
                   boolean fillWidth, boolean fillHeight, DesktopUiNode content) implements DesktopUiNode {
        /**
         * 创建填充可用宽度但不填充可用高度的表面。
         *
         * @param id 稳定节点标识
         * @param style 语义表面样式
         * @param padding 逻辑内边距
         * @param fillWidth 是否填充可用宽度
         * @param content 表面内容
         */
        public Surface(String id, SurfaceStyle style, Insets padding,
                       boolean fillWidth, DesktopUiNode content) {
            this(id, style, padding, fillWidth, false, content);
        }

        /**
         * @param id 稳定节点标识
         * @param style 语义表面样式
         * @param padding 逻辑内边距
         * @param fillWidth 是否填充可用宽度
         * @param fillHeight 是否填充可用高度
         * @param content 表面内容
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

    /** 带标题的分组容器。 */
    record Group(String id, TextToken title, DesktopUiNode content) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param title 已本地化的分组标题
         * @param content 分组内容
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
     * 对齐的表单行。行拥有可见标签与帮助文字，因此 renderer 将行内容作为控件本身渲染，
     * 不重复控件节点自己的标签。
     */
    record Form(String id, FormStyle formStyle, TextToken labelSuffix,
                List<FormRow> rows) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param formStyle 标签尺寸与密度策略
         * @param labelSuffix 可选的已本地化标签后缀
         * @param rows 有序表单行
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

    /** 标签页容器。 */
    record Tabs(String id, List<Tab> tabs) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param tabs 有序标签页描述
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

    /** 可滚动子节点容器。 */
    record Scroll(String id, DesktopUiNode content) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param content 可滚动内容
         */
        public Scroll {
            id = requireId(id, "id");
            content = Objects.requireNonNull(content, "content");
        }

        @Override public Kind kind() { return Kind.SCROLL; }
        @Override public List<DesktopUiNode> childNodes() { return List.of(content); }
    }

    /** 双区域分割容器。 */
    record Split(String id, Axis axis, double resizeWeight,
                 DesktopUiNode first, DesktopUiNode second) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param axis 分割方向
         * @param resizeWeight 分配给第一个子节点的额外空间比例
         * @param first 第一个子节点
         * @param second 第二个子节点
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

    /** 普通本地化文本；有意不支持原始 HTML。 */
    record Text(String id, TextToken text, TextStyle style,
                boolean wrap, boolean selectable, TextAlignment textAlignment) implements DesktopUiNode {
        /**
         * 创建沿逻辑起始边对齐的文本。
         *
         * @param id 稳定节点标识
         * @param text 本地化文本
         * @param style 语义文本样式
         * @param wrap 是否允许换行
         * @param selectable 是否允许选择文本
         */
        public Text(String id, TextToken text, TextStyle style, boolean wrap, boolean selectable) {
            this(id, text, style, wrap, selectable, TextAlignment.START);
        }

        /**
         * @param id 稳定节点标识
         * @param text 本地化文本
         * @param style 语义文本样式
         * @param wrap 是否允许换行
         * @param selectable 是否允许选择文本
         * @param textAlignment 逻辑文本对齐方式
         */
        public Text {
            id = requireId(id, "id");
            text = Objects.requireNonNull(text, "text");
            style = style == null ? TextStyle.BODY : style;
            textAlignment = textAlignment == null ? TextAlignment.START : textAlignment;
        }

        @Override public Kind kind() { return Kind.TEXT; }
    }

    /** 已物化且大小有界、带本地化替代文本的图像。 */
    record Image(String id, ImageData image, TextToken altText,
                 int preferredWidth, int preferredHeight, ScaleMode scaleMode) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param image 已物化的图像数据
         * @param altText 本地化替代文本
         * @param preferredWidth 首选逻辑宽度
         * @param preferredHeight 首选逻辑高度
         * @param scaleMode 图像缩放策略
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

    /** 水平或垂直分隔线。 */
    record Separator(String id, Axis axis) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param axis 分隔线方向
         */
        public Separator {
            id = requireId(id, "id");
            axis = Objects.requireNonNull(axis, "axis");
        }

        @Override public Kind kind() { return Kind.SEPARATOR; }
    }

    /** 固定逻辑尺寸的空白间隔。 */
    record Spacer(String id, int width, int height) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param width 逻辑宽度
         * @param height 逻辑高度
         */
        public Spacer {
            id = requireId(id, "id");
            requireRange(width, 0, 4096, "width");
            requireRange(height, 0, 4096, "height");
        }

        @Override public Kind kind() { return Kind.SPACER; }
    }

    /** 确定或不确定模式的进度显示。 */
    record Progress(String id, double progress, boolean indeterminate,
                    TextToken text) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param progress 从零到一的确定进度
         * @param indeterminate 是否使用不确定进度
         * @param text 可选的本地化进度文本
         */
        public Progress {
            id = requireId(id, "id");
            if (!indeterminate && (!Double.isFinite(progress) || progress < 0d || progress > 1d)) {
                throw new IllegalArgumentException("progress must be between 0 and 1");
            }
        }

        @Override public Kind kind() { return Kind.PROGRESS; }
    }

    /** 文本型输入，包括密码、多行、搜索、时间、文件和目录变体。 */
    record TextInput(String id, String bindingId, TextToken label, TextToken help,
                     InputKind inputKind, String value, int columns, int rows,
                     boolean enabled, long stateRevision) implements DesktopUiNode {
        /**
         * 创建处于初始状态修订代的文本型输入。
         *
         * @param id 稳定节点标识
         * @param bindingId 稳定的值变更事件目标
         * @param label 本地化标签
         * @param help 可选的本地化帮助文本
         * @param inputKind 文本输入语义类型
         * @param value 非密码输入的初始值
         * @param columns 首选列数
         * @param rows 首选行数
         * @param enabled 控件是否启用
         */
        public TextInput(String id, String bindingId, TextToken label, TextToken help,
                         InputKind inputKind, String value, int columns, int rows,
                         boolean enabled) {
            this(id, bindingId, label, help, inputKind, value, columns, rows, enabled, 0L);
        }

        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的值变更事件目标
         * @param label 本地化标签
         * @param help 可选的本地化帮助文本
         * @param inputKind 文本输入语义类型
         * @param value 非密码输入的初始值
         * @param columns 首选列数
         * @param rows 首选行数
         * @param enabled 控件是否启用
         * @param stateRevision 单调递增的外部状态修订号
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

    /** 复选框或开关样式的布尔输入。 */
    record Toggle(String id, String bindingId, TextToken label, TextToken help,
                  ToggleStyle toggleStyle, boolean selected, boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的值变更事件目标
         * @param label 本地化标签
         * @param help 可选的本地化帮助文本
         * @param toggleStyle 布尔输入呈现样式
         * @param selected 当前选中状态
         * @param enabled 控件是否启用
         */
        public Toggle {
            id = requireId(id, "id");
            bindingId = requireId(bindingId, "bindingId");
            label = Objects.requireNonNull(label, "label");
            toggleStyle = toggleStyle == null ? ToggleStyle.CHECKBOX : toggleStyle;
        }

        @Override public Kind kind() { return Kind.TOGGLE; }
    }

    /** 下拉框、单选组、复选组或列表选择控件。 */
    record Choice(String id, String bindingId, TextToken label, TextToken help,
                  ChoiceStyle choiceStyle, SelectionMode selectionMode,
                  List<Option> options, List<String> selectedIds,
                  boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的选择事件目标
         * @param label 本地化标签
         * @param help 可选的本地化帮助文本
         * @param choiceStyle 选择控件呈现样式
         * @param selectionMode 单选或多选模式
         * @param options 有序可选项
         * @param selectedIds 已选选项标识
         * @param enabled 控件是否启用
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

    /** 整数微调框或滑块输入。 */
    record NumberInput(String id, String bindingId, TextToken label, TextToken help,
                       NumberStyle numberStyle, int value, int minimum, int maximum,
                       int step, boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的值变更事件目标
         * @param label 本地化标签
         * @param help 可选的本地化帮助文本
         * @param numberStyle 数值输入呈现样式
         * @param value 当前值
         * @param minimum 最小可接受值
         * @param maximum 最大可接受值
         * @param step 正数步长
         * @param enabled 控件是否启用
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

    /** 可选行选择的只读表格数据。 */
    record Table(String id, String bindingId, List<TableColumn> columns,
                 List<TableRow> rows, SelectionMode selectionMode,
                 List<String> selectedRowIds, boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的选择事件目标
         * @param columns 有序表格列
         * @param rows 有序表格行
         * @param selectionMode 单行或多行选择模式
         * @param selectedRowIds 已选行标识
         * @param enabled 是否启用选择
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

    /** 节点标识稳定的层级数据树。 */
    record Tree(String id, String bindingId, List<TreeItem> items,
                SelectionMode selectionMode, List<String> selectedIds,
                boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param bindingId 稳定的选择事件目标
         * @param items 有序根节点
         * @param selectionMode 单节点或多节点选择模式
         * @param selectedIds 已选节点标识
         * @param enabled 是否启用选择
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

    /** 触发激活事件的命令按钮。 */
    record Button(String id, String actionId, TextToken label, TextToken help,
                  ButtonStyle buttonStyle, boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param actionId 稳定的激活事件目标
         * @param label 本地化按钮标签
         * @param help 可选的本地化帮助文本
         * @param buttonStyle 命令强调样式
         * @param enabled 按钮是否启用
         */
        public Button {
            id = requireId(id, "id");
            actionId = requireId(actionId, "actionId");
            label = Objects.requireNonNull(label, "label");
            buttonStyle = buttonStyle == null ? ButtonStyle.NORMAL : buttonStyle;
        }

        @Override public Kind kind() { return Kind.BUTTON; }
    }

    /** 不嵌入任意 URI、触发激活事件的链接式命令。 */
    record Link(String id, String actionId, TextToken label, TextToken help,
                boolean enabled) implements DesktopUiNode {
        /**
         * @param id 稳定节点标识
         * @param actionId 稳定的激活事件目标
         * @param label 本地化链接标签
         * @param help 可选的本地化帮助文本
         * @param enabled 链接是否启用
         */
        public Link {
            id = requireId(id, "id");
            actionId = requireId(actionId, "actionId");
            label = Objects.requireNonNull(label, "label");
        }

        @Override public Kind kind() { return Kind.LINK; }
    }

    /** 本地化文本令牌；命名空间或键不可用时使用回退文本。 */
    record TextToken(String namespace, String key, String fallback, List<String> arguments) {
        /**
         * @param namespace 可选的插件 i18n 命名空间
         * @param key 稳定消息键；原始回退文本使用空值
         * @param fallback 回退文本
         * @param arguments 消息格式化参数
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
         * @param key 稳定宿主消息键
         * @return 使用消息键作为回退文本的宿主消息令牌
         */
        public static TextToken key(String key) { return new TextToken(null, key, key, List.of()); }
        /**
         * @param text 原始回退文本
         * @return 不带消息键的文本令牌
         */
        public static TextToken raw(String text) { return new TextToken(null, "", text, List.of()); }
    }

    /** 使用 Base64 编码的不可变已物化图像字节。 */
    record ImageData(String mediaType, String base64) {
        /**
         * @param mediaType 图像媒体类型
         * @param base64 大小有界的 Base64 图像字节
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

        /** @return 解码后的图像字节 */
        public byte[] bytes() { return Base64.getDecoder().decode(base64); }
    }

    /** 标签页描述。 */
    record Tab(String id, TextToken title, DesktopUiNode content) {
        /**
         * @param id 稳定标签页标识
         * @param title 本地化标签页标题
         * @param content 完整标签页内容
         */
        public Tab {
            id = requireId(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** 一行对齐表单；尾随内容通常为生效方式标记或次要动作。 */
    record FormRow(String id, TextToken label, TextToken help,
                   DesktopUiNode content, DesktopUiNode trailing) {
        /**
         * @param id 稳定行标识
         * @param label 本地化行标签
         * @param help 可选的本地化帮助文本
         * @param content 行控件或值内容
         * @param trailing 可选的尾随内容
         */
        public FormRow {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** 可选项描述。 */
    record Option(String id, TextToken label, boolean enabled) {
        /**
         * @param id 稳定选项标识
         * @param label 本地化选项标签
         * @param enabled 选项是否启用
         */
        public Option {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
        }
    }

    /** 表格列描述。 */
    record TableColumn(String id, TextToken label, int preferredWidth) {
        /**
         * @param id 稳定列标识
         * @param label 本地化列标签
         * @param preferredWidth 首选逻辑宽度；零表示使用工具包默认值
         */
        public TableColumn {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            requireRange(preferredWidth, 0, 4096, "preferredWidth");
        }
    }

    /** 表格行描述。 */
    record TableRow(String id, List<String> cells) {
        /**
         * @param id 稳定行标识
         * @param cells 有序纯文本单元格
         */
        public TableRow {
            id = requireId(id, "id");
            cells = copyBoundedStrings(cells, "cells");
        }
    }

    /** 递归树节点描述。 */
    record TreeItem(String id, TextToken label, List<TreeItem> children) {
        /**
         * @param id 稳定节点标识
         * @param label 本地化节点标签
         * @param children 有序子节点
         */
        public TreeItem {
            id = requireId(id, "id");
            label = Objects.requireNonNull(label, "label");
            children = copyBounded(children, "children");
        }
    }

    /** 所有渲染器共享的逻辑上、尾、下、起始边距。 */
    record Insets(int top, int end, int bottom, int start) {
        /** 零边距。 */
        public static final Insets NONE = new Insets(0, 0, 0, 0);

        /**
         * @param top 逻辑上边距
         * @param end 逻辑尾边距
         * @param bottom 逻辑下边距
         * @param start 逻辑起始边距
         */
        public Insets {
            requireRange(top, 0, 512, "top");
            requireRange(end, 0, 512, "end");
            requireRange(bottom, 0, 512, "bottom");
            requireRange(start, 0, 512, "start");
        }

        /**
         * @param value 每条边的边距
         * @return 四边相等的边距
         */
        public static Insets all(int value) { return new Insets(value, value, value, value); }
    }

    /** 渲染器投影的类型化事件值。 */
    record Value(ValueKind kind, List<String> values) {
        /**
         * @param kind 稳定值类型
         * @param values 有界序列化值
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

        /** @return 空事件值 */
        public static Value empty() { return new Value(ValueKind.NONE, List.of()); }
        /**
         * @param value 文本值
         * @return 文本事件值
         */
        public static Value text(String value) { return new Value(ValueKind.TEXT, List.of(value == null ? "" : value)); }
        /**
         * @param value 布尔值
         * @return 布尔事件值
         */
        public static Value bool(boolean value) { return new Value(ValueKind.BOOLEAN, List.of(Boolean.toString(value))); }
        /**
         * @param value 数值
         * @return 数值事件值
         */
        public static Value number(Number value) {
            return new Value(ValueKind.NUMBER, List.of(String.valueOf(Objects.requireNonNull(value, "value"))));
        }
        /**
         * @param value 已选标识，或 {@code null}
         * @return 单选事件值
         */
        public static Value selection(String value) {
            return value == null ? new Value(ValueKind.SELECTION, List.of())
                    : new Value(ValueKind.SELECTION, List.of(value));
        }
        /**
         * @param values 已选标识
         * @return 多选事件值
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

    /** 支持的节点类型。 */
    enum Kind {
        /** 通用容器。 */ CONTAINER,
        /** 可按宽度自适应列数的网格。 */ ADAPTIVE_GRID,
        /** 固定页容量的吸附横向区域。 */ PAGED_ROW,
        /** 边缘与中心区域容器。 */ DOCK,
        /** 语义视觉表面。 */ SURFACE,
        /** 带标题的分组。 */ GROUP,
        /** 对齐表单。 */ FORM,
        /** 标签页容器。 */ TABS,
        /** 可滚动容器。 */ SCROLL,
        /** 双区域分割容器。 */ SPLIT,
        /** 本地化文本。 */ TEXT,
        /** 受控语义图标。 */ ICON,
        /** 已物化图像。 */ IMAGE,
        /** 分隔线。 */ SEPARATOR,
        /** 固定间隔。 */ SPACER,
        /** 进度显示。 */ PROGRESS,
        /** 文本型输入。 */ TEXT_INPUT,
        /** 布尔输入。 */ TOGGLE,
        /** 选择输入。 */ CHOICE,
        /** 有界数值输入。 */ NUMBER_INPUT,
        /** 只读表格。 */ TABLE,
        /** 层级树。 */ TREE,
        /** 命令按钮。 */ BUTTON,
        /** 链接式命令。 */ LINK
    }
    /** 通用容器布局。 */
    enum ContainerLayout {
        /** 垂直列。 */ COLUMN,
        /** 水平行。 */ ROW,
        /** 自动换行流。 */ FLOW,
        /** 固定列数网格。 */ GRID
    }
    /** 子节点逻辑对齐方式。 */
    enum Alignment {
        /** 起始边。 */ START,
        /** 居中。 */ CENTER,
        /** 尾边。 */ END,
        /** 填满交叉轴。 */ STRETCH
    }
    /** 映射到原生工具包颜色和边框的语义表面角色。 */
    enum SurfaceStyle {
        /** 无装饰表面。 */ PLAIN,
        /** 分组卡片表面。 */ CARD,
        /** 信息表面。 */ INFO,
        /** 成功表面。 */ SUCCESS,
        /** 警告表面。 */ WARNING,
        /** 错误表面。 */ ERROR,
        /** 弱化表面。 */ MUTED
    }
    /** 对齐行密度与标签尺寸策略。 */
    enum FormStyle {
        /** 紧凑标签与控件。 */ COMPACT,
        /** 响应式标签宽度。 */ RESPONSIVE,
        /** 只读键值呈现。 */ KEY_VALUE
    }
    /** 逻辑方向。 */
    enum Axis {
        /** 水平轴。 */ HORIZONTAL,
        /** 垂直轴。 */ VERTICAL
    }
    /** 语义文本样式。 */
    enum TextStyle {
        /** 普通正文。 */ BODY,
        /** 强调正文。 */ EMPHASIS,
        /** 弱化正文。 */ SECONDARY,
        /** 项目符号正文。 */ BULLET,
        /** 小号说明文本。 */ CAPTION,
        /** 页面标题。 */ TITLE,
        /** 区块标题。 */ HEADING,
        /** 等宽代码文本。 */ CODE,
        /** 成功文本。 */ SUCCESS,
        /** 警告文本。 */ WARNING,
        /** 错误文本。 */ ERROR
    }
    /** 逻辑文本对齐方式。 */
    enum TextAlignment {
        /** 沿起始边对齐。 */ START,
        /** 居中对齐。 */ CENTER,
        /** 沿尾边对齐。 */ END
    }
    /** 图像缩放策略。 */
    enum ScaleMode {
        /** 保留固有尺寸。 */ NONE,
        /** 适配首选边界。 */ FIT,
        /** 填满首选边界。 */ FILL
    }
    /** 文本输入语义类型。 */
    enum InputKind {
        /** 单行文本。 */ TEXT,
        /** 数值文本。 */ NUMBER,
        /** 密文。 */ PASSWORD,
        /** 多行文本。 */ MULTILINE,
        /** 搜索文本。 */ SEARCH,
        /** 日历日期。 */ DATE,
        /** 一天内的时间。 */ TIME,
        /** 日期与时间。 */ DATE_TIME,
        /** 文件路径。 */ FILE,
        /** 目录路径。 */ DIRECTORY
    }
    /** 布尔输入呈现样式。 */
    enum ToggleStyle {
        /** 复选框样式。 */ CHECKBOX,
        /** 开关样式。 */ SWITCH
    }
    /** 选择输入呈现样式。 */
    enum ChoiceStyle {
        /** 下拉组合框。 */ COMBO_BOX,
        /** 单选按钮组。 */ RADIO_BUTTONS,
        /** 复选框组。 */ CHECK_BOXES,
        /** 可见选择列表。 */ LIST
    }
    /** 单选或多选模式。 */
    enum SelectionMode {
        /** 最多选择一项。 */ SINGLE,
        /** 选择多项。 */ MULTIPLE
    }
    /** 数值输入呈现样式。 */
    enum NumberStyle {
        /** 微调框控件。 */ SPINNER,
        /** 滑块控件。 */ SLIDER
    }
    /** 命令强调样式。 */
    enum ButtonStyle {
        /** 普通命令。 */ NORMAL,
        /** 主要命令。 */ PRIMARY,
        /** 破坏性命令。 */ DANGER
    }
    /** 稳定事件值类型。 */
    enum ValueKind {
        /** 无值。 */ NONE,
        /** 文本值。 */ TEXT,
        /** 布尔值。 */ BOOLEAN,
        /** 数值。 */ NUMBER,
        /** 单选值。 */ SELECTION,
        /** 多选值。 */ MULTI_SELECTION
    }
    /** 稳定渲染器事件类型。 */
    enum EventType {
        /** 输入值已变更。 */ CHANGE,
        /** 选择已变更。 */ SELECTION,
        /** 命令已激活。 */ ACTIVATE
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
