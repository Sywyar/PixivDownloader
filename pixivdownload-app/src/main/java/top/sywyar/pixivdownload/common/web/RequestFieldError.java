package top.sywyar.pixivdownload.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** An input error with a machine-readable correction location. */
public record RequestFieldError(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "JSON Pointer to the invalid request field.") String field,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, examples = {"unknown_property", "duplicate", "invalid_format", "invalid_value", "not_applicable", "out_of_range", "too_large", "unsupported"}, description = "Validation keyword or provider reason. Providers may return other values.") String reason)
        implements ApiErrorResponse {
    @com.fasterxml.jackson.annotation.JsonProperty("in")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(allowableValues = "body", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String in() { return field == null ? null : "body"; }
}
