package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 元数据在实际导入时读取；不使用历史记录的 d 作为下载日期。 */
final class HistoryMetadata {
    private final PixivAjaxClient pixiv;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(3_000_000).build()).build());
    HistoryMetadata(PixivAjaxClient pixiv) { this.pixiv = pixiv; }

    WorkFileImportRequest fetch(HistoryRecords.Work work, Path root, String credential) throws IOException {
        boolean novel = work.key.type() == WorkType.NOVEL;
        JsonNode response;
        try {
            response = json.readTree(pixiv.get(URI.create("https://www.pixiv.net/ajax/"
                    + (novel ? "novel/" : "illust/") + work.key.id()), credential));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new HistoryRecords.Invalid("metadata");
        }
        if (response == null) throw new HistoryRecords.Invalid("metadata");
        JsonNode body = response.path("body");
        if (response.path("error").asBoolean(true) || !Long.toString(work.key.id()).equals(body.path("id").asText()))
            throw new HistoryRecords.Invalid("metadata");
        int type = novel ? 3 : integer(body, "illustType");
        int pages = novel ? 1 : integer(body, "pageCount");
        if (type < 0 || type > (novel ? 3 : 2) || work.bare != (type >= 2) || pages != work.files.size())
            throw new HistoryRecords.Invalid("incomplete");
        int ai = integer(body, "aiType");
        if (ai < 0 || ai > 2) throw new HistoryRecords.Invalid("metadata");
        JsonNode tags = body.path("tags").path("tags");
        if (!tags.isArray() || tags.size() > 256) throw new HistoryRecords.Invalid("metadata");
        List<WorkTag> values = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (!tag.path("tag").isTextual()) throw new HistoryRecords.Invalid("metadata");
            values.add(new WorkTag(null, tag.path("tag").textValue(), null));
        }
        if (!body.path("title").isTextual() || !body.path("userId").asText().matches("[1-9][0-9]{0,15}"))
            throw new HistoryRecords.Invalid("metadata");
        return new WorkFileImportRequest(work.key.type(), work.key.id(), body.path("title").textValue(), pages,
                integer(body, "xRestrict"), ai == 2, Long.parseLong(body.path("userId").asText()),
                text(body, "userName"), PixivDescriptionHtml.normalizeLinks(text(body, "description")),
                null, null, values, root, work.files.values().stream().map(root::resolve).toList(), novel ? text(body, "content") : null);
    }
    private static int integer(JsonNode body, String key) throws IOException {
        if (!body.path(key).isInt()) throw new HistoryRecords.Invalid("metadata");
        return body.path(key).intValue();
    }
    private static String text(JsonNode body, String key) {
        return body.path(key).isTextual() ? body.path(key).textValue() : null;
    }
}
