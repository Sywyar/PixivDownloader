package top.sywyar.pixivdownload.gui;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * 在 Spring 启动时生成一次性 GUI 认证令牌，并存入 {@link GuiTokenHolder}。
 * Tomcat 接受连接前令牌已就绪，Swing 侧可安全读取。
 */
@Service
@Slf4j
public class GuiTokenService {

    private String token;

    @PostConstruct
    public void init() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        token = sb.toString();
        GuiTokenHolder.set(token);
        log.debug("GUI token initialized");
    }

    public String getToken() {
        return token;
    }
}
