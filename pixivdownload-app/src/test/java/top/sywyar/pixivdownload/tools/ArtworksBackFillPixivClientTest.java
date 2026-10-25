package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("作品回填 Pixiv 响应映射")
class ArtworksBackFillPixivClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {301, 400})
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
    @ValueSource(ints = {401, 403, 407, 500, 502, 503, 504})
    @DisplayName("认证、代理和服务端故障停止整批，保留状态码")
    void stopsOnSharedHttpFailures(int status) {
        var failure = assertThrows(java.io.IOException.class,
                () -> ArtworksBackFillPixivClient.parseResponse(status, "{}", mapper));
        assertTrue(failure.getMessage().contains("HTTP " + status));
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

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR", "az-AZ"})
    @ResourceLock(Resources.LOCALE)
    @DisplayName("年龄限制错误的大小写匹配不受系统语言影响")
    void matchesRestrictionRegardlessOfDefaultLocale(String languageTag) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            var result = ArtworksBackFillPixivClient.parseResponse(
                    200, "{\"error\":true,\"message\":\"RESTRICTED\"}", mapper);
            assertEquals(ArtworksBackFillPixivClient.ResultType.R18_ONLY, result.type);
            assertEquals("RESTRICTED", result.message);
        } finally {
            Locale.setDefault(previous);
        }
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
