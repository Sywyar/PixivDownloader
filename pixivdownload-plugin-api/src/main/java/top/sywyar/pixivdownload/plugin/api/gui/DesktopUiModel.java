package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

/**
 * 宿主拥有、与工具包无关的桌面界面状态。
 * Renderer 原子读取不可变快照并返回类型事件；宿主忽略未知或过期事件。
 */
public interface DesktopUiModel {
    /**
     * 返回当前完整桌面界面快照。
     *
     * @return 原子发布的不可变快照
     */
    DesktopUiSnapshot snapshot();

    /**
     * 向宿主模型派发一个 renderer 事件。
     *
     * @param event 类型事件
     */
    void dispatch(DesktopUiNode.Event event);
}
