package top.sywyar.pixivdownload.download.media;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

public class MediaOutputSettings {
    public static final String PREFIX = "download-workbench.media";
    public static final List<String> IMAGE_FORMATS = List.of("original", "png", "jpg", "webp");
    public static final List<String> UGOIRA_FORMATS = List.of("webp", "gif", "apng", "mp4", "zip");
    public static final String DEFAULT_IMAGE_FORMATS = "original";
    public static final String DEFAULT_UGOIRA_FORMATS = "webp";
    public static final int DEFAULT_QUALITY = 90;

    private String imageFormats = DEFAULT_IMAGE_FORMATS;
    private String ugoiraFormats = DEFAULT_UGOIRA_FORMATS;
    private int quality = DEFAULT_QUALITY;
    private boolean webpLossless;
    private int maximumEdge;

    public String getImageFormats() { return imageFormats; }
    public void setImageFormats(String value) {
        parseFormats(value, IMAGE_FORMATS);
        imageFormats = value;
    }
    public String getUgoiraFormats() { return ugoiraFormats; }
    public void setUgoiraFormats(String value) {
        parseFormats(value, UGOIRA_FORMATS);
        ugoiraFormats = value;
    }
    public int getQuality() { return quality; }
    public void setQuality(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("Invalid media quality");
        quality = value;
    }
    public boolean isWebpLossless() { return webpLossless; }
    public void setWebpLossless(boolean value) { webpLossless = value; }
    public int getMaximumEdge() { return maximumEdge; }
    public void setMaximumEdge(int value) {
        if (value < 0 || value > 16_383) throw new IllegalArgumentException("Invalid media maximum edge");
        maximumEdge = value;
    }
    public static List<String> parseFormats(String value, List<String> allowed) {
        if (!GuiConfigFieldType.validMultiSelection(value, allowed)) throw new IllegalArgumentException("Invalid media formats");
        return List.of(value.split(","));
    }
}
