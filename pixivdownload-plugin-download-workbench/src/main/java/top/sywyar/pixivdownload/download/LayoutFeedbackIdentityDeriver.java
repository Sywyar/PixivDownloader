package top.sywyar.pixivdownload.download;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 布局偏好调查的调查作用域匿名身份派生。
 *
 * <p>controller 只向浏览器 / PostHog / 状态 API 下发本类派生的 scoped ID，绝不暴露
 * {@code data/install_identity.txt} 的原始安装 UUID。派生输入是命名空间、Survey ID 与
 * 安装身份的组合，输出固定为 {@code plf_<64 位小写 hex>}：
 *
 * <pre>
 * input  = "pixivdownload:download-workbench:layout-feedback:v1" + '\0' + surveyId + '\0' + installIdentity
 * digest = SHA-256(UTF-8(input))
 * output = "plf_" + hex(digest)
 * </pre>
 *
 * <p>相同安装身份 + 相同 Survey ID 的输出稳定一致；Survey ID 不同则输出不同；输出不等于
 * 原始 UUID。这是匿名作用域标识而非账号标识：同一安装的多个浏览器 / 访问设备共享同一
 * scoped ID，仅用于该调查的去重。本类不记录日志，任何调用方都不得打印原始安装 UUID
 * 或派生后的 scoped ID。
 */
public final class LayoutFeedbackIdentityDeriver {

    /** Survey ID 形状：标准 UUID 外形，或 8-128 位安全字母数字令牌（与打包生成器一致）。 */
    public static final Pattern SURVEY_ID_PATTERN = Pattern.compile(
            "(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "|[A-Za-z0-9_-]{8,128}");

    /** scoped ID 固定格式：{@code plf_} + 64 位小写 hex。 */
    public static final Pattern SCOPED_ID_PATTERN = Pattern.compile("^plf_[0-9a-f]{64}$");

    static final String SCOPE_NAMESPACE = "pixivdownload:download-workbench:layout-feedback:v1";

    private static final char NAMESPACE_SEPARATOR = '\0';

    private LayoutFeedbackIdentityDeriver() {
    }

    /**
     * 校验 Survey ID 形状（UUID 外形或安全字母数字令牌，长度受控）。
     */
    public static boolean isValidSurveyId(String surveyId) {
        return surveyId != null && SURVEY_ID_PATTERN.matcher(surveyId).matches();
    }

    /**
     * 派生调查作用域匿名身份。surveyId 形状非法或 installIdentity 不是真实 UUID v4 时抛出
     * {@link IllegalArgumentException}。
     */
    public static String deriveScopedIdentity(String surveyId, String installIdentity) {
        if (!isValidSurveyId(surveyId)) {
            throw new IllegalArgumentException("invalid survey id shape");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(installIdentity);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid install identity", e);
        }
        if (uuid.version() != 4 || uuid.variant() != 2) {
            throw new IllegalArgumentException("install identity is not a UUID v4");
        }
        String input = SCOPE_NAMESPACE + NAMESPACE_SEPARATOR + surveyId
                + NAMESPACE_SEPARATOR + uuid.toString();
        byte[] digest = sha256(input);
        return "plf_" + HexFormat.of().formatHex(digest);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
