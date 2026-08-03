package top.sywyar.pixivdownload.config;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

/**
 * 把 {@link InstallIdentity} 的安装身份标识以中性窄端口暴露给插件子 context。
 */
@Component
public class AppInstallIdentityProvider implements InstallIdentityProvider {

    @Override
    public String get() {
        return InstallIdentity.get();
    }
}
