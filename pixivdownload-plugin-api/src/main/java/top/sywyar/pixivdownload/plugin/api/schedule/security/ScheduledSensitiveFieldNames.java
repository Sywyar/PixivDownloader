package top.sywyar.pixivdownload.plugin.api.schedule.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 计划任务稳定契约共用的纯 JDK 敏感字段名判定，只识别跨来源通用语义；
 * 来源 owner 仍须在自己的编解码边界补充专属字段名校验。
 */
public final class ScheduledSensitiveFieldNames {

    private static final String COUNT_SUFFIX = "count";
    private static final Set<String> BOOLEAN_METADATA_SUFFIXES = Set.of(
            "required",
            "present",
            "bound",
            "dependent",
            "enabled");
    private static final List<String> METADATA_SUFFIXES = List.of(
            "required",
            "present",
            "bound",
            "dependent",
            "enabled",
            COUNT_SUFFIX,
            "algorithm",
            "mode",
            "type",
            "version");
    private static final Set<String> SENSITIVE_METADATA_TRAILERS = Set.of(
            "value",
            "header");
    private static final Pattern NON_NEGATIVE_COUNT =
            Pattern.compile("(?:0|[1-9][0-9]{0,18})");
    private static final Pattern SEPARATED_SID = Pattern.compile(
            "(?i)(?:^|[._-])sid(?:[._-]?(?:value|header)){0,2}$");
    private static final Pattern CAMEL_CASE_SID = Pattern.compile(
            ".*S(?i:id(?:(?:value|header)){0,2})$");

    private ScheduledSensitiveFieldNames() {
    }

