package top.sywyar.pixivdownload.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 完整公共插件 SDK 的版本与兼容性信息。
 *
 * <p>SDK 版本覆盖 {@code pixivdownload-sdk-info}、{@code pixivdownload-plugin-api}
 * 和 {@code pixivdownload-core-api}，并且独立于应用发行版本。兼容时要求主版本相同，
 * 且宿主次版本不低于插件要求；补丁版本不参与兼容性判断。
 */
public final class SdkVersion {

    private static final String RESOURCE = "/META-INF/pixivdownload-sdk.properties";
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(alpha|beta|rc)([1-9]\\d*))?");
    private static final Metadata METADATA = load();

    /** 当前 SDK 的语义化版本。 */
    public static final String VERSION = METADATA.version();
    /** 兼容性主版本。 */
    public static final int MAJOR = METADATA.major();
    /** 兼容性次版本。 */
    public static final int MINOR = METADATA.minor();
    /** 补丁版本，不参与兼容性判断。 */
    public static final int PATCH = METADATA.patch();
    /** 预发布通道；稳定版为空字符串。 */
    public static final String PRERELEASE_CHANNEL = METADATA.prereleaseChannel();
    /** 预发布序号；稳定版为 {@code 0}。 */
    public static final int PRERELEASE_SEQUENCE = METADATA.prereleaseSequence();

    private SdkVersion() {
    }

    /**
     * 返回当前 SDK 的语义化版本。
     *
     * @return {@link #VERSION}
     */
    public static String current() {
        return VERSION;
    }

    /**
     * 返回不可变的 SDK 发布标识。
     *
     * @return 形如 {@code sdk-api-v1.0.0-rc2} 的发布标识
     */
    public static String releaseId() {
        return "sdk-api-v" + VERSION;
    }

    /**
     * 判断当前 SDK 是否为预发布版本。
     *
     * @return 带有 {@code alphaN}、{@code betaN} 或 {@code rcN} 后缀时返回 {@code true}
     */
    public static boolean isPrerelease() {
        return !PRERELEASE_CHANNEL.isEmpty();
    }

    /**
     * 判断当前宿主 SDK 是否满足要求的兼容版本。
     *
     * @param requiredMajor 要求的 SDK 主版本
     * @param requiredMinor 要求的 SDK 次版本
     * @return 当前 SDK 兼容时返回 {@code true}
     */
    public static boolean isCompatibleWith(int requiredMajor, int requiredMinor) {
        return isCompatible(MAJOR, MINOR, requiredMajor, requiredMinor);
    }

    /**
     * 判断给定的 SDK 版本是否满足要求的 SDK 版本。
     *
     * @param providedMajor 给定的 SDK 主版本
     * @param providedMinor 给定的 SDK 次版本
     * @param requiredMajor 要求的 SDK 主版本
     * @param requiredMinor 要求的 SDK 次版本
     * @return 给定 SDK 兼容时返回 {@code true}
     */
    public static boolean isCompatible(
            int providedMajor, int providedMinor, int requiredMajor, int requiredMinor) {
        return requiredMajor == providedMajor && requiredMinor <= providedMinor;
    }

    private static Metadata load() {
        Properties properties = new Properties();
        try (InputStream input = SdkVersion.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing SDK metadata: " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SDK metadata: " + RESOURCE, e);
        }
        if (properties.containsKey("revision")) {
            throw new IllegalStateException("Unsupported SDK revision metadata");
        }
        String version = properties.getProperty("version");
        if (version == null) {
            throw new IllegalStateException("Missing SDK version metadata");
        }
        return parse(version.trim());
    }

    static Metadata parse(String version) {
        Matcher matcher = SEMVER.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalStateException("Invalid SDK semantic version: " + version);
        }
        String prereleaseChannel = matcher.group(4);
        return new Metadata(
                version,
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                prereleaseChannel == null ? "" : prereleaseChannel,
                prereleaseChannel == null ? 0 : Integer.parseInt(matcher.group(5))
        );
    }

    record Metadata(
            String version,
            int major,
            int minor,
            int patch,
            String prereleaseChannel,
            int prereleaseSequence
    ) {
    }
}
