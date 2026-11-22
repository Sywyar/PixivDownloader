package top.sywyar.pixivdownload.collection.request;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CollectionCreateRequest {
    @NotBlank(message = "{validation.collection.name.required}")
    @Schema(example = "Reading list")
    private String name;
    @Schema(example = "albums/{collection_name}")
    @HttpApiNullable
    private String downloadRoot;
}
