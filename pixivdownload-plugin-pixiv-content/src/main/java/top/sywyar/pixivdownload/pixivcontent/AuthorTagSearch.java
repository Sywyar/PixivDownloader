package top.sywyar.pixivdownload.pixivcontent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

import static top.sywyar.pixivdownload.pixivcontent.ContentQueries.*;

/** Pixiv exposes a per-author index for one tag. Use the smallest index to intersect several. */
final class AuthorTagSearch {
    private static final int MAX_TAGS = 10;
    private static final int BATCH = 48;
    private static final int MAX_CANDIDATES = 2048;

    record Page(int total, boolean hasNext, List<JsonNode> works) {}
    private record Batch(int total, JsonNode works) {}

    static Page search(ContentQueries content, long user, String kind, String[] rawTags,
                       int page, int size, String cookie) {
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : rawTags) {
            if (tag == null || tag.isBlank()) throw new HttpParameterException("query", "tag", "required");
            String name = normalized(tag.strip());
            if (name.codePoints().anyMatch(Character::isWhitespace))
                throw new HttpParameterException("query", "tag", "invalid_value");
            tags.add(name);
            if (tags.size() > MAX_TAGS) throw new HttpParameterException("query", "tag", "out_of_range");
        }
        if (tags.isEmpty()) throw new HttpParameterException("query", "tag", "required");
        long start = (page - 1L) * size;
        String endpoint = WEB + "/ajax/user/" + user + "/" + kind + "/tag";
        if (tags.size() == 1) {
            var batch = read(content, endpoint, tags.iterator().next(), start, size, cookie);
            var works = batch.works.valueStream().filter(item -> available(item, user)).toList();
            return new Page(batch.total, start + size < batch.total, works);
        }

        String seed = null;
        Batch first = null;
        for (String tag : tags) {
            var sample = read(content, endpoint, tag, 0, BATCH, cookie);
            if (first == null || sample.total < first.total) {
                first = sample;
                seed = tag;
            }
            if (first.total <= BATCH) break;
        }
        if (first.total > MAX_CANDIDATES) throw ContentFailure.tagSearchTooBroad();
        int matches = 0;
        var works = new ArrayList<JsonNode>();
        var seen = new HashSet<Long>();
        for (int offset = 0; offset < first.total; offset += BATCH) {
            var batch = offset == 0 ? first : read(content, endpoint, seed, offset, BATCH, cookie);
            if (batch.total != first.total) throw ContentFailure.invalidResponse();
            for (var item : batch.works) {
                if (!available(item, user)) continue;
                if (!seen.add(id(item.path("id")))) throw ContentFailure.invalidResponse();
                if (!NovelContentQueries.tags(item.path("tags")).stream()
                        .map(AuthorTagSearch::normalized).toList().containsAll(tags)) continue;
                if (matches >= start && works.size() < size) works.add(item);
                matches++;
            }
        }
        return new Page(matches, start + size < matches, works);
    }

    private static Batch read(ContentQueries content, String endpoint, String tag, long offset, int limit, String cookie) {
        var uri = UriComponentsBuilder.fromUriString(endpoint).queryParam("tag", "{tag}")
                .queryParam("offset", offset).queryParam("limit", limit).encode().buildAndExpand(tag).toUri();
        var result = content.body(uri, cookie);
        int total = nonnegative(result.path("total"));
        var works = array(result.path("works"));
        if (works.size() != Math.min(limit, Math.max(0L, total - offset))) throw ContentFailure.invalidResponse();
        return new Batch(total, works);
    }

    private static String normalized(String tag) {
        return Normalizer.normalize(tag, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean available(JsonNode work, long user) {
        if (work.path("isMasked").asBoolean(false) || work.path("isUnlisted").asBoolean(false)
                || work.path("isAdContainer").asBoolean(false)) return false;
        if (id(work.path("userId")) != user) throw ContentFailure.invalidResponse();
        return true;
    }
}
