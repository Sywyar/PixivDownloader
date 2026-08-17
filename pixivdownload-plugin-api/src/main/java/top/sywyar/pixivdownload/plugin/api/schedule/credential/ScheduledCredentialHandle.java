package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/**
 * 宿主为每次能力调用独立提供的短生命周期凭证句柄。实现必须让 {@link #close()} 幂等并清除可清除的内存副本；
 * 宿主在调用结束的 finally 中执行最终关闭，插件不得缓存、转交句柄或 {@link #copySecret()} 的结果。
 *
 * <p>插件不得通过回调返回值、宿主提交入口、异常消息或 cause 等跨边界载体返回、提交或抛出原始凭据或
 * 可逆派生材料，也不得将其写入作品载荷、队列、pending、checkpoint、状态、证据或日志。
 */
public interface ScheduledCredentialHandle extends AutoCloseable {

    /**
     * 判断存在状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isPresent();

    /**
     * 宿主凭证存储中的不透明引用；不是密钥内容。
     *
     * @return 方法返回的字符串
     */
    String reference();

    /**
     * 已验证的非敏感账号键；凭证尚未探活时可为 {@code null}。
     *
     * @return 方法返回的字符串
     */
    String accountKey();

    /**
     * 返回由调用方负责尽快清零的临时字符副本。凭证不存在时返回空数组。
     *
     * @return 方法返回值
     */
    char[] copySecret();

    @Override
    void close();
}
