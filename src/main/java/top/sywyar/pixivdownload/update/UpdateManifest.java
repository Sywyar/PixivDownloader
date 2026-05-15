package top.sywyar.pixivdownload.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 远端 update.json 的反序列化结构。
 * <pre>
 * {
 *   "latestVersion": "1.2.3",
 *   "releaseDate": "2026-05-15",
 *   "releaseNotes": "支持自动更新...",
 *   "releaseNotesUrl": "https://example.com/changelog#1-2-3",
 *   "assets": {
 *     "win-x64-installer": {
 *       "url": "https://example.com/PixivDownload-1.2.3-win-x64-setup.exe",
 *       "sha256": "abc...",
 *       "sizeBytes": 12345678
 *     }
 *   }
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateManifest {
    private String latestVersion;
    private String releaseDate;
    private String releaseNotes;
    private String releaseNotesUrl;
    private Map<String, Asset> assets;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Asset {
        private String url;
        private String sha256;
        private long sizeBytes;
    }
}
