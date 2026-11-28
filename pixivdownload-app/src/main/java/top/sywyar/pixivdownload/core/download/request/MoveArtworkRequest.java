package top.sywyar.pixivdownload.core.download.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveArtworkRequest {
    @NotBlank(message = "{validation.move.path.required}")
    private String movePath;

    /** Move timestamp, in epoch milliseconds. */
    @NotNull(message = "{validation.move.time.required}")
    private Long moveTime;

    /** 可选的目标根目录，用于让同一根目录下的移动记录共用路径前缀。 */
    private String classifierTargetFolder;
}
