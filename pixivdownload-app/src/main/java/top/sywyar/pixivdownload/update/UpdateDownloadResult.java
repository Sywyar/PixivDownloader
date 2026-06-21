package top.sywyar.pixivdownload.update;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateDownloadResult {
    /** 下载后的安装包绝对路径。 */
    private String installerPath;
    private long sizeBytes;
    private String sha256;
    private String version;
}
