package top.sywyar.pixivdownload.core.pixiv;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

/**
 * Pixiv 图片流式下载稳定端口。
 *
 * <p>实现负责来源主机与 Referer 校验、统一图片请求头、传输字节上限和流式传输；
 * 非法目标或非 2xx 响应返回 {@code false}，传输与文件 I/O 失败只抛出 {@link IOException}。
 * 原始传输由调用方指定完整路径；图片落盘应使用 {@link #downloadImage} 验证格式并确定扩展名。
 */
public interface PixivImageDownloader {

    boolean download(
            URI source,
            URI referer,
            Path target,
            String cookie,
            PixivImageTransferObserver observer
    ) throws IOException;

    /**
     * 下载并验证图片内容，把最终文件写入 {@code targetStem + "." + extension}。
     * 扩展名只可能来自受支持的图片格式；响应类型与文件头不一致时拒绝落盘。
     *
     * @return 成功时返回最终扩展名，非 2xx 或非法来源返回 {@code null}
     */
    default String downloadImage(
            URI source,
            URI referer,
            Path targetStem,
            String cookie,
            PixivImageTransferObserver observer
    ) throws IOException {
        Objects.requireNonNull(targetStem, "targetStem");
        Objects.requireNonNull(observer, "observer");
        Path fileName = Objects.requireNonNull(targetStem.getFileName(), "targetStem file name");
        Path staging = targetStem.resolveSibling(fileName + ".image-download");
        String[] contentType = {null};
        try {
            boolean downloaded = download(source, referer, staging, cookie, new PixivImageTransferObserver() {
                @Override
                public long maximumBytes() {
                    return observer.maximumBytes();
                }

                @Override
                public void checkCancelled() {
                    observer.checkCancelled();
                }

                @Override
                public void onContentType(String value) {
                    contentType[0] = value;
                    observer.onContentType(value);
                }

                @Override
                public void onContentLength(long contentLength) {
                    observer.onContentLength(contentLength);
                }

                @Override
                public void onBytesTransferred(long transferredBytes) {
                    observer.onBytesTransferred(transferredBytes);
                }
            });
            if (!downloaded) {
                return null;
            }
            String extension = verifiedExtension(source, contentType[0], staging);
            Files.move(staging, targetStem.resolveSibling(fileName + "." + extension),
                    StandardCopyOption.REPLACE_EXISTING);
            return extension;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static String verifiedExtension(URI source, String contentType, Path file) throws IOException {
        String pathExtension = extensionFromPath(source == null ? null : source.getPath());
        String responseExtension = extensionFromContentType(contentType);
        String magicExtension = extensionFromMagic(file);
        if (responseExtension != null && !sameFormat(responseExtension, magicExtension)) {
            throw new IOException("Pixiv image content type does not match its file signature");
        }
        return "jpg".equals(magicExtension) && "jpeg".equals(pathExtension)
                ? "jpeg"
                : magicExtension;
    }

    private static String extensionFromPath(String path) {
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return allowedExtension(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String extensionFromContentType(String contentType) throws IOException {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/octet-stream", "binary/octet-stream" -> null;
            default -> throw new IOException("Unsupported Pixiv image content type");
        };
    }

    private static String extensionFromMagic(Path file) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(file)) {
            header = input.readNBytes(12);
        }
        if (startsWith(header, 0xff, 0xd8, 0xff)) {
            return "jpg";
        }
        if (startsWith(header, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) {
            return "png";
        }
        if (startsWith(header, 'G', 'I', 'F', '8', '7', 'a')
                || startsWith(header, 'G', 'I', 'F', '8', '9', 'a')) {
            return "gif";
        }
        if (startsWith(header, 'R', 'I', 'F', 'F') && matchesAt(header, 8, 'W', 'E', 'B', 'P')) {
            return "webp";
        }
        throw new IOException("Unsupported Pixiv image file signature");
    }

    private static String allowedExtension(String extension) {
        return switch (extension) {
            case "jpg", "jpeg", "png", "webp", "gif" -> extension;
            default -> null;
        };
    }

    private static boolean sameFormat(String left, String right) {
        return normalizeJpeg(left).equals(normalizeJpeg(right));
    }

    private static String normalizeJpeg(String extension) {
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        return matchesAt(bytes, 0, expected);
    }

    private static boolean matchesAt(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[offset + index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
