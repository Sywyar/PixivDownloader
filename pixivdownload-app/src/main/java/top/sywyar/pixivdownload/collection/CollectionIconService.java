package top.sywyar.pixivdownload.collection;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * 收藏夹图标文件管理。图标存在 {@code data/collection_icons/{id}.{ext}}。
 * 支持 png/jpg/jpeg/webp，最大 {@value #MAX_ICON_BYTES} 字节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionIconService {

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");
    public static final long MAX_ICON_BYTES = 1024L * 1024L;

    private final AppMessages messages;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(iconDirectory());
    }

    public Path resolveIconPath(long collectionId, String ext) {
        return iconDirectory().resolve(collectionId + "." + ext);
    }

    public Path findExistingIcon(long collectionId, String ext) {
        if (ext == null || ext.isBlank()) return null;
        Path p = resolveIconPath(collectionId, ext);
        return Files.exists(p) ? p : null;
    }

    public String saveIcon(long collectionId, String originalFilename, byte[] data) throws IOException {
        String ext = detectExtension(originalFilename, data);
        if (ext == null) {
            throw LocalizedException.badRequest(
                    "collection.icon.format.unsupported",
                    "不支持的图标格式，仅接受 png/jpg/jpeg/webp"
            );
        }
        if (data.length > MAX_ICON_BYTES) {
            throw LocalizedException.badRequest(
                    "collection.icon.size.exceeded.detail",
                    "图标超出大小限制（最多 {0} KB）",
                    MAX_ICON_BYTES / 1024
            );
        }
        deleteAll(collectionId);
        Path target = resolveIconPath(collectionId, ext);
        Files.write(target, data);
        log.info(message("collection.log.icon.saved", collectionId, ext, data.length));
        return ext;
    }

    public void deleteAll(long collectionId) {
        for (String ext : ALLOWED_EXTENSIONS) {
            Path p = resolveIconPath(collectionId, ext);
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn(message("collection.log.icon.delete-failed", p), e);
            }
        }
    }

    /**
     * 优先从文件名判断扩展名，回退到 magic-number 识别。未命中返回 null。
     */
    private String detectExtension(String filename, byte[] data) {
        String fromName = extensionFromFilename(filename);
        if (fromName != null) return fromName;
        return extensionFromMagic(data);
    }

    private String extensionFromFilename(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext) ? normalize(ext) : null;
    }

    private String extensionFromMagic(byte[] data) {
        if (data == null || data.length < 12) return null;
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "webp";
        return null;
    }

    private String normalize(String ext) {
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    /**
     * 文件扩展名对应的 Content-Type。
     */
    public String contentType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    /**
     * 原子替换：在测试/重置时使用。
     */
    public void replaceAtomic(long collectionId, String ext, byte[] data) throws IOException {
        Path iconDir = iconDirectory();
        Path tmp = Files.createTempFile(iconDir, "icon", ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, resolveIconPath(collectionId, ext), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path iconDirectory() {
        return RuntimeFiles.collectionIconsDirectory();
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
