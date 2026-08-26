package top.sywyar.pixivdownload.plugin.api.gui;

/** 由外置插件提供、在进程生命周期内存活的原生桌面界面。 */
public interface DesktopUiProvider {
    /** @return {@code app.gui-provider} 使用的稳定 provider id */
    String id();

    /** @return 未配置 id 时此 provider 是否为默认项 */
    default boolean defaultProvider() {
        return false;
    }

    /**
     * 启动由此 provider 完整拥有的桌面界面。
     *
     * @param context 工具包无关的宿主业务上下文
     * @return 活动界面会话
     * @throws Exception 界面无法启动时抛出
     */
    DesktopUiSession launch(DesktopUiContext context) throws Exception;
}
