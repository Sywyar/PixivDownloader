package top.sywyar.pixivdownload.download.schedule.source.definition;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

import java.math.BigInteger;
import java.util.Set;

/** Pixiv 七类计划来源持久化定义的插件侧业务 schema。 */
public final class PixivScheduledDefinitionValidator {

    private static final String USER_NEW = "user-new";
    private static final String USER_REQUEST = "user-request";
    private static final String SEARCH = "search";
    private static final String SERIES = "series";
    private static final String MY_BOOKMARKS = "my-bookmarks";
    private static final String FOLLOW_LATEST = "follow-latest";
    private static final String COLLECTION = "collection";
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    private PixivScheduledDefinitionValidator() {
    }

    public static void validate(JsonNode root, String sourceType)
            throws ScheduledExecutionException {
        JsonNode source = root.get("source");
        if (source == null || !source.isObject()) {
            throw invalidDefinition();
        }
        switch (sourceType) {
            case USER_NEW, USER_REQUEST -> requirePositiveId(source.get("userId"));
            case SERIES -> requirePositiveId(source.get("seriesId"));
            case COLLECTION -> requirePositiveId(source.get("collectionId"));
            case SEARCH -> validateSearchSource(source);
            case MY_BOOKMARKS -> validateBookmarksSource(source);
            case FOLLOW_LATEST -> {
                if (!source.isEmpty()) {
                    throw invalidDefinition();
                }
            }
            default -> throw invalidDefinition();
        }
        validateCommonSnapshot(root);
    }

    private static void validateSearchSource(JsonNode source)
            throws ScheduledExecutionException {
        JsonNode word = source.get("word");
        if (word == null || !word.isTextual() || word.textValue().isBlank()) {
            throw invalidDefinition();
        }
        validateEnum(source, "order", Set.of("date_d", "date", "popular_d"));
        validateEnum(source, "mode", Set.of("all", "safe", "r18"));
        validateEnum(source, "sMode", Set.of("s_tag", "s_tc"));
        JsonNode rawMaxPages = source.get("maxPages");
        Integer maxPages = optionalInteger(rawMaxPages);
        if (hasNonBlankValue(rawMaxPages)
                && (maxPages == null || maxPages != -1 && maxPages < 1)) {
            throw invalidDefinition();
        }
    }

    private static void validateBookmarksSource(JsonNode source)
            throws ScheduledExecutionException {
        validateEnum(source, "rest", Set.of("show", "hide"));
    }

    private static void validateCommonSnapshot(JsonNode root)
            throws ScheduledExecutionException {
        JsonNode filters = root.get("filters");
        if (filters != null && !filters.isNull()) {
            if (!filters.isObject()) {
                throw invalidDefinition();
            }
            validateEnum(filters, "content", Set.of("all", "safe", "r18plus", "r18", "r18g"));
            validateEnum(filters, "aiFilter", Set.of("all", "exclude", "only"));
            validateEnum(filters, "typeFilter", Set.of("all", "illust", "manga", "ugoira"));
            validateTextArray(filters.get("tagsExact"));
            validateTextArray(filters.get("tagsFuzzy"));
            validateRange(filters, "pagesMin", "pagesMax", 1);
            validateRange(filters, "wordsMin", "wordsMax", 0);
            validateRange(filters, "bookmarksMin", "bookmarksMax", 0);
        }

        JsonNode download = root.get("download");
        if (download != null && !download.isNull()) {
            if (!download.isObject()) {
                throw invalidDefinition();
            }
            validateMinimum(download, "concurrent", 1);
            validateLongMinimum(download, "intervalMs", 0L);
            validateMinimum(download, "imageDelayMs", 0);
            JsonNode collectionId = download.get("collectionId");
            if (collectionId != null && !collectionId.isNull()
                    && !(collectionId.isTextual() && collectionId.textValue().isBlank())) {
                requirePositiveId(collectionId);
            }
        }

        JsonNode fetchLimit = root.get("fetchLimit");
        Integer parsedFetchLimit = optionalInteger(fetchLimit);
        if (fetchLimit != null && !fetchLimit.isNull()
                && !(fetchLimit.isTextual() && fetchLimit.textValue().isBlank())
                && (parsedFetchLimit == null || parsedFetchLimit < 0)) {
            throw invalidDefinition();
        }
    }

    private static void validateEnum(JsonNode object, String field, Set<String> allowed)
            throws ScheduledExecutionException {
        JsonNode value = object.get(field);
        if (value != null && !value.isNull()
                && (!value.isTextual() || !allowed.contains(value.textValue()))) {
            throw invalidDefinition();
        }
    }

    private static void validateTextArray(JsonNode value)
            throws ScheduledExecutionException {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray()) {
            throw invalidDefinition();
        }
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw invalidDefinition();
            }
        }
    }

    private static void validateRange(
            JsonNode object,
            String minimumField,
            String maximumField,
            int floor) throws ScheduledExecutionException {
        Integer minimum = optionalInteger(object.get(minimumField));
        Integer maximum = optionalInteger(object.get(maximumField));
        if (hasNonBlankValue(object.get(minimumField)) && minimum == null
                || hasNonBlankValue(object.get(maximumField)) && maximum == null
                || minimum != null && minimum < floor
                || maximum != null && maximum < floor
                || minimum != null && maximum != null && minimum > maximum) {
            throw invalidDefinition();
        }
    }

    private static void validateMinimum(JsonNode object, String field, int floor)
            throws ScheduledExecutionException {
        JsonNode raw = object.get(field);
        Integer value = optionalInteger(raw);
        if (hasNonBlankValue(raw) && (value == null || value < floor)) {
            throw invalidDefinition();
        }
    }

    private static void validateLongMinimum(JsonNode object, String field, long floor)
            throws ScheduledExecutionException {
        JsonNode raw = object.get(field);
        Long value = optionalLong(raw);
        if (hasNonBlankValue(raw) && (value == null || value < floor)) {
            throw invalidDefinition();
        }
    }

    private static Integer optionalInteger(JsonNode value) {
        if (value == null || value.isNull()
                || value.isTextual() && value.textValue().isBlank()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue().trim();
        if (!text.matches("-?[0-9]+")) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long optionalLong(JsonNode value) {
        if (value == null || value.isNull()
                || value.isTextual() && value.textValue().isBlank()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue().trim();
        if (!text.matches("-?[0-9]+")) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasNonBlankValue(JsonNode value) {
        return value != null && !value.isNull()
                && !(value.isTextual() && value.textValue().isBlank());
    }

    private static void requirePositiveId(JsonNode value)
            throws ScheduledExecutionException {
        if (value == null || value.isNull()) {
            throw invalidDefinition();
        }
        if (value.isIntegralNumber()) {
            if (!value.canConvertToLong()
                    || value.longValue() <= 0L
                    || value.longValue() > MAX_SAFE_JSON_INTEGER) {
                throw invalidDefinition();
            }
            return;
        }
        if (!value.isTextual()) {
            throw invalidDefinition();
        }
        String text = value.textValue().trim();
        if (!text.matches("[0-9]+")) {
            throw invalidDefinition();
        }
        try {
            BigInteger parsed = new BigInteger(text);
            if (parsed.signum() <= 0 || parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw invalidDefinition();
            }
        } catch (NumberFormatException ignored) {
            throw invalidDefinition();
        }
    }

    private static ScheduledExecutionException invalidDefinition() {
        return new ScheduledExecutionException(
                ScheduledFailure.Category.INVALID_DEFINITION,
                "schedule.pixiv.definition-business-invalid");
    }
}
