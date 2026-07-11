package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/**
 * 宿主为每次能力调用独立提供的短生命周期凭证句柄。实现必须让 {@link #close()} 幂等并清除可清除的内存副本；
 * 宿主在调用结束的 finally 中执行最终关闭，插件不得缓存、转交句柄或 {@link #copySecret()} 的结果，也不得把凭证
 * 写入作品载荷、队列、pending、异常或日志。
 */
public interface ScheduledCredentialHandle extends AutoCloseable {

    boolean isPresent();

    /** 宿主凭证存储中的不透明引用；不是密钥内容。 */
    String reference();

    /** 已验证的非敏感账号键；凭证尚未探活时可为 {@code null}。 */
    String accountKey();

    /**
     * 返回由调用方负责尽快清零的临时字符副本。凭证不存在时返回空数组。
     */
    char[] copySecret();

    @Override
    void close();
}
