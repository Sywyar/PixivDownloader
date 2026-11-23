package top.sywyar.pixivdownload.core.web;

/**
 * 解析浏览器采集凭据请求头。
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

    /** 操作需要可识别的采集账户时使用的机器码。 */
    public static final String REQUIRED_CODE = "ACQUISITION_CREDENTIAL_REQUIRED";

    private AcquisitionCredentialResolver() {
    }

    /**
     * 查询并返回对应结果。
     *
     * @param acquisitionCredential 采集凭证
     * @return 方法返回的字符串
     */
    public static String resolve(String acquisitionCredential) {
        if (acquisitionCredential == null) {
            return null;
        }
        String normalized = acquisitionCredential.trim();
        if (normalized.length() > MAX_LENGTH) {
            throw new HttpParameterException("header", HEADER_NAME, "out_of_range");
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
