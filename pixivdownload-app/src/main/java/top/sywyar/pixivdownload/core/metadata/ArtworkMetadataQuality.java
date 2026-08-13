package top.sywyar.pixivdownload.core.metadata;

/** 下载链路中用于阻止已知占位值覆盖真实作品元数据的最小判定。 */
public final class ArtworkMetadataQuality {

    private ArtworkMetadataQuality() {
    }

    public static boolean isMeaningfulTitle(long artworkId, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String compact = title.replaceAll("\\s+", "");
        return !compact.equals("作品" + artworkId)
                && !compact.equalsIgnoreCase("Artwork" + artworkId);
    }
}
