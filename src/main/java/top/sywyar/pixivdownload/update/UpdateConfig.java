package top.sywyar.pixivdownload.update;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    private volatile boolean enabled = true;
    private volatile String manifestUrl = DEFAULT_MANIFEST_URL;
    private volatile boolean autoCheck = true;
}
