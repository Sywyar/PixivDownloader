package top.sywyar.pixivdownload.collection.request;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
public class CollectionDownloadRootRequest {
    @Schema(example = "albums/{collection_name}")
    @HttpApiNullable
    private String downloadRoot;
}
