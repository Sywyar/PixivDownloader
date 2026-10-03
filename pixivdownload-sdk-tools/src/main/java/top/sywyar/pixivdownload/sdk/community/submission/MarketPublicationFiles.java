package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.content.MarketImage;
import top.sywyar.pixivdownload.sdk.community.content.MarketLink;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 官方目录的文档来源声明复用投稿合同；原始文件与元数据一起进入同一插件 Release。 */
public final class MarketPublicationFiles {
    public static final String METADATA = "market-content.json";
    private MarketPublicationFiles() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Publication(String defaultLocale, List<MarketLink> links, MarketImage icon,
                              List<MarketImage> screenshots, MarketContent content) { }

    public static void prepare(Path root, Path curation, String pluginId, String version, String base, Path output)
            throws IOException {
        CommunityValues.https(base, true, "/release");
        if (!base.endsWith("/")) throw new ContractException("SCHEMA_INVALID", "/release");
        byte[] input;
        try (var stream = Files.newInputStream(curation)) { input = stream.readNBytes(256 * 1024 + 1); }
        var entry = CommunityJson.strictTree(input, 256 * 1024).path(pluginId);
        if (!entry.isObject()) throw new ContractException("SCHEMA_INVALID", "/curation");
        String locale = entry.path("defaultLocale").asText();
        var assets = new LinkedHashMap<String, byte[]>();
        var sources = entry.path("documentationSources");
        var content = new MarketContent(documents(root, sources.path("readme"), false, version, base, assets),
                documents(root, sources.path("changelog"), false, version, base, assets),
                documents(root, sources.path("releaseNotes"), true, version, base, assets));
        if (content.readme() == null && content.changelog() == null && content.releaseNotes() == null) content = null;
        MarketImage icon = image(root, entry.path("icon"), true, base, assets);
        var screenshots = new ArrayList<MarketImage>();
        if (entry.has("screenshots")) for (var image : entry.path("screenshots")) {
            screenshots.add(image(root, image, false, base, assets));
        }
        List<MarketLink> links = entry.has("links") ? List.of(CommunityJson.decode("market/properties/links",
                CommunityJson.encode(entry.get("links")), 64 * 1024, MarketLink[].class)) : null;
        var publication = new Publication(locale, links, icon, screenshots, content);
        if (content != null) content.validate(locale);
        MarketLink.validate(links);
        Files.createDirectories(output);
        for (var asset : assets.entrySet()) Files.write(output.resolve(asset.getKey()), asset.getValue());
        Files.write(output.resolve(METADATA), CommunityJson.encode(publication));
        verify(output.resolve(METADATA), output, base);
    }

