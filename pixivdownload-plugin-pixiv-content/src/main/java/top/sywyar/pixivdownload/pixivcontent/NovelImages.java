package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.pixivcontent.NovelContentModels.NovelImage;

import static top.sywyar.pixivdownload.pixivcontent.ContentQueries.*;
import static top.sywyar.pixivdownload.pixivcontent.NovelContentQueries.novelPath;

/** Resolves uploaded images and the artwork pages their creators have approved for insertion. */
final class NovelImages {
    private static final Pattern MARKER = Pattern.compile("\\[(uploadedimage|pixivimage):([1-9][0-9]{0,18})(?:-([1-9][0-9]{0,9}))?\\]");
    // Pixiv permits 30 uploaded illustrations and 300 artwork references per novel.
    private static final int MAX_IMAGES = 330;
    private static final int CITATIONS_PER_REQUEST = 30;

    private NovelImages() {}

    static List<NovelImage> resolve(ContentQueries content, long novelId, String raw, JsonNode info, String cookie) {
        var byMarker = new LinkedHashMap<String, NovelImage>();
        var citations = new LinkedHashMap<String, NovelImage>();
        var matcher = MARKER.matcher(raw);
        while (matcher.find()) {
            String marker = matcher.group();
            if (byMarker.containsKey(marker)) continue;
            long id;
            int page;
            try {
                id = Long.parseLong(matcher.group(2));
                page = matcher.group(3) == null ? 1 : Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException invalidMarker) {
                continue;
            }
            NovelImage image;
            if ("uploadedimage".equals(matcher.group(1))) {
                if (matcher.group(3) != null || uploadEntry(info, id).isMissingNode()) continue;
                image = new NovelImage(marker, "uploaded", id, null, novelPath(novelId) + "/images/" + id);
            } else {
                image = new NovelImage(marker, "artwork", id, page - 1,
                        PixivContentPlugin.API + "/artworks/" + id + "/pages/" + (page - 1) + "/image");
                String citation = matcher.group(2) + (matcher.group(3) == null ? "" : "-" + matcher.group(3));
                citations.put(citation, image);
            }
            byMarker.put(marker, image);
            if (byMarker.size() > MAX_IMAGES) throw ContentFailure.tooLarge();
        }
        if (citations.isEmpty()) return List.copyOf(byMarker.values());
        var ids = List.copyOf(citations.keySet());
        Set<String> approved = new HashSet<>();
        for (int from = 0; from < ids.size(); from += CITATIONS_PER_REQUEST) {
            var batch = ids.subList(from, Math.min(from + CITATIONS_PER_REQUEST, ids.size()));
            var uri = UriComponentsBuilder.fromUriString(WEB + "/ajax/novel/" + novelId + "/insert_illusts");
            for (String key : batch) uri.queryParam("id[]", key);
            var result = content.body(uri.build().encode().toUri(), cookie);
            if (!result.isObject() && !(result.isArray() && result.isEmpty())) throw ContentFailure.invalidResponse();
            for (String key : batch) {
                var entry = result.path(key);
                if (!entry.path("visible").isBoolean() || !entry.path("visible").asBoolean()) continue;
                var image = citations.get(key);
                if (id(entry.path("id")) != image.id() || positive(entry.path("page")) - 1 != image.pageIndex())
                    throw ContentFailure.invalidResponse();
                approved.add(image.marker());
            }
        }
        var images = new ArrayList<NovelImage>();
        for (var image : byMarker.values()) {
            if (image.kind().equals("uploaded") || approved.contains(image.marker())) images.add(image);
        }
        return images;
    }

    static URI uploaded(JsonNode info, long imageId) {
        if (!text(info, "content").contains("[uploadedimage:" + imageId + "]")) throw ContentFailure.notFound();
        var entry = uploadEntry(info, imageId);
        if (entry.isMissingNode() || entry.isNull()) throw ContentFailure.notFound();
        return cdnUri(text(entry.path("urls"), "original"));
    }

    private static JsonNode uploadEntry(JsonNode info, long imageId) {
        var entry = info.path("textEmbeddedImages").path(Long.toString(imageId));
        if (!entry.isMissingNode() && id(entry.path("novelImageId")) != imageId) throw ContentFailure.invalidResponse();
        return entry;
    }
}
