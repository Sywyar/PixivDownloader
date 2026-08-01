package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 作品进入异步通知时的安全展示投影。展示名称保持 namespace 与 i18n key 分离；引用地址只能是
 * 不携带凭证材料的 HTTPS 绝对地址。插件不提供展示信息时使用 {@link #empty()}。
 */
public record ScheduledWorkNotificationPresentation(
        String displayNamespace,
        String displayNameKey,
        String referenceUrl
) {

    public static final int MAX_DISPLAY_NAMESPACE_BYTES = 64;
    public static final int MAX_DISPLAY_NAME_KEY_BYTES = 192;
    public static final int MAX_REFERENCE_URL_BYTES = 4_096;

    private static final int MAX_PERCENT_DECODE_ROUNDS = 16;

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern I18N_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,191}");

    public ScheduledWorkNotificationPresentation {
        displayNamespace = normalizeToken(
                displayNamespace, NAMESPACE, "display namespace");
        displayNameKey = normalizeToken(
                displayNameKey, I18N_KEY, "display name key");
        if ((displayNamespace == null) != (displayNameKey == null)) {
            throw new IllegalArgumentException(
                    "display namespace and display name key must be provided together");
        }
        referenceUrl = normalizeReferenceUrl(referenceUrl);
    }

    public static ScheduledWorkNotificationPresentation empty() {
        return new ScheduledWorkNotificationPresentation(null, null, null);
    }

    private static String normalizeToken(
            String value,
            Pattern pattern,
            String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        String normalized = value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is not a valid presentation token");
        }
        return normalized;
    }

    private static String normalizeReferenceUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException(
                    "notification reference URL must not contain control characters");
        }
        String normalized = value.trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_REFERENCE_URL_BYTES) {
            throw new IllegalArgumentException("notification reference URL exceeds size limit");
        }
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("notification reference URL is invalid", exception);
        }
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "notification reference URL must be an absolute HTTPS URL with a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "notification reference URL must not contain user info");
        }

        validateDecodedComponent(uri.getRawPath());
        validateDecodedComponent(uri.getRawQuery());
        validateDecodedComponent(uri.getRawFragment());
        validateQuery(uri.getRawQuery());
        return normalized;
    }

    private static void validateDecodedComponent(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return;
        }
        String decoded = rawValue;
        for (int round = 0; ; round++) {
            if (containsControlCharacter(decoded)) {
                throw new IllegalArgumentException(
                        "notification reference URL must not contain encoded control characters");
            }
            if (!hasPercentEscape(decoded)) {
                if (ScheduledCredentialText.containsCredentialMaterial(decoded)) {
                    throw new IllegalArgumentException(
                            "notification reference URL must not contain encoded credential material");
                }
                return;
            }
            if (round >= MAX_PERCENT_DECODE_ROUNDS) {
                throw new IllegalArgumentException(
                        "notification reference URL percent encoding is too deeply nested");
            }
            decoded = decodePercentEncoded(decoded);
        }
    }

    private static void validateQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return;
        }
        for (String parameter : rawQuery.split("[&;]", -1)) {
            int separator = parameter.indexOf('=');
            validateQueryParameter(
                    separator < 0 ? parameter : parameter.substring(0, separator),
                    separator < 0 ? "" : parameter.substring(separator + 1));
        }
    }

    private static void validateQueryParameter(String rawName, String rawValue) {
        String name = rawName;
        String value = rawValue;
        for (int round = 0; ; round++) {
            if (containsControlCharacter(name) || containsControlCharacter(value)) {
                throw new IllegalArgumentException(
                        "notification reference URL query must not contain control characters");
            }
            if (ScheduledSensitiveFieldNames.isSensitiveFieldName(name)
                    || (ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(name)
                    && !ScheduledSensitiveFieldNames.isSafeMetadataValue(name, value)
                    && !hasPercentEscape(value))) {
                throw new IllegalArgumentException(
                        "notification reference URL query name is sensitive");
            }
            if (ScheduledCredentialText.containsCredentialMaterial(name)
                    || ScheduledCredentialText.containsCredentialMaterial(value)) {
                throw new IllegalArgumentException(
                        "notification reference URL query contains credential material");
            }

            boolean encodedName = hasPercentEscape(name);
            boolean encodedValue = hasPercentEscape(value);
            if (!encodedName && !encodedValue) {
                return;
            }
            if (round >= MAX_PERCENT_DECODE_ROUNDS) {
                throw new IllegalArgumentException(
                        "notification reference URL query encoding is too deeply nested");
            }
            if (encodedName) {
                name = decodePercentEncoded(name);
            }
            if (encodedValue) {
                value = decodePercentEncoded(value);
            }
        }
    }

    private static String decodePercentEncoded(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            int codePoint = value.codePointAt(index);
            byte[] encoded = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(encoded);
            index += Character.charCount(codePoint);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "notification reference URL contains invalid UTF-8 encoding", exception);
        }
    }

    private static boolean hasPercentEscape(String value) {
        for (int index = 0; index + 2 < value.length(); index++) {
            if (value.charAt(index) == '%'
                    && Character.digit(value.charAt(index + 1), 16) >= 0
                    && Character.digit(value.charAt(index + 2), 16) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
