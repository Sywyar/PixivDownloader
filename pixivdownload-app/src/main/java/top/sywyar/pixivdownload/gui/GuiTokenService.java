package top.sywyar.pixivdownload.gui;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.GuiTokenProvider;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 为所属 Spring 上下文生成 GUI 认证令牌，在 Tomcat 接受连接前就绪。
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
        log.debug("GUI token initialized");
    }

    @Override
    public String getToken() {
        return token;
    }
}
