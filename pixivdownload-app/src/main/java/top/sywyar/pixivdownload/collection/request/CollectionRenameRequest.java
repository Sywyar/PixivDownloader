package top.sywyar.pixivdownload.collection.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CollectionRenameRequest {
    @NotBlank(message = "{validation.collection.name.required}")
    @Schema(example = "Reading list")
    private String name;
}
