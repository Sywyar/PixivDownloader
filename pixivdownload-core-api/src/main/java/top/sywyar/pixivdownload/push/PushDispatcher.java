package top.sywyar.pixivdownload.push;

import java.util.List;

/**
 * 不拥有通道实现的窄派发端口。
 */
public interface PushDispatcher {

    /**
     * 执行对应操作并返回结果。
     *
     * @param message 消息
     * @return 方法返回的列表
     */
    List<PushResult> push(PushMessage message);

    /**
     * 执行对应操作并返回结果。
     *
     * @param channelId 通道标识
     * @param message 消息
     * @return 方法返回的 {@code PushResult} 实例
     */
    PushResult push(PushChannelId channelId, PushMessage message);

    /**
     * 执行对应操作并返回结果。
     *
     * @param settings 设置
     * @param message 消息
     * @return 方法返回的列表
     */
    List<PushResult> test(List<PushChannelSettings> settings, PushMessage message);
}
