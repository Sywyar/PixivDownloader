package top.sywyar.pixivdownload.download.request;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BatchStateRequest {
    @Schema(type = "object", implementation = java.util.Map.class,
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    private JsonNode state;
}
