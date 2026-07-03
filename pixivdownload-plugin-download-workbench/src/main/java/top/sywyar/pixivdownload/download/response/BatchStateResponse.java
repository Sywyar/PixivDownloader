package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BatchStateResponse {
    /** 原始 JSON 字符串，序列化时不再加引号 */
    @JsonRawValue
    private final String state;
}
