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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("详情响应按实际字节限流，声明长度不能绕过限制")
    void boundsResponseBody(boolean knownLength) throws Exception {
        int limit = 4 * 1024 * 1024;
        byte[] body = new byte[limit];
        java.util.Arrays.fill(body, (byte) 'a');
        try (var entity = new org.apache.hc.core5.http.io.entity.InputStreamEntity(
                new java.io.ByteArrayInputStream(body), knownLength ? body.length : -1,
                org.apache.hc.core5.http.ContentType.APPLICATION_JSON)) {
            assertEquals(limit, ArtworksBackFillPixivClient.readResponse(entity).length());
        }
        byte[] oversized = new byte[limit + 1];
        try (var entity = new org.apache.hc.core5.http.io.entity.InputStreamEntity(
                new java.io.ByteArrayInputStream(oversized), knownLength ? oversized.length : -1,
                org.apache.hc.core5.http.ContentType.APPLICATION_JSON)) {
            assertThrows(java.io.IOException.class, () -> ArtworksBackFillPixivClient.readResponse(entity));
        }
    }

    @Test
    @DisplayName("小说日期按带时区日期解析，失败状态不被当作有效元数据")
    void parsesNovelResponse() throws Exception {
        var type = top.sywyar.pixivdownload.core.work.model.WorkType.NOVEL;
        var result = ArtworksBackFillPixivClient.parseResponse(200,
                "{\"body\":{\"userId\":42,\"uploadDate\":\"2020-02-03T12:00:00+09:00\"}}", mapper, type);
        assertEquals(1580698800000L, result.uploadTime);
        assertEquals(ArtworksBackFillPixivClient.ResultType.RATE_LIMITED,
                ArtworksBackFillPixivClient.parseResponse(429, "", mapper, type).type);
        assertEquals(ArtworksBackFillPixivClient.ResultType.DELETED,
                ArtworksBackFillPixivClient.parseResponse(404, "", mapper, type).type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"seriesNavData\":{}}", "{\"seriesNavData\":\"invalid\"}"})
    @DisplayName("不完整本地元数据不能将未知系列补为无系列")
    void incompleteLocalSeriesRemainsUnknown(String body) throws Exception {
        assertFalse(ArtworksBackFillPixivClient.parsePayload(new ObjectMapper().readTree(body), 1L, false).seriesKnown);
        assertTrue(ArtworksBackFillPixivClient.parsePayload(new ObjectMapper().readTree("{\"seriesNavData\":null}"), 1L, false).seriesKnown);
    }

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
        org.junit.jupiter.api.Assertions.assertNull(result.authorName);
        assertEquals(1, result.xRestrict);
        assertTrue(result.isAi);
        assertFalse(result.tags.isEmpty());
        assertEquals(new ArtworksBackFillPixivClient.TagEntry("tag-a", "Tag A"), result.tags.get(0));
        assertEquals(7L, result.seriesId);
        org.junit.jupiter.api.Assertions.assertNull(result.seriesTitle);
        assertEquals(3L, result.seriesOrder);
    }
}
