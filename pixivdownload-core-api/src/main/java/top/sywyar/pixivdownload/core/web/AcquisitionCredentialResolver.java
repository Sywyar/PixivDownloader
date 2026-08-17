package top.sywyar.pixivdownload.core.web;

/**
 * 解析中立的浏览器采集凭据请求头，并在迁移期间兼容来源专用的旧请求头。
 */
public final class AcquisitionCredentialResolver {

    /**
     * 请求头名称常量。
     */
    public static final String HEADER_NAME = "X-Acquisition-Credential";
    /**
     * 允许的最大长度。
     */
    public static final int MAX_LENGTH = 16_384;

    private AcquisitionCredentialResolver() {
    }

    /**
     * 查询并返回对应结果。
     *
     * @param acquisitionCredential 采集凭证
     * @param legacyCredential 旧值凭证
     * @return 方法返回的字符串
     */
    public static String resolve(String acquisitionCredential, String legacyCredential) {
        String generic = normalize(acquisitionCredential);
        String legacy = normalize(legacyCredential);
        if (generic != null && legacy != null && !generic.equals(legacy)) {
            throw new IllegalArgumentException("Conflicting acquisition credential headers");
        }
        return generic != null ? generic : legacy;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Acquisition credential is too long");
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
