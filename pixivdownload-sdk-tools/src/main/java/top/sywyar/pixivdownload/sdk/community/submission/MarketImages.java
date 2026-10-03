package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.content.MarketImageBytes;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** SDK 路径校验与共享静态图片解码的适配。 */
public final class MarketImages {
    public static final int ICON_BYTES = MarketImageBytes.ICON_BYTES;
    public static final int SCREENSHOT_BYTES = MarketImageBytes.SCREENSHOT_BYTES;
    public static final int TOTAL_BYTES = MarketImageBytes.TOTAL_BYTES;
    public static final int ICON_DIMENSION = MarketImageBytes.ICON_DIMENSION;
    public static final int SCREENSHOT_DIMENSION = MarketImageBytes.SCREENSHOT_DIMENSION;
    private MarketImages() { }

    public record Metadata(String sha256, long size, String mediaType, int width, int height) { }

    public static Metadata read(InputStream source, boolean icon) throws IOException {
        return inspect(source.readNBytes((icon ? ICON_BYTES : SCREENSHOT_BYTES) + 1), icon);
    }

    public static Metadata inspect(byte[] bytes, boolean icon) throws IOException {
        var image = MarketImageBytes.inspect(bytes, icon);
        return new Metadata(image.sha256(), image.size(), image.mediaType(), image.width(), image.height());
    }

    /** PR/head 的实际文件由调用方冻结；路径与图片属性重新从其字节核对。 */
    public static List<Metadata> validate(Path root, String accountId, String pluginId, String version,
                                         MarketMetadata market) throws IOException {
        var inputs = new ArrayList<MarketMetadata.Image>();
        if (market.icon() != null) inputs.add(market.icon());
        if (market.screenshots() != null) inputs.addAll(market.screenshots());
        var output = new ArrayList<Metadata>();
        long total = 0;
        for (int i = 0; i < inputs.size(); i++) {
            var image = inputs.get(i);
            Path file = CommunityPaths.resolve(root, image.path(), false, true);
            Metadata metadata;
            try (InputStream input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                metadata = read(input, market.icon() != null && i == 0);
            }
            total += metadata.size();
            if (total > TOTAL_BYTES) throw CommunityJson.limit("/market/images", TOTAL_BYTES, "bytes-total");
            String extension = metadata.mediaType().equals("image/jpeg") ? "jpg" : metadata.mediaType().substring(6);
            String expected = "assets/" + accountId + "/" + pluginId + "/" + version + "/" + metadata.sha256() + "." + extension;
            if (!expected.equals(image.path())) throw new ContractException("PATH_MISMATCH", image.path());
            if (image.asset() != null && (image.asset().size() != metadata.size()
                    || !image.asset().sha256().equals(metadata.sha256()) || !image.asset().mediaType().equals(metadata.mediaType())
                    || !image.asset().name().equals("market-" + metadata.sha256() + "." + extension))) {
                throw new ContractException("REFERENCE_MISMATCH", image.path());
            }
            output.add(metadata);
        }
        return List.copyOf(output);
    }

}
