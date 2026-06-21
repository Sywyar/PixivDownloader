package top.sywyar.pixivdownload.scripts;

import java.util.List;

/**
 * GET /api/scripts 的响应 DTO
 */
public record ScriptListResponse(List<ScriptItem> scripts, String detectedHost) {

    public record ScriptItem(
            String id,
            String displayName,
            String description,
            String version
    ) {
    }
}
