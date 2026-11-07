package top.sywyar.pixivdownload.download.request;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BatchStateRequest {
    private JsonNode state;
}
