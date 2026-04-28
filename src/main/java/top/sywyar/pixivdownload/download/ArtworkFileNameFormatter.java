package top.sywyar.pixivdownload.download;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArtworkFileNameFormatter {

    public static final String DEFAULT_TEMPLATE = "{artwork_id}_p{page}";
    public static final long DEFAULT_TEMPLATE_ID = 1L;

    private static final int MAX_BASENAME_LENGTH = 180;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
            "\\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\\+?|R18\\+?)}");
    private static final Pattern INVALID_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern TRAILING_DOTS_AND_SPACES = Pattern.compile("[. ]+$");
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private ArtworkFileNameFormatter() {}

    public static String normalizeTemplate(String template) {
        return template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
    }

    public static List<String> formatAll(String template,
                                         long artworkId,
                                         String artworkTitle,
                                         Long authorId,
                                         String authorName,
                                         long timestamp,
                                         int count,
                                         Boolean isAi,
                                         Integer xRestrict) {
        int safeCount = Math.max(1, count);
        List<String> names = new ArrayList<>(safeCount);
        for (int page = 0; page < safeCount; page++) {
            names.add(format(template, artworkId, artworkTitle, authorId, authorName,
                    timestamp, page, safeCount, isAi, xRestrict));
        }
        return ensureUnique(names);
    }

    public static List<String> normalizeProvidedBaseNames(List<String> baseNames, int count, long artworkId) {
        int safeCount = Math.max(1, count);
        if (baseNames == null || baseNames.size() < safeCount) {
            return List.of();
        }
        List<String> names = new ArrayList<>(safeCount);
        for (int page = 0; page < safeCount; page++) {
            names.add(normalizeBaseName(baseNames.get(page), fallbackBaseName(artworkId, page)));
        }
        return ensureUnique(names);
    }

    public static String normalizeBaseName(String value, String fallback) {
        String cleaned = sanitize(value);
        if (cleaned.isBlank()) {
            cleaned = sanitize(fallback);
        }
        if (cleaned.isBlank()) {
            cleaned = "untitled";
        }
        return limitLength(cleaned, MAX_BASENAME_LENGTH);
    }

    private static String format(String template,
                                 long artworkId,
                                 String artworkTitle,
                                 Long authorId,
                                 String authorName,
                                 long timestamp,
                                 int page,
                                 int count,
                                 Boolean isAi,
                                 Integer xRestrict) {
        String normalizedTemplate = normalizeTemplate(template);
        Matcher matcher = VARIABLE_PATTERN.matcher(normalizedTemplate);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolveVariable(
                    matcher.group(1), artworkId, artworkTitle, authorId, authorName,
                    timestamp, page, count, isAi, xRestrict)));
        }
        matcher.appendTail(buffer);
        return normalizeBaseName(buffer.toString(), fallbackBaseName(artworkId, page));
    }

    private static String resolveVariable(String variable,
                                          long artworkId,
                                          String artworkTitle,
                                          Long authorId,
                                          String authorName,
                                          long timestamp,
                                          int page,
                                          int count,
                                          Boolean isAi,
                                          Integer xRestrict) {
        boolean ai = Boolean.TRUE.equals(isAi);
        int restrict = xRestrict == null ? 0 : xRestrict;
        return switch (variable) {
            case "artwork_id" -> String.valueOf(artworkId);
            case "artwork_title" -> sanitize(artworkTitle);
            case "author_id" -> authorId == null ? "" : String.valueOf(authorId);
            case "author_name" -> sanitize(authorName);
            case "timestamp" -> String.valueOf(timestamp);
            case "page" -> String.valueOf(page);
            case "count" -> String.valueOf(count);
            case "ai" -> ai ? "AI" : "";
            case "ai+" -> ai ? "AI" : "Human";
            case "R18" -> restrict == 2 ? "R18G" : restrict == 1 ? "R18" : "";
            case "R18+" -> restrict == 2 ? "R18G" : restrict == 1 ? "R18" : "SFW";
            default -> "";
        };
    }

    private static List<String> ensureUnique(List<String> names) {
        List<String> result = new ArrayList<>(names.size());
        Map<String, Integer> baseCounts = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (int page = 0; page < names.size(); page++) {
            String base = names.get(page);
            String candidate = base;
            String baseKey = key(base);
            int duplicate = baseCounts.getOrDefault(baseKey, 0);
            if (duplicate > 0 || used.contains(baseKey)) {
                int suffixIndex = 1;
                String candidateKey;
                do {
                    String suffix = "_p" + page + (suffixIndex > 1 ? "_" + suffixIndex : "");
                    candidate = appendSuffix(base, suffix);
                    candidateKey = key(candidate);
                    suffixIndex++;
                } while (used.contains(candidateKey));
            }
            baseCounts.put(baseKey, duplicate + 1);
            used.add(key(candidate));
            result.add(candidate);
        }
        return result;
    }

    private static String appendSuffix(String base, String suffix) {
        int maxBaseLength = Math.max(1, MAX_BASENAME_LENGTH - suffix.length());
        return limitLength(base, maxBaseLength) + suffix;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = INVALID_FILE_NAME_CHARS.matcher(value).replaceAll("_").trim();
        cleaned = TRAILING_DOTS_AND_SPACES.matcher(cleaned).replaceAll("");
        if (WINDOWS_RESERVED_NAMES.contains(cleaned.toUpperCase(Locale.ROOT))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    private static String limitLength(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String fallbackBaseName(long artworkId, int page) {
        return artworkId + "_p" + page;
    }
}
