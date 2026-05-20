package top.sywyar.pixivdownload.update;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.AppVersion;

import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "update")
public class UpdateConfig {
    /**
     * 官方发布渠道的 update.json，由 {@code .github/workflows/release.yml} 在每次发布时
     * 一并上传到对应 release。利用 GitHub {@code /releases/latest/download/...} 重定向
     * 始终指向当前最新发布。
     */
    public static final String DEFAULT_MANIFEST_URL =
            "https://github.com/Sywyar/PixivDownload/releases/latest/download/update.json";
    public static final String DEFAULT_NIGHTLY_MANIFEST_URL =
            "https://github.com/Sywyar/PixivDownload/releases/download/nightly/update.json";

    private volatile boolean enabled = true;
    private volatile String manifestUrl = DEFAULT_MANIFEST_URL;
    private volatile String nightlyManifestUrl = DEFAULT_NIGHTLY_MANIFEST_URL;
    private volatile boolean autoCheck = true;
    /**
     * 是否检查每日构建版（nightly）更新；未配置（{@code null}）时按当前运行版本决定
     * —— 每日构建版默认开启，正式版默认关闭。
     */
    private volatile Boolean checkNightly;

    /** 计算 checkNightly 的有效值：显式配置优先，缺失时按当前版本派生。 */
    public boolean resolveCheckNightly() {
        Boolean configured = this.checkNightly;
        if (configured != null) {
            return configured;
        }
        return isCurrentVersionNightly();
    }

    /** 当前运行版本号是否为每日构建版。 */
    public static boolean isCurrentVersionNightly() {
        String version = AppVersion.getDisplayVersionOrNull();
        return version != null && version.toLowerCase(Locale.ROOT).contains("nightly");
    }
}
