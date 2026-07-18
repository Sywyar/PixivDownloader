package top.sywyar.pixivdownload.collection;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionService {

    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_DOWNLOAD_ROOT_LENGTH = 500;
    private static final Pattern WINDOWS_DRIVE_RELATIVE = Pattern.compile("^[A-Za-z]:(?![/\\\\]).*");
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    private final CollectionMapper collectionMapper;
    private final CollectionIconService iconService;
    private final AppMessages messages;
    private final DownloadConfig downloadConfig;
    private final NovelMetadataRepository novelMetadataRepository;
    private final PathPrefixCodec pathPrefixCodec;
    /** 不直接使用：仅表达对 {@link DatabaseInitializer} 的初始化顺序依赖（{@link #init()} 要求表已建好）。 */
    @SuppressWarnings("unused")
    private final DatabaseInitializer databaseInitializer;

    /** 非 DDL 初始化：建表 / 补列 / 索引已统一由 {@link DatabaseInitializer} 执行，这里只保留幂等数据迁移。 */
    @PostConstruct
    public void init() {
        collectionMapper.migrateCollectionTimestampsToMillis();
        collectionMapper.migrateArtworkCollectionTimestampsToMillis();
    }

    public List<Collection> listAll() {
        return collectionMapper.findAll().stream().map(this::resolve).toList();
    }

    public Collection get(long id) {
        return resolve(collectionMapper.findById(id));
    }

    private Collection resolve(Collection collection) {
        if (collection == null) return null;
        String resolved = pathPrefixCodec.resolve(collection.downloadRoot());
        if (java.util.Objects.equals(resolved, collection.downloadRoot())) return collection;
        return new Collection(collection.id(), collection.name(), collection.iconExt(),
                resolved, collection.sortOrder(), collection.createdTime(),
                collection.artworkCount(), collection.novelCount());
    }

    public boolean exists(long id) {
        return collectionMapper.countById(id) > 0;
    }

    public Collection create(String name) {
        return create(name, null);
    }

    public Collection create(String name, String downloadRoot) {
        String clean = validateName(name);
        if (collectionMapper.countByName(clean) > 0) {
            throw LocalizedException.badRequest("collection.name.duplicate", "同名收藏夹已存在");
        }
        String cleanDownloadRoot = validateDownloadRoot(downloadRoot, clean, 0L);
        CollectionInsert insert = new CollectionInsert();
        insert.setName(clean);
        insert.setIconExt(null);
        insert.setDownloadRoot(pathPrefixCodec.encode(cleanDownloadRoot));
        insert.setSortOrder(0);
        insert.setCreatedTime(System.currentTimeMillis());
        collectionMapper.insert(insert);
        Long newId = insert.getId();
        if (newId == null) {
            throw new LocalizedException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "collection.create.failed",
                    "创建收藏夹失败"
            );
        }
        log.info(message("collection.log.created", newId, clean));
        return resolve(collectionMapper.findById(newId));
    }

    public Collection rename(long id, String name) {
        requireExists(id);
        String clean = validateName(name);
        if (collectionMapper.countByNameExcludingId(clean, id) > 0) {
            throw LocalizedException.badRequest("collection.name.duplicate", "同名收藏夹已存在");
        }
        collectionMapper.updateName(id, clean);
        return resolve(collectionMapper.findById(id));
    }

    public Collection updateDownloadRoot(long id, String downloadRoot) {
        Collection collection = requireCollection(id);
        String cleanDownloadRoot = validateDownloadRoot(downloadRoot, collection.name(), collection.id());
        collectionMapper.updateDownloadRoot(id, pathPrefixCodec.encode(cleanDownloadRoot));
        return resolve(collectionMapper.findById(id));
    }

    public Collection updateSortOrder(long id, int sortOrder) {
        requireExists(id);
        collectionMapper.updateSortOrder(id, sortOrder);
        return resolve(collectionMapper.findById(id));
    }

    public void delete(long id) {
        requireExists(id);
        collectionMapper.deleteArtworkLinksByCollection(id);
        collectionMapper.deleteById(id);
        iconService.deleteAll(id);
        log.info(message("collection.log.deleted", id));
    }

    public Collection setIcon(long id, byte[] data) {
        requireExists(id);
        try {
            String ext = iconService.saveIcon(id, data);
            collectionMapper.updateIconExt(id, ext);
        } catch (java.io.IOException e) {
            throw new LocalizedException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "collection.icon.save.failed",
                    "保存图标失败: {0}",
                    e.getMessage()
            );
        }
        return resolve(collectionMapper.findById(id));
    }

    public Collection clearIcon(long id) {
        requireExists(id);
        iconService.deleteAll(id);
        collectionMapper.updateIconExt(id, null);
        return resolve(collectionMapper.findById(id));
    }

    public boolean addArtwork(long collectionId, long artworkId) {
        requireExists(collectionId);
        int changed = collectionMapper.addArtwork(collectionId, artworkId, System.currentTimeMillis());
        return changed > 0;
    }

    public boolean removeArtwork(long collectionId, long artworkId) {
        requireExists(collectionId);
        return collectionMapper.removeArtwork(collectionId, artworkId) > 0;
    }

    public void removeArtworkFromAll(long artworkId) {
        collectionMapper.removeAllArtworkLinks(artworkId);
    }

    public List<Long> collectionsOf(long artworkId) {
        return collectionMapper.findCollectionIdsByArtwork(artworkId);
    }

    public Map<Long, List<Long>> membershipsOf(List<Long> artworkIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (artworkIds == null || artworkIds.isEmpty()) return result;
        for (Map<String, Object> row : collectionMapper.findLinksByArtworks(artworkIds)) {
            Long artworkId = ((Number) row.get("artworkId")).longValue();
            Long collectionId = ((Number) row.get("collectionId")).longValue();
            result.computeIfAbsent(artworkId, k -> new ArrayList<>()).add(collectionId);
        }
        return result;
    }

    public boolean addNovel(long collectionId, long novelId) {
        requireExists(collectionId);
        return novelMetadataRepository.addToCollection(collectionId, novelId);
    }

    public boolean removeNovel(long collectionId, long novelId) {
        requireExists(collectionId);
        return novelMetadataRepository.removeFromCollection(collectionId, novelId);
    }

    public List<Long> novelCollectionsOf(long novelId) {
        return novelMetadataRepository.getCollectionIdsForNovel(novelId);
    }

    public Map<Long, List<Long>> novelMembershipsOf(List<Long> novelIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (novelIds == null || novelIds.isEmpty()) return result;
        for (Map<String, Object> row : novelMetadataRepository.findCollectionLinksByNovels(novelIds)) {
            Long novelId = ((Number) row.get("novelId")).longValue();
            Long collectionId = ((Number) row.get("collectionId")).longValue();
            result.computeIfAbsent(novelId, k -> new ArrayList<>()).add(collectionId);
        }
        return result;
    }

    public List<Long> novelIdsInCollection(long collectionId) {
        return novelMetadataRepository.getNovelIdsInCollection(collectionId);
    }

    public List<Long> artworkIdsInCollections(Set<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return List.of();
        }
        return collectionMapper.findArtworkIdsInCollections(collectionIds);
    }

    public Path resolveDownloadRoot(long collectionId, Path defaultRoot) {
        Collection collection = resolve(collectionMapper.findById(collectionId));
        if (collection == null || !StringUtils.hasText(collection.downloadRoot())) {
            return defaultRoot;
        }
        return resolveConfiguredDownloadRoot(
                collection.downloadRoot(),
                collection.name(),
                collection.id(),
                defaultRoot
        );
    }

    private void requireExists(long id) {
        if (!exists(id)) {
            throw LocalizedException.badRequest("collection.not-found", "收藏夹不存在: {0}", id);
        }
    }

    private Collection requireCollection(long id) {
        Collection collection = get(id);
        if (collection != null) {
            return collection;
        }
        throw LocalizedException.badRequest("collection.not-found", "收藏夹不存在: {0}", id);
    }

    private String validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw LocalizedException.badRequest(
                    "validation.collection.name.required",
                    "收藏夹名称不能为空"
            );
        }
        String clean = name.trim();
        if (clean.length() > MAX_NAME_LENGTH) {
            throw LocalizedException.badRequest(
                    "collection.name.too-long",
                    "收藏夹名称过长（最多 {0} 字符）",
                    MAX_NAME_LENGTH
            );
        }
        return clean;
    }

    private String validateDownloadRoot(String downloadRoot, String collectionName, long collectionId) {
        if (!StringUtils.hasText(downloadRoot)) {
            return null;
        }
        String clean = downloadRoot.trim();
        if (clean.length() > MAX_DOWNLOAD_ROOT_LENGTH) {
            throw LocalizedException.badRequest(
                    "collection.download-root.too-long",
                    "收藏夹下载目录过长（最多 {0} 字符）",
                    MAX_DOWNLOAD_ROOT_LENGTH
            );
        }
        if (hasControlCharacter(clean)) {
            throw LocalizedException.badRequest(
                    "collection.download-root.control-char",
                    "收藏夹下载目录不能包含控制字符"
            );
        }
        try {
            resolveConfiguredDownloadRoot(clean, collectionName, collectionId, Paths.get(downloadConfig.getRootFolder()));
        } catch (LocalizedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw LocalizedException.badRequest(
                    "collection.download-root.invalid",
                    "无效的收藏夹下载目录: {0}",
                    clean
            );
        }
        return clean;
    }

    private Path resolveConfiguredDownloadRoot(String downloadRoot,
                                               String collectionName,
                                               long collectionId,
                                               Path defaultRoot) {
        String expanded = expandDownloadRootTemplate(downloadRoot, collectionName, collectionId);
        if (hasSingleLeadingSeparator(expanded)) {
            expanded = stripLeadingSeparators(expanded);
        }
        if (WINDOWS_DRIVE_RELATIVE.matcher(expanded).matches()) {
            throw LocalizedException.badRequest(
                    "collection.download-root.invalid",
                    "无效的收藏夹下载目录: {0}",
                    downloadRoot
            );
        }
        Path configured = Paths.get(expanded);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path root = defaultRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(stripLeadingSeparators(expanded)).normalize();
        if (!resolved.startsWith(root)) {
            throw LocalizedException.badRequest(
                    "collection.download-root.relative-escape",
                    "相对收藏夹下载目录不能离开 download.root-folder: {0}",
                    downloadRoot
            );
        }
        return resolved;
    }

    private String expandDownloadRootTemplate(String downloadRoot, String collectionName, long collectionId) {
        return downloadRoot.replace(
                "{collection_name}",
                safePathSegment(collectionName, collectionId)
        );
    }

    private boolean hasSingleLeadingSeparator(String value) {
        return value.length() > 0
                && isPathSeparator(value.charAt(0))
                && !(value.length() > 1 && isPathSeparator(value.charAt(1)));
    }

    private boolean isPathSeparator(char value) {
        return value == '/' || value == '\\';
    }

    private String stripLeadingSeparators(String value) {
        int index = 0;
        while (index < value.length()) {
            char ch = value.charAt(index);
            if (ch != '/' && ch != '\\') {
                break;
            }
            index++;
        }
        return value.substring(index);
    }

    private String safePathSegment(String value, long collectionId) {
        String source = StringUtils.hasText(value) ? value.trim() : "collection-" + collectionId;
        StringBuilder builder = new StringBuilder(source.length());
        source.codePoints().forEach(codePoint -> {
            if (isUnsafePathSegmentCodePoint(codePoint)) {
                builder.append('_');
            } else {
                builder.appendCodePoint(codePoint);
            }
        });
        String clean = builder.toString().trim();
        if (!StringUtils.hasText(clean) || ".".equals(clean) || "..".equals(clean)) {
            return "collection-" + collectionId;
        }
        if (WINDOWS_RESERVED_NAME.matcher(clean).matches()) {
            return clean + "_";
        }
        return clean;
    }

    private boolean isUnsafePathSegmentCodePoint(int codePoint) {
        return codePoint == '/'
                || codePoint == '\\'
                || codePoint == ':'
                || codePoint == '*'
                || codePoint == '?'
                || codePoint == '"'
                || codePoint == '<'
                || codePoint == '>'
                || codePoint == '|'
                || Character.isISOControl(codePoint);
    }

    private boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
