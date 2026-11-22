package top.sywyar.pixivdownload.core.artwork.download;

import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 未完成选页的布局及各原始页索引上已发布的输出。
 * @param artworkId 插画作品 ID
 * @param folder 作品目录的绝对路径
 * @param totalPages 来源作品的总页数
 * @param template 生成文件名时使用的模板
 * @param maxLength 文件名长度上限
 * @param outputSettings 续传时比对的输出设置
 * @param sourceRevision 来源作品的修订指纹
 * @param baseNames 按原始页序排列的文件名，不含扩展名
 * @param media 已保存页面的媒体清单，以原始页索引为键
 * @param fingerprints 已保存页面的文件指纹，以原始页索引为键
 */
public record PartialArtwork(long artworkId, String folder, int totalPages, String template, int maxLength,
                             String outputSettings, String sourceRevision, List<String> baseNames,
                             Map<Integer, ArtworkMediaManifest> media, Map<Integer, String> fingerprints) {
    /**
     * 校验选页布局并保存各页的不可变快照。
     * @param artworkId 插画作品 ID
     * @param folder 作品目录的绝对路径
     * @param totalPages 来源作品的总页数
     * @param template 生成文件名时使用的模板
     * @param maxLength 文件名长度上限
     * @param outputSettings 续传时比对的输出设置
     * @param sourceRevision 来源作品的修订指纹
     * @param baseNames 按原始页序排列的文件名，不含扩展名
     * @param media 已保存页面的媒体清单，以原始页索引为键
     * @param fingerprints 已保存页面的文件指纹，以原始页索引为键
     */
    public PartialArtwork {
        baseNames = List.copyOf(baseNames);
        media = Map.copyOf(media);
        fingerprints = Map.copyOf(fingerprints);
        if (totalPages <= 0 || baseNames.size() != totalPages || !Path.of(folder).isAbsolute()
                || !baseNames.equals(PixivWorkFileNameFormatter.normalizeProvidedBaseNames(baseNames, totalPages, artworkId))
                || media.keySet().stream().anyMatch(page -> page < 0 || page >= totalPages))
            throw new IllegalArgumentException("Invalid partial artwork layout");
    }

    /**
     * 上传时间和按顺序排列的全部原图 URL 也能反映选页范围以外的变化。
     * @param urls 按原始页序排列的 URL
     * @param uploadDate 来源上传日期
     * @return 来源版本的 SHA-256 指纹
     */
    public static String sourceRevision(List<String> urls, String uploadDate) {
        var digest = sha256();
        if (uploadDate != null) digest.update(uploadDate.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String url : urls) {
            digest.update(url.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 按清单中的扩展名顺序保存。读取可能损坏的文件前先检查长度。
     * @param stem 不含扩展名的文件路径
     * @param manifest 该页的产物清单
     * @return 按清单顺序排列的文件长度与 SHA-256 摘要
     * @throws IOException 文件无法读取或为空
     */
    public static String fingerprint(Path stem, ArtworkMediaManifest manifest) throws IOException {
        var result = new StringJoiner(",");
        for (String ext : manifest.extensions()) {
            Path file = stem.resolveSibling(stem.getFileName() + "." + ext);
            var digest = sha256();
            try (var input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
                long size = input.transferTo(OutputStream.nullOutputStream());
                if (size == 0) throw new IOException("Empty artwork output");
                result.add(size + ":" + HexFormat.of().formatHex(digest.digest()));
            }
        }
        return result.toString();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /**
     * 列出现有输出。续传时还要核对摘要，才能将该页计为完成。
     * @return 按原始页索引排列的现有文件名
     * @throws IOException 读取文件信息失败
     */
    public Map<Integer, List<String>> availableFiles() throws IOException {
        return availableFiles(false);
    }

    /**
     * 验证文件摘要后列出可以续用的页面。
     * @return 通过验证的页面及其输出文件名
     * @throws IOException 无法读取文件信息或内容
     */
    public Map<Integer, List<String>> verifiedFiles() throws IOException {
        return availableFiles(true);
    }

    private Map<Integer, List<String>> availableFiles(boolean verify) throws IOException {
        var files = new TreeMap<Integer, List<String>>();
        for (var entry : media.entrySet()) {
            int page = entry.getKey();
            String name = baseNames.get(page);
            var outputs = entry.getValue().extensions().stream().map(ext -> name + "." + ext).toList();
            String expected = fingerprints.get(page);
            if (verify && expected == null) continue;
            String[] stamps = expected == null ? null : expected.split(",", -1);
            if (stamps != null && stamps.length != outputs.size()) continue;
            boolean valid = true;
            for (int i = 0; i < outputs.size(); i++) {
                Path file = Path.of(folder).resolve(outputs.get(i));
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { valid = false; break; }
                long size = Files.size(file);
                if (size == 0 || stamps != null && !stamps[i].startsWith(size + ":")) { valid = false; break; }
            }
            if (valid && (!verify || expected.equals(fingerprint(Path.of(folder).resolve(name), entry.getValue()))))
                files.put(page, outputs);
        }
        return files;
    }
}
