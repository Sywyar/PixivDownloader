package top.sywyar.pixivdownload.update;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * /api/gui/update/check 的响应。
 */
@Data
@Builder
public class UpdateCheckResult {
    /** 是否启用了在线更新。 */
    private boolean enabled;
    /** 本次检查是否成功联网拿到清单。 */
    private boolean checkSucceeded;
    /** 是否存在可用的更新（版本号 > 当前 && 平台资源存在）。 */
    private boolean updateAvailable;
    /** 当前版本（解析自 AppVersion，可能为空）。 */
    private String currentVersion;
    /** 清单中的最新版本号。 */
    private String latestVersion;
    private String releaseDate;
    private String releaseNotes;
    private String releaseNotesUrl;
    /** 适用于当前平台的安装包 URL；无可用资源时为空。 */
    private String assetUrl;
    private long assetSizeBytes;
    private String assetSha256;
    /** 平台 key，例如 win-x64-installer。 */
    private String assetPlatform;
    /** 检查发生时间（epoch 毫秒）。 */
    private Instant checkedAt;
    /** 检查失败时的人类可读信息（成功时为空）。 */
    private String error;
}
