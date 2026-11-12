package top.sywyar.pixivdownload.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

/** 开发实例使用有效访问端口隔离 Cookie；普通运行保留原名。 */
@UtilityClass
public class InstanceCookies {

    public static String name(HttpServletRequest request, String original) {
        // 代理来源已由 TrustedForwardedRequestFilter 校验；不重读代理头或配置端口。
        return PluginDevelopmentArtifacts.enabled()
                ? "pixiv_dev_" + request.getServerPort() + "_" + original
                : original;
    }
}
