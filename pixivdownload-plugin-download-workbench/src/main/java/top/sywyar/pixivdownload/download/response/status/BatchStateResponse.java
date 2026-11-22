package top.sywyar.pixivdownload.download.response.status;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BatchStateResponse {
    /** 原始 JSON 字符串，序列化时不再加引号 */
    @JsonRawValue
    @Schema(type = "object", implementation = java.util.Map.class,
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    private final String state;
}
