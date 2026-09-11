package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** 从同一有界字节完整解码静态图片；保留原格式与原始摘要，不转码。 */
public final class MarketImages {
    public static final int ICON_BYTES = 256 * 1024;
    public static final int SCREENSHOT_BYTES = 2 * 1024 * 1024;
    public static final int TOTAL_BYTES = 8 * 1024 * 1024;
    public static final int ICON_DIMENSION = 1024;
    public static final int SCREENSHOT_DIMENSION = 4096;
    private MarketImages() { }

    public record Metadata(String sha256, long size, String mediaType, int width, int height) { }

    public static Metadata read(InputStream source, boolean icon) throws IOException {
        int maximum = icon ? ICON_BYTES : SCREENSHOT_BYTES;
        return inspect(source.readNBytes(maximum + 1), icon);
    }

    public static Metadata inspect(byte[] bytes, boolean icon) throws IOException {
        int maximum = icon ? ICON_BYTES : SCREENSHOT_BYTES;
        if (bytes.length > maximum) throw CommunityJson.limit("/market/images", maximum, "bytes");
        String extension = container(bytes);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                boolean[] warningSeen = {false};
                reader.addIIOReadWarningListener((unused, warning) -> warningSeen[0] = true);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase(extension) || extension.equals("jpg") && format.equalsIgnoreCase("jpeg"))) throw invalid();
                if (reader.getNumImages(true) != 1) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int dimension = icon ? ICON_DIMENSION : SCREENSHOT_DIMENSION;
                if (width < 1 || height < 1 || width > dimension || height > dimension) {
                    throw CommunityJson.limit("/market/images", dimension, "pixels-per-dimension");
                }
                if (extension.equals("png")) verifyPngPayload(bytes, width, height);
                var decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height || warningSeen[0]) throw invalid();
                decoded.flush();
                return new Metadata(CommunityJson.sha256(bytes), bytes.length,
                        extension.equals("jpg") ? "image/jpeg" : "image/" + extension, width, height);
            } catch (ContractException e) { throw e; }
            catch (RuntimeException | IOException e) { throw invalid(); }
            finally { reader.dispose(); }
        }
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
            output.add(metadata);
        }
        return List.copyOf(output);
    }

    private static String container(byte[] data) {
        if (data.length >= 8 && ByteBuffer.wrap(data).getLong() == 0x89504e470d0a1a0aL) {
            png(data); return "png";
        }
        if (data.length >= 12 && ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")) {
            webp(data); return "webp";
        }
        if (data.length >= 4 && (data[0] & 255) == 255 && (data[1] & 255) == 216) {
            jpeg(data); return "jpg";
        }
        throw invalid();
    }

    private static void png(byte[] data) {
        int position = 8, headers = 0, images = 0;
        while (position + 12 <= data.length) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(data, position, 4).getInt());
            long end = position + 12L + length;
            if (end > data.length) throw invalid();
            String type = new String(data, position + 4, 4, StandardCharsets.US_ASCII);
            if (!type.matches("[A-Za-z]{4}") || Set.of("acTL", "fcTL", "fdAT").contains(type)) throw invalid();
            CRC32 crc = new CRC32();
            crc.update(data, position + 4, (int) length + 4);
            if (crc.getValue() != Integer.toUnsignedLong(ByteBuffer.wrap(data, (int) end - 4, 4).getInt())) throw invalid();
            if (position == 8 && !type.equals("IHDR")) throw invalid();
            if (type.equals("IHDR") && (++headers != 1 || length != 13 || position != 8)) throw invalid();
            if (type.equals("IDAT")) images++;
            if (Character.isUpperCase(type.charAt(0)) && !Set.of("IHDR", "PLTE", "IDAT", "IEND").contains(type)) throw invalid();
            if (type.equals("IEND")) {
                if (length != 0 || end != data.length || headers != 1 || images == 0) throw invalid();
                return;
            }
            position = (int) end;
        }
        throw invalid();
    }

    private static void webp(byte[] data) {
        if (Integer.toUnsignedLong(ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()) + 8 != data.length) throw invalid();
        int position = 12, frames = 0, extended = 0;
        while (position + 8 <= data.length) {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(data, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            long end = position + 8L + size + (size & 1);
            if (end > data.length) throw invalid();
            if (ascii(data, position, "ANIM") || ascii(data, position, "ANMF")) throw invalid();
            if (ascii(data, position, "VP8X")) {
                if (++extended > 1 || position != 12 || size != 10 || (data[position + 8] & 2) != 0) throw invalid();
            }
            if (ascii(data, position, "VP8 ") || ascii(data, position, "VP8L")) frames++;
            if ((size & 1) != 0 && data[(int) end - 1] != 0) throw invalid();
            position = (int) end;
        }
        if (position != data.length || frames != 1) throw invalid();
    }

    /** ImageIO 可能在像素读够后忽略缺失的 zlib 尾部，因此独立读完同一 IDAT 流并核对扫描线总量。 */
    private static void verifyPngPayload(byte[] data, int width, int height) {
        int depth = data[24] & 255, color = data[25] & 255, interlace = data[28] & 255;
        int channels = switch (color) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 0; };
        if (channels == 0 || !Set.of(1, 2, 4, 8, 16).contains(depth) || interlace > 1) throw invalid();
        long expected = 0;
        if (interlace == 0) expected = ((width * (long) channels * depth + 7) / 8 + 1) * height;
        else {
            int[] x = {0, 4, 0, 2, 0, 1, 0}, y = {0, 0, 4, 0, 2, 0, 1};
            int[] dx = {8, 8, 4, 4, 2, 2, 1}, dy = {8, 8, 8, 4, 4, 2, 2};
            for (int i = 0; i < 7; i++) {
                int columns = Math.max(0, (width - x[i] + dx[i] - 1) / dx[i]);
                int rows = Math.max(0, (height - y[i] + dy[i] - 1) / dy[i]);
                if (columns > 0 && rows > 0) expected += ((columns * (long) channels * depth + 7) / 8 + 1) * rows;
            }
        }
        var compressed = new ByteArrayOutputStream();
        boolean started = false, ended = false;
        for (int position = 8; position < data.length;) {
            int length = ByteBuffer.wrap(data, position, 4).getInt();
            if (ascii(data, position + 4, "IDAT")) {
                if (ended) throw invalid();
                started = true;
                compressed.write(data, position + 8, length);
            } else if (started) ended = true;
            position += length + 12;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed.toByteArray());
            byte[] buffer = new byte[8192];
            long total = 0;
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer, 0, (int) Math.min(buffer.length, expected - total + 1));
                if (read == 0 && !inflater.finished()) throw invalid();
                total += read;
                if (total > expected) throw invalid();
            }
            if (total != expected || inflater.getRemaining() != 0) throw invalid();
        } catch (DataFormatException e) { throw invalid(); }
        finally { inflater.end(); }
    }

    private static void jpeg(byte[] data) {
        int position = 2;
        boolean scan = false, entropy = false;
        while (position < data.length) {
            if ((data[position++] & 255) != 255) {
                if (entropy) continue;
                throw invalid();
            }
            while (position < data.length && (data[position] & 255) == 255) position++;
            if (position >= data.length) throw invalid();
            int marker = data[position++] & 255;
            if (entropy && (marker == 0 || marker >= 208 && marker <= 215)) continue;
            entropy = false;
            if (marker == 217) {
                if (!scan || position != data.length) throw invalid();
                return;
            }
            if (marker == 0 || marker == 216 || marker >= 208 && marker <= 215 || position + 2 > data.length) throw invalid();
            if (marker == 1) continue;
            int size = Short.toUnsignedInt(ByteBuffer.wrap(data, position, 2).getShort());
            if (size < 2 || position + (long) size > data.length) throw invalid();
            position += size;
            if (marker == 218) { scan = true; entropy = true; }
        }
        throw invalid();
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != value.charAt(i)) return false;
        return true;
    }

    private static ContractException invalid() { return new ContractException("SCHEMA_INVALID", "/market/images"); }
}
