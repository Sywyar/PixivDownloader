package top.sywyar.pixivdownload.plugin.api.stream;

/**
 * 当前插件专属的长连接推流登记入口。
 *
 * <p>宿主在创建插件子 context 时按可信插件身份提供本接口。插件只提交自身命名空间内唯一的连接 token，
 * 不得也无需自报 plugin id、package id、generation 或 publication 身份。
 */
public interface PluginStreamRegistrar {

    /**
     * 登记一条当前插件拥有的活动推流。token 必须标识单个物理连接，不能使用会被并发连接复用的逻辑键。
     *
     * @param streamToken 流令牌
     * @param stream 数据流
     */
    void register(String streamToken, PluginStream stream);

    /**
     * 摘除当前插件下的指定流；用于客户端正常完成、断开或显式关闭，不触发关闭回调。
     *
     * @param streamToken 流令牌
     */
    void unregister(String streamToken);

    /**
     * 当前插件是否仍允许登记新推流。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean acceptsNewStreams();
}
