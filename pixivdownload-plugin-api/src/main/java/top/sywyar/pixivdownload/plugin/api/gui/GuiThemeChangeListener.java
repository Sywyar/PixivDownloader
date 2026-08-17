package top.sywyar.pixivdownload.plugin.api.gui;

/** 主题贡献用于报告其自有监听器所观察到外观变化的回调。 */
@FunctionalInterface
public interface GuiThemeChangeListener {

    /**
     * 主题贡献观察到新外观时调用。
     *
     * @param appearance 当前外观，永不为 {@code null}
     */
    void appearanceChanged(GuiThemeAppearance appearance);
}
