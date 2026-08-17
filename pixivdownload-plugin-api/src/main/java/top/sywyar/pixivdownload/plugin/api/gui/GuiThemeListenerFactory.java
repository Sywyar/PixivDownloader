package top.sywyar.pixivdownload.plugin.api.gui;

/** 为 GUI 主题贡献创建可关闭的监听会话。 */
@FunctionalInterface
public interface GuiThemeListenerFactory {

    /**
     * 开始监听与该主题有关的变化。
     *
     * @param listener 接收外观变化的回调
     * @return 可关闭会话；不需要监听器时使用 {@link GuiThemeListenerSession#none()}
     */
    GuiThemeListenerSession open(GuiThemeChangeListener listener);
}
