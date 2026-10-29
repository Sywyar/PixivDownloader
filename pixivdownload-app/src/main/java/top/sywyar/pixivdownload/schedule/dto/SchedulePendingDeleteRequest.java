package top.sywyar.pixivdownload.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 用复合作品身份清除一条待重试记录；字符串按 JSON 原样传输，不经过 URL path 解码。 */
@Data
public class SchedulePendingDeleteRequest {

    @NotBlank
    private String workType;

    @NotBlank
    private String workId;
}
