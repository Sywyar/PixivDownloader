package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.content.MarketDocuments;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读调用者已经核验的固定源码树或明确选择的本地根，不执行构建或追随链接。 */
public final class MarketDocumentFiles {
    private MarketDocumentFiles() { }

    public record Inspection(String format, long size, String sha256, String text, List<String> images) { }

    public static byte[] read(Path root, String relative, int maximum) throws IOException {
        Path file = CommunityPaths.resolve(root, relative, false, true);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new ContractException("PATH_MISMATCH", relative);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw CommunityJson.limit(relative, maximum, "bytes");
            return bytes;
        }
    }

    public static Inspection inspect(byte[] bytes, String format) {
        return new Inspection(format, bytes.length, CommunityJson.sha256(bytes), MarketDocuments.text(bytes),
                MarketDocuments.images(bytes, format));
    }

    public static void validate(MarketContent content, Path root, String defaultLocale) throws IOException {
        if (content == null) return;
        content.validate(defaultLocale);
        var bytesByName = new LinkedHashMap<String, byte[]>();
        for (var asset : content.assets().values()) {
            boolean image = asset.mediaType().startsWith("image/");
            byte[] bytes = read(root, asset.name(), image ? MarketContent.IMAGE_BYTES : MarketContent.DOCUMENT_BYTES);
            asset.verify(bytes);
            if (image && !MarketImages.inspect(bytes, false).mediaType().equals(asset.mediaType())) {
                throw new ContractException("SCHEMA_INVALID", "/content/mediaType");
            }
            if (!image) bytesByName.put(asset.name(), bytes);
        }
        for (var group : java.util.Arrays.asList(content.readme(), content.changelog(), content.releaseNotes())) {
            if (group == null) continue;
            for (var document : group.values()) {
                List<String> images = MarketDocuments.images(bytesByName.get(document.asset().name()), document.format());
                Map<String, MarketContent.Asset> resources = document.resources() == null ? Map.of() : document.resources();
                if (!images.containsAll(resources.keySet())) throw new ContractException("PATH_MISMATCH", "/content/resources");
            }
        }
    }
}
