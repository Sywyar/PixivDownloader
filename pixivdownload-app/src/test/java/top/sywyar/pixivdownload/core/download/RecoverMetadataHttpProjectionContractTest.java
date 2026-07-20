package top.sywyar.pixivdownload.core.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.core.download.response.DownloadedResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("recover-metadata HTTP 投影契约")
class RecoverMetadataHttpProjectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("两阶段恢复请求应绑定 camelCase xRestrict，且不暴露全小写别名")
    void recoverMetadataBindsCamelCaseXRestrict() throws Exception {
        RecoverMetadataRequest request = objectMapper.readValue("""
                {"title":"t","authorId":7,"authorName":"u","xRestrict":2,"isAi":true,"description":"d"}
                """, RecoverMetadataRequest.class);

        assertThat(request.getXRestrict()).isEqualTo(2);
        assertThat(request.getIsAi()).isTrue();

        JsonNode json = objectMapper.valueToTree(request);
        assertThat(json.path("xRestrict").asInt()).isEqualTo(2);
        assertThat(json.has("xrestrict")).isFalse();
    }

    @Test
    @DisplayName("下载记录响应只输出 camelCase 年龄分级字段，不泄露全小写别名")
    void downloadedResponseUsesCanonicalAgeRatingFields() {
        DownloadedResponse response = DownloadedResponse.builder()
                .artworkId(1L)
                .xRestrict(2)
                .isAi(true)
                .build();

        JsonNode json = objectMapper.valueToTree(response);
        assertThat(json.path("xRestrict").asInt()).isEqualTo(2);
        assertThat(json.path("isAi").asBoolean()).isTrue();
        assertThat(json.has("xrestrict")).isFalse();
    }
}
