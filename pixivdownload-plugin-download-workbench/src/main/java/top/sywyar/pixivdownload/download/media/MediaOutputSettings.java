package top.sywyar.pixivdownload.download.media;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

/** 单次作品下载或维护操作的输出参数，不绑定全局配置。 */
public class MediaOutputSettings {
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
    public static MediaOutputSettings fromJson(com.fasterxml.jackson.databind.JsonNode node) {
        var settings = new MediaOutputSettings();
        if (node.hasNonNull("imageFormats")) settings.setImageFormats(node.path("imageFormats").asText());
        if (node.hasNonNull("ugoiraFormats")) settings.setUgoiraFormats(node.path("ugoiraFormats").asText());
        settings.setQuality(readInteger(node.path("mediaQuality"), DEFAULT_QUALITY));
        settings.setMaximumEdge(readInteger(node.path("mediaMaximumEdge"), 0));
        settings.setWebpLossless(readBoolean(node.path("mediaWebpLossless")));
        return settings;
    }

    public static int readInteger(com.fasterxml.jackson.databind.JsonNode value, int defaultValue) {
        if (value == null || value.isNull() || value.isMissingNode()) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid media integer");
        return value.intValue();
    }

    public static boolean readBoolean(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (!value.isBoolean()) throw new IllegalArgumentException("Invalid media boolean");
        return value.booleanValue();
    }

    public static List<String> parseFormats(String value, List<String> allowed) {
        if (!GuiConfigFieldType.validMultiSelection(value, allowed)) throw new IllegalArgumentException("Invalid media formats");
        return List.of(value.split(","));
    }
}
