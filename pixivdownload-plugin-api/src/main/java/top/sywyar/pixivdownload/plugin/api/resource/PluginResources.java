package top.sywyar.pixivdownload.plugin.api.resource;

/**
 * 当前插件 serving 代际的资源入口，由宿主在子 context 刷新前注入。
 *
 * <p>作用域归宿主所有；插件停止或启动回滚时先关闭准入，待请求、服务与任务真实排空后释放资源，
 * 最后关闭子 context。旧入口永不转投新代际。后台任务仍须使用任务登记器，资源关闭不能代替任务 drain。
 * 初始化时即可创建作用域，事件源也可能立即回调；这些回调不能假定插件服务已经发布。
 */
public interface PluginResources {

    /**
     * 创建局部资源作用域。短期功能结束后可主动关闭；遗漏的作用域由宿主清理。
     *
     * @return 当前代际的新作用域
     * @throws PluginResourceException 当前代际已关闭准入
     */
    PluginResourceScope openScope();
}
