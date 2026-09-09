package top.sywyar.pixivdownload.update;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * /api/gui/update/check 的响应。
 */
@Value
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
    /** 正式版检查失败的信息；独立验签成功的每夜版仍可作为替代选项。 */
    private String error;
    /**
     * 可选的每夜版替代选项。启用每夜版检查且发现可信更新时，若正式版检查失败，或每夜版
     * 严格新于已验证的最新正式版，则提供该选项。嵌套对象本身的
     * {@code nightlyAlternative} 字段始终为 {@code null}。
     */
    private UpdateCheckResult nightlyAlternative;
}
