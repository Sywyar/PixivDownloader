package top.sywyar.pixivdownload.config;

import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.NioEndpoint;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.net.BindException;
import java.net.NetworkInterface;

/** 开发模式在真正绑定监听 socket 时递增端口，不重建应用上下文。 */
@Component
@Order(2)
public class DevelopmentWebServerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (PluginDevelopmentArtifacts.enabled()) {
            factory.setProtocol(DevelopmentProtocol.class.getName());
        }
    }

    /** 由 Tomcat 按协议类名创建，沿用默认 NIO 与 SSL 初始化流程。 */
    public static final class DevelopmentProtocol extends Http11NioProtocol {
        public DevelopmentProtocol() {
            super(new NioEndpoint() {
                @Override
                protected void initServerSocket() throws Exception {
                    while (true) {
                        try {
                            super.initServerSocket();
                            return;
                        } catch (BindException conflict) {
                            // 每次失败均释放未绑定的 channel，成功的 socket 交由 Tomcat 持有。
                            doCloseServerSocket();
                            if (getUseInheritedChannel() || getUnixDomainSocketPath() != null
                                    || getPortWithOffset() <= 0 || getPortWithOffset() >= 65_535
                                    || (getAddress() != null && !getAddress().isAnyLocalAddress()
                                    && NetworkInterface.getByInetAddress(getAddress()) == null)) {
                                throw conflict;
                            }
                            setPort(getPort() + 1);
                        }
                    }
                }
            });
        }
    }
}
