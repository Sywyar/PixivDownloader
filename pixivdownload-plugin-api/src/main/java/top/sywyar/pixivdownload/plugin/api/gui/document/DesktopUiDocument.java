package top.sywyar.pixivdownload.plugin.api.gui.document;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 桌面应用根页面的完整工具包无关描述。页面顺序即渲染后的导航顺序，
 * provider 只负责渲染各页面的节点树。
 *
 * @param pages 有序根页面
 * @param dialogs 有序的已打开模态对话框
 * @param shortcuts 当前文档启用的应用快捷键
 * @param tray 可选系统托盘结构
 */
public record DesktopUiDocument(List<Page> pages, List<Dialog> dialogs,
                                List<KeyboardShortcut> shortcuts, Optional<Tray> tray) {
    /**
     * 创建没有已打开对话框或快捷键的文档。
     *
     * @param pages 有序根页面
     */
    public DesktopUiDocument(List<Page> pages) {
        this(pages, List.of(), List.of(), Optional.empty());
    }

    /**
     * 创建没有应用快捷键的文档。
     *
     * @param pages 有序根页面
     * @param dialogs 有序的已打开模态对话框
     */
    public DesktopUiDocument(List<Page> pages, List<Dialog> dialogs) {
        this(pages, dialogs, List.of(), Optional.empty());
    }

    /**
     * 创建没有系统托盘的文档。
     *
     * @param pages 有序根页面
     * @param dialogs 有序的已打开模态对话框
     * @param shortcuts 当前文档启用的应用快捷键
     */
    public DesktopUiDocument(List<Page> pages, List<Dialog> dialogs,
                             List<KeyboardShortcut> shortcuts) {
        this(pages, dialogs, shortcuts, Optional.empty());
    }

    /**
     * 校验并防御性复制页面树。
     *
     * @param pages 有序根页面
     * @param dialogs 有序的已打开模态对话框
     * @param shortcuts 当前文档启用的应用快捷键
     * @param tray 可选系统托盘结构
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
            page.floatingAction().ifPresent(node -> DesktopUiNode.validateTree(node, nodeIds));
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
     * 返回渲染当前文档所需的全部节点类型。
     *
     * @return 不可变的必需节点类型集合
     */
    public Set<DesktopUiNode.Kind> requiredNodeKinds() {
        var kinds = new HashSet<DesktopUiNode.Kind>();
        for (Page page : pages) {
            kinds.addAll(DesktopUiNode.validateTree(page.content()));
            page.floatingAction().ifPresent(node -> kinds.addAll(DesktopUiNode.validateTree(node)));
        }
        for (Dialog dialog : dialogs) kinds.addAll(DesktopUiNode.validateTree(dialog.content()));
        return Set.copyOf(kinds);
    }

    /**
     * 返回无静默降级地渲染当前文档所需的全部语义能力。
     *
     * @return 不可变的必需能力集合
     */
    public Set<DesktopUiCapability> requiredCapabilities() {
        var capabilities = new HashSet<DesktopUiCapability>();
        for (Page page : pages) {
            capabilities.addAll(DesktopUiNode.requiredCapabilities(page.content()));
            page.floatingAction().ifPresent(node -> capabilities.addAll(DesktopUiNode.requiredCapabilities(node)));
        }
        for (Dialog dialog : dialogs) capabilities.addAll(DesktopUiNode.requiredCapabilities(dialog.content()));
        return Set.copyOf(capabilities);
    }

    /**
     * 系统托盘描述。provider 按同一顺序渲染菜单，并通过文档事件通道派发普通动作。
     *
     * @param tooltip 已本地化的托盘提示
     * @param items 有序托盘菜单项
     */
    public record Tray(DesktopUiNode.TextToken tooltip, List<TrayItem> items) {
        /**
         * 校验并防御性复制托盘菜单。
         *
         * @param tooltip 已本地化的托盘提示
         * @param items 有序托盘菜单项
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
     * 一个系统托盘菜单项。
     *
     * @param id 稳定菜单项标识
     * @param label 已本地化的标签；分隔符携带未使用的占位 token
     * @param role provider 对该菜单项的处理方式
     * @param actionId {@link TrayItemRole#DISPATCH} 的动作目标，其它角色为空
     */
    public record TrayItem(String id, DesktopUiNode.TextToken label,
                           TrayItemRole role, String actionId) {
        /**
         * 创建激活 provider 主窗口的菜单项。
         *
         * @param id 稳定菜单项标识
         * @param label 已本地化的标签
         * @return 窗口激活菜单项
         */
        public static TrayItem activate(String id, DesktopUiNode.TextToken label) {
            return new TrayItem(id, label, TrayItemRole.ACTIVATE_WINDOW, "");
        }

        /**
         * 创建派发应用动作的菜单项。
         *
         * @param id 稳定菜单项标识
         * @param label 已本地化的标签
         * @param actionId 应用动作目标
         * @return 动作派发菜单项
         */
        public static TrayItem dispatch(String id, DesktopUiNode.TextToken label, String actionId) {
            return new TrayItem(id, label, TrayItemRole.DISPATCH, actionId);
        }

        /**
         * 创建视觉分隔符。
         *
         * @param id 稳定菜单项标识
         * @return 分隔符菜单项
         */
        public static TrayItem separator(String id) {
            return new TrayItem(id, DesktopUiNode.TextToken.raw("-"), TrayItemRole.SEPARATOR, "");
        }

        /**
         * 校验菜单项行为与动作目标。
         *
         * @param id 稳定菜单项标识
         * @param label 已本地化的标签
         * @param role provider 对该菜单项的处理方式
         * @param actionId 派发时使用的应用动作目标
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

    /** provider 对系统托盘菜单项的处理方式。 */
    public enum TrayItemRole {
        /** 恢复并聚焦 provider 主窗口。 */ ACTIVATE_WINDOW,
        /** 通过 {@code DesktopUiContext.dispatchEvent} 发出菜单项动作。 */ DISPATCH,
        /** 渲染原生菜单分隔符。 */ SEPARATOR
    }

    /**
     * 已打开的模态对话框描述。按钮仍是 {@code content} 中的普通节点；允许关闭时，
     * 通过窗口装饰关闭会发出 {@code dismissActionId}。
     */
    public record Dialog(String id, DesktopUiNode.TextToken title, DialogStyle style,
                         DesktopUiNode content, String dismissActionId, boolean dismissible,
                         int preferredWidth, int preferredHeight) {
        /**
         * 校验一个模态对话框描述。
         *
         * @param id 稳定对话框标识
         * @param title 已本地化的对话框标题
         * @param style 对话框语义角色
         * @param content 完整对话框内容树
         * @param dismissActionId 对话框关闭时发出的动作
         * @param dismissible 是否允许通过窗口装饰关闭
         * @param preferredWidth 首选逻辑宽度，零表示使用工具包默认值
         * @param preferredHeight 首选逻辑高度，零表示使用工具包默认值
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

    /** 使用工具包原生表现渲染的对话框语义角色。 */
    public enum DialogStyle {
        /** 信息对话框。 */ INFO,
        /** 操作成功对话框。 */ SUCCESS,
        /** 警告对话框。 */ WARNING,
        /** 错误对话框。 */ ERROR,
        /** 确认问题对话框。 */ QUESTION
    }

    /**
     * 文档级键盘序列。物理键标识包括 {@code ArrowUp}、{@code KeyA}、
     * {@code Digit1}、{@code Enter} 或 {@code F1}。
     *
     * @param id 稳定快捷键标识
     * @param sequence 有序且非空的按键序列
     * @param actionId 完整序列匹配后发出的动作目标
     * @param consume 是否消费最后一个匹配按键
     */
    public record KeyboardShortcut(String id, List<KeyStroke> sequence,
                                   String actionId, boolean consume) {
        /**
         * 校验并防御性复制快捷键。
         *
         * @param id 稳定快捷键标识
         * @param sequence 有序且非空的按键序列
         * @param actionId 完整序列匹配后发出的动作目标
         * @param consume 是否消费最后一个匹配按键
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
         * 推进当前序列；不匹配时从首键重新开始。
         *
         * @param currentIndex 当前按键前预期的下一个序列索引
         * @param pressed 已按下的物理键
         * @return 下一匹配状态
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

    /** 推进一次键盘快捷键序列后的结果。 */
    public record MatchResult(int nextIndex, boolean completed) {
        /**
         * 校验下一个序列索引。
         *
         * @param nextIndex 下一个待匹配的序列索引
         * @param completed 当前按键是否完成了序列
         */
        public MatchResult {
            if (nextIndex < 0) throw new IllegalArgumentException("nextIndex must not be negative");
        }
    }

    /**
     * 工具包无关的物理键与修饰键状态。
     *
     * @param key 物理键标识
     * @param alt 是否按下 Alt
     * @param control 是否按下 Control
     * @param shift 是否按下 Shift
     * @param meta 是否按下平台 Meta 键
     */
    public record KeyStroke(String key, boolean alt, boolean control, boolean shift, boolean meta) {
        /**
         * 创建没有修饰键的物理键。
         *
         * @param key 物理键标识
         * @return 无修饰键的按键
         */
        public static KeyStroke key(String key) { return new KeyStroke(key, false, false, false, false); }

        /**
         * 校验物理键标识。
         *
         * @param key 物理键标识
         * @param alt 是否按下 Alt
         * @param control 是否按下 Control
         * @param shift 是否按下 Shift
         * @param meta 是否按下平台 Meta 键
         */
        public KeyStroke {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]{0,31}")) {
                throw new IllegalArgumentException("key must be a physical-key identifier");
            }
        }
    }

    /**
     * 根页面描述。
     *
     * @param id 仅用于导航状态的稳定页面标识
     * @param title 已本地化的标题 token
     * @param icon 导航使用的受控图标
     * @param content 完整页面内容树
     * @param floatingAction 可选的页面浮动操作树
     */
    public record Page(String id, DesktopUiNode.TextToken title, DesktopUiIcon icon,
                       DesktopUiNode content, Optional<DesktopUiNode> floatingAction) {
        /**
         * 创建使用通用信息图标且没有浮动操作的页面。
         *
         * @param id 稳定页面标识
         * @param title 已本地化的标题 token
         * @param content 完整页面内容树
         */
        public Page(String id, DesktopUiNode.TextToken title, DesktopUiNode content) {
            this(id, title, DesktopUiIcon.INFO, content, Optional.empty());
        }

        /**
         * 创建没有浮动操作的页面。
         *
         * @param id 稳定页面标识
         * @param title 已本地化的标题 token
         * @param icon 导航使用的受控图标
         * @param content 完整页面内容树
         */
        public Page(String id, DesktopUiNode.TextToken title, DesktopUiIcon icon,
                    DesktopUiNode content) {
            this(id, title, icon, content, Optional.empty());
        }

        /**
         * 校验页面描述。
         *
         * @param id 稳定页面标识
         * @param title 已本地化的标题 token
         * @param icon 导航使用的受控图标
         * @param content 完整页面内容树
         * @param floatingAction 可选的页面浮动操作树
         */
        public Page {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("id must be a stable id");
            }
            title = Objects.requireNonNull(title, "title");
            icon = Objects.requireNonNull(icon, "icon");
            content = Objects.requireNonNull(content, "content");
            floatingAction = Objects.requireNonNull(floatingAction, "floatingAction");
        }
    }

    private static void requireStableId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
    }
}
