package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 应用 GUI 主题。调用方到达 AWT 事件分派线程后，由
 * {@link GuiThemeContribution#applyOnEventDispatchThread()} 调用实现。
 */
@FunctionalInterface
public interface GuiThemeApplier {

    /**
     * 把主题应用到当前桌面 UI 状态。
     *
     * @throws Exception 无法应用主题时抛出
     */
    void apply() throws Exception;
}
