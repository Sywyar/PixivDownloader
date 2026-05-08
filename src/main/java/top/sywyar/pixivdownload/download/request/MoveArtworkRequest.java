package top.sywyar.pixivdownload.download.request;

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
}
