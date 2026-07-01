package top.sywyar.pixivdownload.push;

import java.util.List;

/**
 * Core push dispatch facade used by the optional push plugin UI and sinks.
 */
public interface PushDispatcher {

    List<PushResult> push(PushMessage message);

    PushResult push(PushChannelType type, PushMessage message);

    List<PushResult> test(List<PushChannelSettings> settings, PushMessage message);
}
