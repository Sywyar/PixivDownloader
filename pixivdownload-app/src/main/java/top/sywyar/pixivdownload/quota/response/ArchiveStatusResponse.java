package top.sywyar.pixivdownload.quota.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArchiveStatusResponse {
    private final String token;
    private final @Schema(allowableValues = {"pending", "creating", "ready", "empty", "error", "cancelled", "expired"}) String status;
    private final Long expireSeconds;
    private final SourceDeletion sourceDeletion;

    /** 删除计数仅包含已经提交记录事务的作品。 */
    public record SourceDeletion(
            @Schema(allowableValues = {"not_requested", "pending", "running", "not_started", "succeeded", "failed", "partial"})
            String status, int requestedWorks, int deletedWorks) {}

}
