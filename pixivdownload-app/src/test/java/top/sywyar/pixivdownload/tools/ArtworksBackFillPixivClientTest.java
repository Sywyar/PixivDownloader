package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("作品回填 Pixiv 响应映射")
class ArtworksBackFillPixivClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("按状态码和错误文案区分限流、删除与年龄限制")
    void classifiesUnavailableResponses() throws Exception {
        assertEquals(
                ArtworksBackFillPixivClient.ResultType.RATE_LIMITED,
                ArtworksBackFillPixivClient.parseResponse(429, "", mapper).type
        );
        assertEquals(
                ArtworksBackFillPixivClient.ResultType.DELETED,
                ArtworksBackFillPixivClient.parseResponse(404, "", mapper).type
        );
        assertEquals(
                ArtworksBackFillPixivClient.ResultType.R18_ONLY,
                ArtworksBackFillPixivClient.parseResponse(
                        200,
                        "{\"error\":true,\"message\":\"R-18 restricted\"}",
                        mapper
                ).type
        );
        assertEquals(
                ArtworksBackFillPixivClient.ResultType.DELETED,
                ArtworksBackFillPixivClient.parseResponse(
                        200,
                        "{\"error\":true,\"message\":\"not found\"}",
                        mapper
                ).type
        );
    }

    @Test
    @DisplayName("成功响应提取作者、标签、AI 与系列元数据")
    void mapsSuccessfulResponse() throws Exception {
        ArtworksBackFillPixivClient.LookupResult result = ArtworksBackFillPixivClient.parseResponse(
                200,
                """
                        {
                          "error": false,
                          "body": {
                            "userId": "42",
                            "userName": "",
                            "xRestrict": 1,
                            "aiType": 2,
                            "description": "description",
                            "tags": {"tags": [
                              {"tag": "tag-a", "translation": {"en": "Tag A"}},
                              {"tag": "tag-b"}
                            ]},
                            "seriesNavData": {"seriesId": "7", "order": 3, "title": ""}
                          }
                        }
                        """,
                mapper
        );

        assertEquals(ArtworksBackFillPixivClient.ResultType.FOUND, result.type);
        assertEquals(42L, result.authorId);
        assertEquals("42", result.authorName);
        assertEquals(1, result.xRestrict);
        assertTrue(result.isAi);
        assertFalse(result.tags.isEmpty());
        assertEquals(new ArtworksBackFillPixivClient.TagEntry("tag-a", "Tag A"), result.tags.get(0));
        assertEquals(7L, result.seriesId);
        assertEquals("7", result.seriesTitle);
    }
}
