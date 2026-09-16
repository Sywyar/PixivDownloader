package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 管理员对待处理作品的一次性选择，绑定当前任务版本。 */
public record SchedulePendingResolveRequest(
        @Min(0) long expectedStateVersion,
        @NotBlank String workType,
        @NotBlank String workId,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String userAction,
        boolean rememberForRun) {
}
