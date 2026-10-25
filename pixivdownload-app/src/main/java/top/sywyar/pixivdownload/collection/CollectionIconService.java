package top.sywyar.pixivdownload.collection;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
    private final FileTransactionService files;

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

    /** 调用方串行化同一收藏夹的修改；图标与记录回调共用主库文件事务。 */
    public String saveIcon(long collectionId, byte[] data, Consumer<String> commitRecord) throws IOException {
        if (data.length > MAX_ICON_BYTES) {
            throw LocalizedException.badRequest(
                    "collection.icon.size.exceeded.detail",
                    "图标超出大小限制（最多 {0} KB）",
                    MAX_ICON_BYTES / 1024
            );
        }
        UploadedImageValidator.ValidatedImage image = UploadedImageValidator.validate(data);
        if (image == null) {
            throw LocalizedException.badRequest(
                    "collection.icon.format.unsupported",
                    "不支持的图标格式，仅接受 png/jpg/jpeg/webp"
            );
        }
        String ext = image.extension();
        Path target = resolveIconPath(collectionId, ext);
        Path temporary = target.resolveSibling(collectionId + ".icon.part");
        boolean staged = false;
        try {
            PlainFilePathGuard.requirePlainParent(temporary, false);
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                PlainFilePathGuard.requirePlainRegularFile(temporary);
            }
            // 固定暂存名使下一次写入可回收进程中断残留，不保留历史上传副本。
            Files.deleteIfExists(temporary);
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                staged = true;
                output.write(image.storageBytes());
            }
            files.changeFiles(Map.of(target, temporary), iconPaths(collectionId).stream()
                    .filter(path -> !path.equals(target)).toList(), () -> commitRecord.accept(ext));
        } finally {
            if (staged) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    log.warn(message("collection.log.icon.delete-failed", temporary), failure);
                }
            }
        }
        log.info(message("collection.log.icon.saved", collectionId, ext, image.storageBytes().length));
        return ext;
    }

    public void deleteAll(long collectionId, Runnable commitRecord) throws IOException {
        files.changeFiles(Map.of(), iconPaths(collectionId), commitRecord);
    }

    private List<Path> iconPaths(long collectionId) {
        return ALLOWED_EXTENSIONS.stream().map(ext -> resolveIconPath(collectionId, ext)).toList();
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

    private Path iconDirectory() {
        return RuntimeFiles.collectionIconsDirectory();
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
