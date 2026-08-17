package top.sywyar.pixivdownload.plugin.api.gui;

/** GUI 主题贡献返回的可关闭监听句柄。 */
@FunctionalInterface
public interface GuiThemeListenerSession extends AutoCloseable {

    /**
     * 返回供没有后台监听器的主题共享使用的空操作会话。
     *
     * @return 可重复关闭的空操作会话
     */
    static GuiThemeListenerSession none() {
        return () -> {
        };
    }

    /**
     * 释放监听器资源。实现必须保持幂等。
     */
    @Override
    void close();
}
