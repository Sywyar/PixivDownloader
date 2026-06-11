package top.sywyar.pixivdownload.plugin.api.event;

/**
 * 事件订阅句柄。{@link #close()} 即退订，必须幂等。
 */
public interface EventSubscription extends AutoCloseable {

    @Override
    void close();
}
