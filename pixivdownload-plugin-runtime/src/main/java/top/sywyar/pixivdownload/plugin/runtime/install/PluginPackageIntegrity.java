package top.sywyar.pixivdownload.plugin.runtime.install;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 插件包完整性校验（本地、纯函数、<b>不</b>发起任何网络访问）：把一个待安装包的实际字节与其来源
 * {@link PluginPackageOrigin} 声明的期望（大小 / SHA-256 / 结构化签名是否存在）比对。
 *
 * <h2>用途与边界</h2>
 * 这是旧调用点保留的薄校验器：受信目录清单提供期望大小 / 哈希时，本类据此校验本地文件。实际签名算法校验由统一
 * 供应链 verifier 执行。<b>本地上传</b>来源（{@link PluginPackageSource#LOCAL_UPLOAD}）不带任何期望，
 * {@link #verify} 直接通过（无可信清单可比对）。
 *
 * <p>校验<b>fail-closed</b>：声明了期望就必须匹配；声明了签名但调用方仍落到本兼容校验器时，<b>拒绝</b>而非放行，
 * 避免结构化签名被静默跳过。本类不接受任何 URL、不下载、不解压。
 */
public final class PluginPackageIntegrity {

    private PluginPackageIntegrity() {
    }

    /** 一次完整性校验的结果（不可变）。 */
    public record Result(boolean ok, String detail) {
        public static Result pass() {
            return new Result(true, "ok");
        }

        public static Result fail(String detail) {
            return new Result(false, detail);
        }
    }

    /**
     * 按来源声明的期望校验本地文件：本地上传 / 无期望 → 直接通过；声明期望大小 / SHA-256 → 必须一致；声明签名 →
     * 当前无校验器、fail-closed 拒绝。读取失败按拒绝处理。
     */
    public static Result verify(PluginPackageOrigin origin, Path file) {
        if (origin == null || !origin.hasIntegrityExpectations()) {
            return Result.pass();
        }
        try {
            if (origin.expectedSizeBytes() != null) {
                long actual = Files.size(file);
                if (actual != origin.expectedSizeBytes()) {
                    return Result.fail("size mismatch: expected " + origin.expectedSizeBytes()
                            + " but was " + actual);
                }
            }
            if (origin.expectedSha256() != null) {
                String actual = sha256Hex(file);
                if (!actual.equalsIgnoreCase(origin.expectedSha256())) {
                    return Result.fail("sha-256 mismatch");
                }
            }
        } catch (IOException e) {
            return Result.fail("unreadable package for integrity check: " + e.getMessage());
        }
        if (origin.signature() != null) {
            // 保留位：尚无签名校验器。声明了签名却无法校验时 fail-closed，绝不放行未校验的「已签名」包。
            return Result.fail("signature verification is not available");
        }
        return Result.pass();
    }

    /** 计算文件 SHA-256 的十六进制小写串（流式读取，不一次性载入内存）。 */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
