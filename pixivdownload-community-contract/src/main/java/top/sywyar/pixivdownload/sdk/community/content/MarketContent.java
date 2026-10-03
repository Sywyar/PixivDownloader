package top.sywyar.pixivdownload.sdk.community.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 版本说明的原始附件引用；发布时只替换 URL，格式、大小和摘要保持审核时的值。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketContent(Map<String, Document> readme, Map<String, Document> changelog,
                            Map<String, Document> releaseNotes) {
    public static final int DOCUMENT_BYTES = 1024 * 1024;
    public static final int TOTAL_DOCUMENT_BYTES = 4 * 1024 * 1024;
    public static final int IMAGE_BYTES = 2 * 1024 * 1024;
    public static final int TOTAL_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_RESOURCES = 32;

    public MarketContent {
        readme = freeze(readme);
        changelog = freeze(changelog);
        releaseNotes = freeze(releaseNotes);
    }

    public record Asset(String name, String url, String mediaType, long size, String sha256) {
        public void validate(boolean image) {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) invalid("/content/name");
            CommunityPaths.relative(name, false);
            CommunityValues.https(url, true, "/content/url");
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) invalid("/content/sha256");
            int maximum = image ? IMAGE_BYTES : DOCUMENT_BYTES;
            if (size <= 0) invalid("/content/size");
            if (size > maximum) throw CommunityJson.limit("/content/size", maximum, "bytes");
            if (mediaType == null || !(image ? Set.of("image/png", "image/jpeg", "image/webp")
                    : Set.of("text/markdown", "text/html")).contains(mediaType)) invalid("/content/mediaType");
            String extension = switch (mediaType) {
                case "text/markdown" -> "md"; case "text/html" -> "html";
                case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp";
                default -> throw new IllegalStateException();
            };
            if (!name.equals("content-" + sha256 + "." + extension)
                    && !(image && name.equals("market-" + sha256 + "." + extension))) invalid("/content/name");
        }
        public void verify(byte[] bytes) { CommunityValues.verifyBytes(bytes, size, sha256, "/content/" + name); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Document(String format, Asset asset, String sourcePath, Map<String, Asset> resources) {
        public Document { resources = freeze(resources); }
        public void validate() {
            if (format == null || !Set.of("markdown", "html").contains(format) || asset == null) invalid("/content/format");
            asset.validate(false);
            if (!("text/" + format).equals(asset.mediaType)) invalid("/content/mediaType");
            if (sourcePath != null) CommunityPaths.relative(sourcePath, false);
            if (resources == null) return;
            if (resources.size() > MAX_RESOURCES) throw CommunityJson.limit("/content/resources", MAX_RESOURCES, "items");
            for (var entry : resources.entrySet()) {
                if (entry.getKey().startsWith("https://")) CommunityValues.https(entry.getKey(), false, "/content/resources");
                else resourcePath(sourcePath, entry.getKey());
                if (entry.getValue() == null) invalid("/content/resources");
                entry.getValue().validate(true);
            }
        }
    }

    /** 相对图片的原始写法用于渲染映射，实际文件路径必须仍落在选定根内。 */
    public static String resourcePath(String sourcePath, String value) {
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || value.startsWith("/") || value.contains("\\")) {
                invalid("/content/resources");
            }
            String source = sourcePath == null ? "README.md" : CommunityPaths.relative(sourcePath, false);
            String path = new URI(null, null, source, null).resolve(uri).normalize().getPath();
            return CommunityPaths.relative(path, false);
        } catch (IllegalArgumentException | java.net.URISyntaxException failure) {
            throw new ContractException("PATH_MISMATCH", "/content/resources");
        }
    }

    public void validate(String defaultLocale) {
        var assets = new LinkedHashMap<String, Asset>();
        long documents = 0, images = 0;
        for (var group : java.util.Arrays.asList(readme, changelog, releaseNotes)) {
            if (group == null) continue;
            ContentLocales.validate(group, "/content");
            if (defaultLocale != null && !group.containsKey(defaultLocale)) {
                throw new ContractException("LOCALE_DEFAULT_MISSING", "/content");
            }
            for (var document : group.values()) {
                if (document == null) invalid("/content");
                document.validate();
                if (add(assets, document.asset)) documents += document.asset.size;
                if (document.resources != null) for (var image : document.resources.values()) {
                    if (add(assets, image)) images += image.size;
                }
            }
        }
        if (documents > TOTAL_DOCUMENT_BYTES) throw CommunityJson.limit("/content", TOTAL_DOCUMENT_BYTES, "document-bytes-total");
        if (images > TOTAL_IMAGE_BYTES) throw CommunityJson.limit("/content", TOTAL_IMAGE_BYTES, "image-bytes-total");
    }

    public Map<String, Asset> assets() {
        var result = new LinkedHashMap<String, Asset>();
        for (var group : java.util.Arrays.asList(readme, changelog, releaseNotes)) if (group != null) {
            for (var document : group.values()) {
                add(result, document.asset);
                if (document.resources != null) document.resources.values().forEach(asset -> add(result, asset));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean add(Map<String, Asset> assets, Asset asset) {
        var prior = assets.putIfAbsent(asset.name, asset);
        if (prior != null && !prior.equals(asset)) throw new ContractException("DUPLICATE_KEY", "/content/assets");
        return prior == null;
    }
    private static <T> Map<String, T> freeze(Map<String, T> value) {
        return value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    private static void invalid(String field) { throw new ContractException("SCHEMA_INVALID", field); }
}
