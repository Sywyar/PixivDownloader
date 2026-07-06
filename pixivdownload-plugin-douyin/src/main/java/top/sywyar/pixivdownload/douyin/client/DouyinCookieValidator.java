package top.sywyar.pixivdownload.douyin.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class DouyinCookieValidator {

    public static final List<String> REQUIRED_KEYS = List.of(
            "ttwid",
            "passport_csrf_token");

    public static final List<String> SESSION_KEYS = List.of(
            "sessionid",
            "sessionid_ss",
            "sid_tt",
            "sid_guard");

    public static final List<String> SUGGESTED_KEYS = List.of(
            "msToken",
            "odin_tt",
            "sid_guard",
            "sessionid",
            "sid_tt");

    private static final String SESSION_GROUP_LABEL = "sessionid / sessionid_ss / sid_tt / sid_guard";

    private DouyinCookieValidator() {
    }

    public static Validation validate(String cookie) {
        String value = cookie == null ? "" : cookie.trim();
        Map<String, String> fields = parse(value);
        List<String> missing = REQUIRED_KEYS.stream()
                .filter(key -> !hasValue(fields, key))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!value.isBlank() && SESSION_KEYS.stream().noneMatch(key -> hasValue(fields, key))) {
            missing.add(SESSION_GROUP_LABEL);
        }
        List<String> suggestedMissing = SUGGESTED_KEYS.stream()
                .filter(key -> !hasValue(fields, key))
                .toList();
        return new Validation(value.isBlank(), missing, suggestedMissing);
    }

    public static void ensureUsable(String cookie) throws DouyinClientException {
        Validation validation = validate(cookie);
        if (validation.empty()) {
            throw new DouyinClientException(DouyinClientErrorCode.COOKIE_REQUIRED,
                    "Douyin Cookie is required");
        }
        if (!validation.usable()) {
            throw new DouyinClientException(DouyinClientErrorCode.COOKIE_MISSING_FIELDS,
                    "Douyin Cookie is missing required fields: " + String.join(", ", validation.missingRequired()));
        }
    }

    private static Map<String, String> parse(String cookie) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (cookie == null || cookie.isBlank()) {
            return fields;
        }
        for (String part : cookie.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if (!key.isBlank()) {
                fields.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return fields;
    }

    private static boolean hasValue(Map<String, String> fields, String key) {
        String value = fields.get(key.toLowerCase(Locale.ROOT));
        return value != null && !value.isBlank();
    }

    public record Validation(boolean empty, List<String> missingRequired, List<String> missingSuggested) {

        public Validation {
            missingRequired = missingRequired == null ? List.of() : List.copyOf(missingRequired);
            missingSuggested = missingSuggested == null ? List.of() : List.copyOf(missingSuggested);
        }

        public boolean usable() {
            return !empty && missingRequired.isEmpty();
        }
    }
}
