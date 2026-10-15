package top.sywyar.pixivdownload.gui;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.GuiTokenProvider;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 在 Spring 启动时生成一次性 GUI 认证令牌，并存入 {@link GuiTokenHolder}。
 * Tomcat 接受连接前令牌已就绪，Swing 侧可安全读取。
 */
@Service
@Slf4j
public class GuiTokenService implements GuiTokenProvider {

    private String token;

    @PostConstruct
    public void init() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        GuiTokenHolder.set(token);
        log.debug("GUI token initialized");
    }

    @Override
    public String getToken() {
        return token;
    }
}
