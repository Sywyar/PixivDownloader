package top.sywyar.pixivdownload.novel.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 从服务端获取的 Pixiv 数据构造内部下载请求。 */
public final class NovelDownloadRequestFactory {

    private static final int MAX_RAW_METADATA_BYTES = 256 * 1024;

    private NovelDownloadRequestFactory() {
    }

    public static NovelDownloadRequest fromPixiv(
            PixivNovelMetadata metadata,
            PixivNovelMetadata.SeriesMetadata series,
            String cookie,
            String rawMetaJson) {
        NovelDownloadRequest request = new NovelDownloadRequest();
        request.setNovelId(metadata.novelId());
        request.setTitle(metadata.title());
        request.setCookie(cookie);
        request.setContent(metadata.content());

        NovelDownloadRequest.Other other = new NovelDownloadRequest.Other();
        other.setAuthorId(metadata.authorId());
        other.setAuthorName(metadata.authorName());
        other.setXRestrict(metadata.xRestrict());
        other.setAi(metadata.ai());
        other.setOriginal(metadata.original());
        other.setLanguage(metadata.language());
        other.setWordCount(metadata.wordCount());
        other.setTextLength(metadata.textLength());
        other.setReadingTimeSeconds(metadata.readingTimeSeconds());
        other.setPageCount(metadata.pageCount());
        other.setDescription(metadata.description());
        other.setTags(metadata.tags());
        other.setSeriesId(metadata.seriesId());
        other.setSeriesOrder(metadata.seriesOrder());
        other.setSeriesTitle(metadata.seriesTitle());
        other.setUploadTimestamp(metadata.uploadTimestamp());
        other.setCoverUrl(metadata.coverUrl());
        other.setEmbeddedImages(metadata.embeddedImages());
        other.setRawMetaJson(rawMetaJson);
        if (series != null) {
            other.setSeriesDescription(series.description());
            other.setSeriesCoverUrl(series.coverUrl());
            other.setSeriesTags(series.tags());
        }
        request.setOther(other);
        return request;
    }

    public static String boundedRawMetadata(ObjectMapper objectMapper, JsonNode body)
            throws JsonProcessingException {
        ObjectNode raw = body.deepCopy();
        raw.remove(List.of("content", "textEmbeddedImages"));
        String json = objectMapper.writeValueAsString(raw);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_METADATA_BYTES) {
            throw new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0);
        }
        return json;
    }
}
