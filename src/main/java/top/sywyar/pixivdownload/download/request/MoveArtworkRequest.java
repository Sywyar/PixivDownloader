package top.sywyar.pixivdownload.download.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveArtworkRequest {
    @NotBlank(message = "移动路径不能为空")
    private String movePath;

    @NotNull(message = "移动时间不能为空")
    private Long moveTime;
}
