package top.sywyar.pixivdownload.i18n;

import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class MessageBundles {
    private MessageBundles() {}
    public static String get(String code, Object... arguments) { return SwingHost.host().message(code, arguments); }
}
