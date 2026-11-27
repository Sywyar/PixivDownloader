package top.sywyar.pixivdownload.quota.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 管理员压缩任务列表（仅未过期任务，按创建时间倒序）。 */
public record AdminArchiveTasksResponse(List<Task> tasks) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(
            String token,
            @Schema(allowableValues = {"pending", "creating", "ready", "empty", "error", "cancelled"}) String status,
            String exportType,
            int workCount,
            int processedWorks,
            int fileCount,
            long createdTime,
            long expireSeconds,
            ArchiveStatusResponse.SourceDeletion sourceDeletion
    ) {
    }
}
