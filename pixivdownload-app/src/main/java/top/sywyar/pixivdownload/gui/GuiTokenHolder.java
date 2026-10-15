package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.GuiTokenProvider;

/**
 * Spring 侧与 Swing 侧共享 GUI 认证令牌的静态桥接。
 * {@link GuiTokenService} 在 Spring 初始化时写入令牌；Swing 面板读取后附到请求头中。
 */
public final class GuiTokenHolder {

    public static final String HEADER_NAME = GuiTokenProvider.HEADER_NAME;

    private GuiTokenHolder() {}

    private static volatile String token;

    static void set(String value) {
        token = value;
    }

    public static String get() {
        return token;
    }
}