    private static Map<String, MarketContent.Document> documents(Path root, JsonNode sources, boolean extract,
            String version, String base, Map<String, byte[]> files) throws IOException {
        if (sources.isNull() || sources.isMissingNode()) return null;
        if (!sources.isObject()) throw new ContractException("SCHEMA_INVALID", "/documentationSources");
        if (sources.size() > top.sywyar.pixivdownload.sdk.community.content.ContentLocales.MAX_LOCALES) {
            throw CommunityJson.limit("/documentationSources", top.sywyar.pixivdownload.sdk.community.content.ContentLocales.MAX_LOCALES, "locales");
        }
        var result = new LinkedHashMap<String, MarketContent.Document>();
        for (var fields = sources.fields(); fields.hasNext();) {
            var source = fields.next();
            String path = CommunityPaths.relative(source.getValue().asText(), false);
            String format = path.matches("(?i).*\\.html?$") ? "html" : "markdown";
            byte[] bytes = MarketDocumentFiles.read(root, path, MarketContent.DOCUMENT_BYTES);
            if (extract) {
                if (!"markdown".equals(format)) throw new ContractException("SCHEMA_INVALID", "/documentationSources");
                bytes = ChangelogSections.extract(bytes, version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            var inspected = MarketDocumentFiles.inspect(bytes, format);
            var resources = new LinkedHashMap<String, MarketContent.Asset>();
            for (String reference : inspected.images()) {
                String relative = MarketContent.resourcePath(path, reference);
                byte[] image = MarketDocumentFiles.read(root, relative, MarketContent.IMAGE_BYTES);
                var type = MarketImages.inspect(image, false).mediaType();
                resources.put(reference, asset(image, type, base, files, "content-"));
            }
            result.put(source.getKey(), new MarketContent.Document(format,
                    asset(bytes, "text/" + format, base, files, "content-"), path, resources));
        }
        return result;
    }

    private static MarketImage image(Path root, JsonNode value, boolean icon, String base, Map<String, byte[]> files)
            throws IOException {
        if (value.isNull() || value.isMissingNode()) return null;
        var input = CommunityJson.decode("imageInput", CommunityJson.encode(value), 64 * 1024, MarketMetadata.Image.class);
        byte[] bytes = MarketDocumentFiles.read(root, input.path(), icon ? MarketImages.ICON_BYTES : MarketImages.SCREENSHOT_BYTES);
        var metadata = MarketImages.inspect(bytes, icon);
        return new MarketImage(input.path(), input.alt(), asset(bytes, metadata.mediaType(), base, files, "market-"));
    }

    private static MarketContent.Asset asset(byte[] bytes, String type, String base, Map<String, byte[]> files, String prefix) {
        String suffix = switch (type) {
            case "text/markdown" -> "md"; case "text/html" -> "html"; case "image/png" -> "png";
            case "image/jpeg" -> "jpg"; case "image/webp" -> "webp";
            default -> throw new ContractException("SCHEMA_INVALID", "/content/mediaType");
        };
        String hash = CommunityJson.sha256(bytes), name = prefix + hash + "." + suffix;
        long total = files.values().stream().mapToLong(value -> value.length).sum();
        long maximum = MarketContent.TOTAL_DOCUMENT_BYTES + MarketContent.TOTAL_IMAGE_BYTES + MarketImages.TOTAL_BYTES;
        if (!files.containsKey(name) && total + bytes.length > maximum) throw CommunityJson.limit("/assets", maximum, "bytes");
        files.putIfAbsent(name, bytes);
        return new MarketContent.Asset(name, base + name, type, bytes.length, hash);
    }

    public static Publication read(Path metadata) throws IOException {
        byte[] input;
        try (var stream = Files.newInputStream(metadata)) { input = stream.readNBytes(64 * 1024 + 1); }
        var publication = CommunityJson.decode("marketPublication", input, 64 * 1024, Publication.class);
        top.sywyar.pixivdownload.sdk.community.content.ContentLocales.validate(Map.of(publication.defaultLocale(), ""), "/defaultLocale");
        MarketLink.validate(publication.links());
        if (publication.content() != null) publication.content().validate(publication.defaultLocale());
        return publication;
    }

    public static List<MarketContent.Asset> assets(Publication publication, String base) {
        var values = new LinkedHashMap<String, MarketContent.Asset>();
        if (publication.content() != null) values.putAll(publication.content().assets());
        var images = new ArrayList<MarketImage>();
        if (publication.icon() != null) images.add(publication.icon());
        if (publication.screenshots() != null) images.addAll(publication.screenshots());
        long imageBytes = 0;
        for (var image : images) {
            if (image == null || image.asset() == null) throw new ContractException("SCHEMA_INVALID", "/market/images");
            var asset = image.asset(); asset.validate(true); imageBytes += asset.size();
            var old = values.putIfAbsent(asset.name(), asset);
            if (old != null && !old.equals(asset)) throw new ContractException("DUPLICATE_KEY", "/assets");
        }
        if (imageBytes > MarketImages.TOTAL_BYTES) throw CommunityJson.limit("/market/images", MarketImages.TOTAL_BYTES, "bytes");
        for (var asset : values.values()) if (!asset.url().equals(base + asset.name())) throw new ContractException("PATH_MISMATCH", "/content/url");
        return List.copyOf(values.values());
    }

    public static Publication verify(Path metadata, Path root, String base) throws IOException {
        var publication = read(metadata);
        assets(publication, base);
        if (publication.content() != null) {
            MarketDocumentFiles.validate(publication.content(), root, publication.defaultLocale());
        }
        var images = new ArrayList<MarketImage>();
        if (publication.icon() != null) images.add(publication.icon());
        if (publication.screenshots() != null) images.addAll(publication.screenshots());
        for (var image : images) {
            boolean icon = image == publication.icon();
            var asset = image.asset();
            byte[] bytes = MarketDocumentFiles.read(root, asset.name(), icon ? MarketImages.ICON_BYTES : MarketImages.SCREENSHOT_BYTES);
            asset.verify(bytes);
            if (!MarketImages.inspect(bytes, icon).mediaType().equals(asset.mediaType())) throw new ContractException("SCHEMA_INVALID", "/market/images");
        }
        return publication;
    }
}
