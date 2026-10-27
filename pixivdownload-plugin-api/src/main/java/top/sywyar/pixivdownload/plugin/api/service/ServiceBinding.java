package top.sywyar.pixivdownload.plugin.api.service;

/**
 * 一组显式依赖的持续绑定意图。缺席时保留意图，恢复后使用新的精确代理重新激活。
 * 激活失败不重放业务；重试仅重新建立资源。关闭失败保留本轮资源和所属 context。
 */
public interface ServiceBinding extends AutoCloseable {
    /** 本地只读状态，不包含插件异常对象或样本。 */
    enum State {
        /** 自身尚未发布，或至少一项依赖缺席。 */
        WAITING,
        /** 至少一项依赖存在多个活动提供方。 */
        AMBIGUOUS,
        /** 本轮激活回调正在执行。 */
        ACTIVATING,
        /** 本轮激活成功且仍接受调用。 */
        ACTIVE,
        /** 准入已撤回，等待实际执行退出或资源释放。 */
        DRAINING,
        /** 激活失败且回滚完成，等待显式重试或依赖变更。 */
        FAILED,
        /** 资源清理失败；旧轮次和租约仍保留供重试。 */
        CLOSE_FAILED,
        /** 意图已永久撤销且资源清理完成。 */
        CLOSED
    }

    /** @return 当前状态；ACTIVE 也不免除每次调用的 publication 检查 */
    State state();

    /** 请求重试失败的资源清理或激活；不重放服务命令。 */
    void retry();

    /**
     * 永久撤销意图并异步排空、释放本轮资源。可重复调用以重试清理。
     * 返回不表示已退出；仅 CLOSED 表示清理完成。关闭后不会因依赖恢复而重新激活。
     */
    @Override
    void close();
}
