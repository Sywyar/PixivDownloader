package top.sywyar.pixivdownload.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.AppVersion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向 {@code /actuator/info} 贡献应用名称与版本。
 * <p>仅暴露已经公开（UI / 油猴脚本头部均可见）的版本信息，不包含任何配置或环境变量，
 * 因此可随 actuator info 端点对未登录访客开放。
 */
@Component
public class AppInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("name", AppInfo.NAME);
        app.put("version", AppVersion.getDisplayVersionOrDefault("unknown"));
        builder.withDetail("app", app);
    }
}
