package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("作品回填 Pixiv 响应映射")
class ArtworksBackFillPixivClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 403, 407, 500, 502, 503})
    @DisplayName("HTTP 失败不被当作成功作品或永久不可达记录")
    void rejectsFailedHttpResponses(int status) throws Exception {
        for (String body : new String[]{"{}", "<html>Bad gateway</html>",
                "{\"error\":true,\"message\":\"not found\"}"}) {
            var result = ArtworksBackFillPixivClient.parseResponse(status, body, mapper);
            assertEquals(ArtworksBackFillPixivClient.ResultType.SKIP, result.type);
            assertTrue(result.message.contains(Integer.toString(status)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "{\"error\":false}",
            "{\"body\":null}", "{\"body\":[]}", "{\"body\":{}}",
            "{\"body\":{\"message\":\"upstream unavailable\"}}"})
    @DisplayName("成功状态下没有作品数据的响应也不能用于回填")
    void rejectsMissingArtworkPayload(String body) throws Exception {
        assertEquals(ArtworksBackFillPixivClient.ResultType.SKIP,
                ArtworksBackFillPixivClient.parseResponse(200, body, mapper).type);
    }

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
