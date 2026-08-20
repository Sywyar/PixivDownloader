package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活动功能插件贡献的一页工具包中立桌面内容。
 * 页面只允许只读节点、静态对话框和无参数动作；可编辑配置继续通过
 * {@link GuiConfigContribution} 声明。动作只能映射到当前插件精确声明的相对
 * {@code /api/gui/} POST 路由，宿主固定发送空对象且不向页面暴露响应正文。
 * 宿主负责盖章可信 owner，并拒绝可写绑定、任意 URI、HTML、SVG、回调和自定义载荷。
 *
 * @param pageId 贡献插件命名空间内的稳定页面标识
 * @param order 贡献页面之间的导航顺序
 * @param title 本地化页面标题
 * @param content 完整声明式页面树
 * @param actions 动作标识到相对 {@code /api/gui/} POST 端点的映射
 * @param dialogs 当前打开且属于该页面的声明式对话框
 */
public record DesktopUiPageContribution(
        String pageId,
        int order,
        DesktopUiNode.TextToken title,
        DesktopUiNode content,
        Map<String, String> actions,
        List<DesktopUiDocument.Dialog> dialogs
) {
    /**
     * 校验不含 owner 的页面形状并防御性复制集合。
     * owner 命名空间、只读节点和精确路由归属由宿主聚合页面时校验。
     *
     * @param pageId 稳定页面标识
     * @param order 导航顺序
     * @param title 本地化页面标题
     * @param content 完整页面树
     * @param actions 声明的动作端点
     * @param dialogs 当前打开的对话框
     */
    public DesktopUiPageContribution {
        if (pageId == null || !pageId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("pageId must be a stable id");
        }
        title = Objects.requireNonNull(title, "title");
        content = Objects.requireNonNull(content, "content");
        Map<String, String> normalizedActions = new LinkedHashMap<>();
        if (actions != null) actions.forEach((actionId, endpoint) ->
                normalizedActions.put(actionId, endpoint == null ? null : endpoint.trim()));
        actions = Map.copyOf(normalizedActions);
        dialogs = List.copyOf(dialogs == null ? List.of() : dialogs);
    }

    /**
     * 创建不含动作和打开对话框的页面。
     *
     * @param pageId 稳定页面标识
     * @param order 导航顺序
     * @param title 本地化页面标题
     * @param content 完整页面树
     */
    public DesktopUiPageContribution(String pageId, int order,
                                     DesktopUiNode.TextToken title, DesktopUiNode content) {
        this(pageId, order, title, content, Map.of(), List.of());
    }
}
