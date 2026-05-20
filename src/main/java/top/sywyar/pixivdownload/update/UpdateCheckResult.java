package top.sywyar.pixivdownload.update;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * /api/gui/update/check 的响应。
 */
@Data
@Builder(toBuilder = true)
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
    /** 是否为每夜版更新（而非正式发布）。 */
    private boolean nightly;
    /** 检查失败时的人类可读信息（成功时为空）。 */
    private String error;
    /**
     * 可选的每夜版替代选项。仅当 {@code update.check-nightly=true} 且 nightly 版本严格新于
     * 当前正式版（或当前正式版无更新但 nightly 有）时出现。嵌套对象本身的
     * {@code nightlyAlternative} 字段始终为 {@code null}。
     */
    private UpdateCheckResult nightlyAlternative;
}
