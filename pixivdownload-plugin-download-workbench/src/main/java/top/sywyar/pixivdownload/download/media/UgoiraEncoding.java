package top.sywyar.pixivdownload.download.media;

import java.util.ArrayList;
import java.util.List;

/** 动图输出的受控编码参数；不接受外部 FFmpeg 参数。 */
public final class UgoiraEncoding {
    private UgoiraEncoding() {}

    public static List<String> arguments(String format, MediaOutputSettings settings) {
        List<String> args = new ArrayList<>(List.of("-an"));
        int edge = settings.getMaximumEdge();
        String scale = edge > 0
                ? "scale=w='min(iw," + edge + ")':h='min(ih," + edge + ")':force_original_aspect_ratio=decrease"
                : "";
        switch (format) {
            case "webp" -> {
                if (!scale.isEmpty()) args.addAll(List.of("-vf", scale));
                args.addAll(List.of("-vcodec", "libwebp", "-quality", Integer.toString(settings.getQuality()),
                        "-lossless", settings.isWebpLossless() ? "1" : "0", "-loop", "0", "-f", "webp"));
            }
            case "gif" -> {
                // 逐帧调色板避免为全局调色板缓存整段动画。
                args.addAll(List.of("-vf", (scale.isEmpty() ? "" : scale + ",")
                        + "split[a][b];[a]palettegen=stats_mode=single[p];[b][p]paletteuse=new=1",
                        "-loop", "0", "-f", "gif"));
            }
            case "apng" -> {
                if (!scale.isEmpty()) args.addAll(List.of("-vf", scale));
                args.addAll(List.of("-c:v", "apng", "-plays", "0", "-f", "apng"));
            }
            case "mp4" -> args.addAll(List.of("-vf", (scale.isEmpty() ? "" : scale + ",")
                            + "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-crf", Integer.toString((100 - settings.getQuality()) * 51 / 99),
                    "-movflags", "+faststart", "-f", "mp4"));
            default -> throw new IllegalArgumentException("Unsupported animation output");
        }
        return args;
    }
}