    /**
     * 判断字段名是否声明 Cookie、会话、token、凭证、口令、secret、签名或临时地址材料。
     * 分隔符与大小写不影响判定，例如 {@code refresh_token}、{@code session_id} 与
     * {@code proxy-authorization} 都会被拒绝。
     *
     * @param fieldName 字段名称
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isSensitiveFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String trimmed = fieldName.trim();
        SensitiveFieldStructure structure = sensitiveFieldStructure(trimmed);
        return structure != null && !structure.isSafeMetadata();
    }

    /**
     * 判断字段名是否是允许以严格非敏感值表达的凭证元数据。
     * 这类字段仍带有敏感语义，调用方必须同时用 {@link #isSafeMetadataValue(String, String)}
     * 校验对应值，不能只凭后缀放行。
     *
     * @param fieldName 字段名称
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isSensitiveMetadataFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        SensitiveFieldStructure structure =
                sensitiveFieldStructure(fieldName.trim());
        return structure != null && structure.isSafeMetadata();
    }

    /**
     * 校验敏感元数据的值：计数只接受有界非负十进制整数，状态只接受布尔字面量。
     *
     * @param fieldName 字段名称
     * @param value 值
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isSafeMetadataValue(String fieldName, String value) {
        if (value == null || fieldName == null || fieldName.isBlank()) {
            return false;
        }
        SensitiveFieldStructure structure =
                sensitiveFieldStructure(fieldName.trim());
        if (structure == null || !structure.isSafeMetadata()) {
            return false;
        }
        String suffix = structure.metadataSuffix();
        String normalizedValue = value.trim();
        if (COUNT_SUFFIX.equals(suffix)) {
            return NON_NEGATIVE_COUNT.matcher(normalizedValue).matches();
        }
        return suffix != null
                && BOOLEAN_METADATA_SUFFIXES.contains(suffix)
                && ("true".equalsIgnoreCase(normalizedValue)
                || "false".equalsIgnoreCase(normalizedValue));
    }

    private static SensitiveFieldStructure sensitiveFieldStructure(
            String fieldName) {
        String remaining = fieldName;
        int depth = 0;
        int metadataDepth = 0;
        boolean hasTrailer = false;
        String metadataSuffix = null;
        while (true) {
            SuffixMatch metadata = stripMetadataSuffix(remaining);
            if (metadata != null) {
                metadataSuffix = metadata.suffix();
                metadataDepth++;
                depth++;
                remaining = metadata.base();
                continue;
            }
            SuffixMatch trailer = stripSensitiveTrailer(remaining);
            if (trailer != null) {
                hasTrailer = true;
                depth++;
                remaining = trailer.base();
                continue;
            }
            String normalizedBase = remaining.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            return hasSensitiveSemantic(remaining, normalizedBase)
                    ? new SensitiveFieldStructure(
                    metadataSuffix, depth, metadataDepth, hasTrailer)
                    : null;
        }
    }

    private static SuffixMatch stripMetadataSuffix(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String suffix : METADATA_SUFFIXES) {
            if (!normalized.endsWith(suffix) || normalized.length() == suffix.length()) {
                continue;
            }
            String base = fieldName.replaceFirst(metadataSuffixPattern(suffix), "");
            if (!base.equals(fieldName) && !base.isBlank()) {
                return new SuffixMatch(suffix, base);
            }
        }
        return null;
    }

    private static SuffixMatch stripSensitiveTrailer(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        for (String trailer : SENSITIVE_METADATA_TRAILERS) {
            if (!normalized.endsWith(trailer)
                    || normalized.length() == trailer.length()) {
                continue;
            }
            String base = fieldName.replaceFirst(
                    metadataSuffixPattern(trailer), "");
            if (!base.equals(fieldName) && !base.isBlank()) {
                return new SuffixMatch(trailer, base);
            }
        }
        return null;
    }

    private static String metadataSuffixPattern(String suffix) {
        StringBuilder pattern = new StringBuilder("(?i)[^A-Za-z0-9]*");
        for (int index = 0; index < suffix.length(); index++) {
            pattern.append(Pattern.quote(String.valueOf(suffix.charAt(index))));
            if (index + 1 < suffix.length()) {
                pattern.append("[^A-Za-z0-9]*");
            }
        }
        return pattern.append("[^A-Za-z0-9]*$").toString();
    }

    private static boolean hasSensitiveSemantic(String fieldName, String normalized) {
        return endsWithSensitiveSemantic(normalized, "cookie")
                || endsWithSensitiveSemantic(normalized, "cookiejar")
                || endsWithSensitiveSemantic(normalized, "authorization")
                || endsWithSensitiveSemantic(normalized, "credential")
                || endsWithSensitiveSemantic(normalized, "password")
                || endsWithSensitiveSemantic(normalized, "passwd")
                || endsWithSensitiveSemantic(normalized, "secret")
                || endsWithSensitiveSemantic(normalized, "token")
                || endsWithSensitiveSemantic(normalized, "apikey")
                || endsWithSensitiveSemantic(normalized, "signature")
                || endsWithSensitiveSemantic(normalized, "session")
                || endsWithSensitiveSemantic(normalized, "sessionkey")
                || endsWithSensitiveSemantic(normalized, "sessionid")
                || endsWithSensitiveSemantic(normalized, "sessid")
                || endsWithSensitiveSemantic(normalized, "signedurl")
                || endsWithSensitiveSemantic(normalized, "temporaryurl")
                || endsWithSensitiveSemantic(normalized, "auth")
                || normalized.endsWith("rememberme")
                || isSidFieldName(fieldName)
                || endsWithSensitiveSemantic(normalized, "sig");
    }

    private static boolean isSidFieldName(String fieldName) {
        return SEPARATED_SID.matcher(fieldName).find()
                || CAMEL_CASE_SID.matcher(fieldName).matches();
    }

    private static boolean endsWithSensitiveSemantic(String normalized, String semantic) {
        return normalized.endsWith(semantic)
                || normalized.endsWith(semantic + "value")
                || normalized.endsWith(semantic + "header");
    }

    private record SensitiveFieldStructure(
            String metadataSuffix,
            int depth,
            int metadataDepth,
            boolean hasTrailer) {

        private boolean isSafeMetadata() {
            return depth == 1
                    && metadataDepth == 1
                    && !hasTrailer
                    && (COUNT_SUFFIX.equals(metadataSuffix)
                    || BOOLEAN_METADATA_SUFFIXES.contains(metadataSuffix));
        }
    }

    private record SuffixMatch(String suffix, String base) {
    }
}
