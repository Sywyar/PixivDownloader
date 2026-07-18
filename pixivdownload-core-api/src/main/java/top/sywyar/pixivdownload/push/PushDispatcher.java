package top.sywyar.pixivdownload.push;

import java.util.List;

/**
 * 不拥有通道实现的窄派发端口。
 */
public interface PushDispatcher {

    List<PushResult> push(PushMessage message);

    PushResult push(PushChannelType type, PushMessage message);

    List<PushResult> test(List<PushChannelSettings> settings, PushMessage message);
}
