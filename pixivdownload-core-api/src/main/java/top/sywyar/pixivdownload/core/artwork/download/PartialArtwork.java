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
import java.util.Set;
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
        return fingerprint(stem, manifest, manifest.thumbnailOwned());
    }

    private static String fingerprint(Path stem, ArtworkMediaManifest manifest, boolean thumbnail) throws IOException {
        var result = new StringJoiner(",");
        for (String ext : manifest.extensions()) {
            result.add(fingerprintFile(stem.resolveSibling(stem.getFileName() + "." + ext)));
        }
        if (thumbnail) result.add(fingerprintFile(stem.resolveSibling(stem.getFileName() + "_thumb.jpg")));
        return result.toString();
    }

    private static String fingerprintFile(Path file) throws IOException {
        String stamp = observeFile(file);
        if (stamp.startsWith("0:")) throw new IOException("Empty artwork output");
        return stamp;
    }

    private static String observeFile(Path file) throws IOException {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
            long size = input.transferTo(OutputStream.nullOutputStream());
            return size + ":" + HexFormat.of().formatHex(digest.digest());
        }
    }

    /**
     * 同布局续传前观察到的登记文件。未登记、已丢失的目标只能新建。
     * @param observedHashes 允许替换的已有登记文件及发布前必须再次匹配的 SHA-256
     * @param verifiedFiles 内容仍匹配原登记，可在本次不再输出时移除的文件
     * @param preserveThumbnail 已存在用户或归属未经证实的缩略图，应原样保留并从新登记排除
     */
    public record PageRepair(Map<Path, String> observedHashes, Set<Path> verifiedFiles, boolean preserveThumbnail) {
        /**
         * 保存发布前的文件校验条件，复制文件名和摘要集合。
         * @param observedHashes 可替换文件及其观察到的 SHA-256
         * @param verifiedFiles 仍匹配原登记的文件
         * @param preserveThumbnail 是否保留已存在的缩略图
         */
        public PageRepair {
            observedHashes = Map.copyOf(observedHashes);
            verifiedFiles = Set.copyOf(verifiedFiles);
        }
    }

    /**
     * 在开始下载该页前记录可替换文件的内容。登记的媒体格式可修复损坏；
     * 已存在的缩略图只在原记录含有匹配摘要时重新发布。
     * @param page 待修复的原始页索引
     * @return 该页发布时的校验条件
     * @throws IOException 无法读取现有文件
     */
    public PageRepair observeRepair(int page) throws IOException {
        var observed = new java.util.HashMap<Path, String>();
        var verified = new java.util.HashSet<Path>();
        var previous = media.get(page);
        Path stem = Path.of(folder).resolve(baseNames.get(page));
        Path thumbnail = stem.resolveSibling(stem.getFileName() + "_thumb.jpg");
        boolean preserveThumbnail = !Files.notExists(thumbnail, LinkOption.NOFOLLOW_LINKS);
        if (previous != null) {
            String[] stamps = fingerprints.getOrDefault(page, "").split(",", -1);
            int formats = previous.extensions().size();
            for (int index = 0; index < formats; index++) {
                Path file = stem.resolveSibling(stem.getFileName() + "." + previous.extensions().get(index));
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String stamp = observeFile(file);
                observed.put(file, stamp.substring(stamp.indexOf(':') + 1));
                if (index < stamps.length && stamp.equals(stamps[index])) verified.add(file);
            }
            if (previous.thumbnailOwned() && stamps.length == formats + 1 && matchesSize(thumbnail, stamps[formats])
                    && stamps[formats].equals(fingerprintFile(thumbnail))) {
                observed.put(thumbnail, stamps[formats].substring(stamps[formats].indexOf(':') + 1));
                verified.add(thumbnail);
                preserveThumbnail = false;
            }
        }
        return new PageRepair(observed, verified, preserveThumbnail);
    }

    /**
     * 重建时分别核对每个已登记文件，某个副本丢失或被改写不撤销其它副本的归属。
     * @return 可替换的路径及发布前需复核的 SHA-256
     * @throws IOException 无法读取文件信息或内容
     */
    public Map<Path, String> verifiedOutputs() throws IOException {
        var files = new java.util.HashMap<Path, String>();
        for (var page : media.entrySet()) {
            var manifest = page.getValue();
            String base = baseNames.get(page.getKey());
            String expected = fingerprints.get(page.getKey());
            if (expected == null) continue;
            String[] stamps = expected.split(",", -1);
            int formats = manifest.extensions().size();
            if (stamps.length != formats && !(manifest.thumbnailOwned() && stamps.length == formats + 1)) continue;
            for (int index = 0; index < stamps.length; index++) {
                String suffix = index == formats ? "_thumb.jpg" : "." + manifest.extensions().get(index);
                Path file = Path.of(folder).resolve(base + suffix);
                if (matchesSize(file, stamps[index]) && stamps[index].equals(fingerprintFile(file)))
                    files.put(file, stamps[index].substring(stamps[index].indexOf(':') + 1));
            }
            // 旧指纹只涵盖媒体格式；已明确记录的缩略图沿用原归属。
            if (manifest.thumbnailOwned() && stamps.length == formats) {
                Path thumb = Path.of(folder).resolve(base + "_thumb.jpg");
                if (Files.isRegularFile(thumb, LinkOption.NOFOLLOW_LINKS)) {
                    String stamp = fingerprintFile(thumb);
                    files.put(thumb, stamp.substring(stamp.indexOf(':') + 1));
                }
            }
        }
        return files;
    }

    private static boolean matchesSize(Path file, String stamp) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        long size = Files.size(file);
        return size > 0 && (stamp == null || stamp.startsWith(size + ":"));
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
        return availableFiles(media.keySet(), false);
    }

    /**
     * 验证文件摘要后列出可以续用的页面。
     * @return 通过验证的页面及其输出文件名
     * @throws IOException 无法读取文件信息或内容
     */
    public Map<Integer, List<String>> verifiedFiles() throws IOException {
        return verifiedFiles(media.keySet());
    }

    /** 仅校验本次请求的页，完整登记前调用无参数版本验证全部产物。
     * @param pages 本次请求的原始页索引
     * @return 已验证的输出文件
     * @throws IOException 无法读取文件信息或内容
     */
    public Map<Integer, List<String>> verifiedFiles(Set<Integer> pages) throws IOException {
        return availableFiles(pages, true);
    }

    private Map<Integer, List<String>> availableFiles(Set<Integer> pages, boolean verify) throws IOException {
        var files = new TreeMap<Integer, List<String>>();
        for (int page : pages) {
            var manifest = media.get(page);
            if (manifest == null) continue;
            String name = baseNames.get(page);
            var outputs = manifest.extensions().stream().map(ext -> name + "." + ext).toList();
            String expected = fingerprints.get(page);
            if (verify && expected == null) continue;
            String[] stamps = expected == null ? null : expected.split(",", -1);
            boolean hasThumbnailStamp = manifest.thumbnailOwned() && stamps != null && stamps.length == outputs.size() + 1;
            if (stamps != null && stamps.length != outputs.size() && !hasThumbnailStamp) continue;
            boolean valid = true;
            for (int i = 0; i < outputs.size(); i++) {
                Path file = Path.of(folder).resolve(outputs.get(i));
                if (!matchesSize(file, stamps == null ? null : stamps[i])) { valid = false; break; }
            }
            if (hasThumbnailStamp && !matchesSize(Path.of(folder).resolve(name + "_thumb.jpg"), stamps[outputs.size()])) continue;
            if (!valid) continue;
            if (verify) {
                Path stem = Path.of(folder).resolve(name);
                String actual = manifest.thumbnailOwned() && !hasThumbnailStamp ? fingerprint(stem, manifest, false) : fingerprint(stem, manifest);
                if (!expected.equals(actual)) continue;
            }
            files.put(page, outputs);
        }
        return files;
    }
}
