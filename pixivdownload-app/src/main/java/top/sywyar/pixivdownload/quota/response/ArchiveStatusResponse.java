package top.sywyar.pixivdownload.quota.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArchiveStatusResponse {
    private final String token;
    private final String status;
    private final Long expireSeconds;
}
