package top.sywyar.pixivdownload.pixivcontent;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

/** Converts Pixiv's playback archive and frame timings into one directly playable animation. */
final class UgoiraAnimation {
    private static final int MAX_FRAMES = 500;
    private static final int ZIP_BYTES = 32 * 1024 * 1024;
    private static final int EXTRACTED_BYTES = 32 * 1024 * 1024;
    private static final int FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EDGE = 4096;
    private static final long FRAME_PIXELS = 4_194_304;
    private static final long TOTAL_PIXELS = 240_000_000;
    private final Semaphore slots = new Semaphore(2);
    private final ContentQueries queries;
    private final ContentTransport transport;

    UgoiraAnimation(ContentQueries queries, ContentTransport transport) {
        this.queries = queries;
        this.transport = transport;
    }

    ResponseEntity<Resource> read(long artworkId, String credential) {
        if (!slots.tryAcquire()) throw ContentFailure.animationBusy();
        boolean handedOff = false;
        try {
            JsonNode metadata = queries.body(URI.create(ContentQueries.WEB + "/ajax/illust/" + artworkId
                    + "/ugoira_meta"), credential);
            URI source = ContentQueries.cdnUri(ContentQueries.text(metadata, "src"));
            String format = switch (ContentQueries.text(metadata, "mime_type")) {
                case "image/jpeg" -> "JPEG";
                case "image/png" -> "PNG";
                default -> throw ContentFailure.invalidResponse();
            };
            var timings = timings(metadata.path("frames"));
            Map<String, byte[]> images;
            try (var response = transport.animationZip(source, artworkId)) {
                long length = ContentTransport.length(response);
                if (length > ZIP_BYTES) throw ContentFailure.tooLarge();
                images = unpack(response.body(), length, timings);
            }
            var frames = new ArrayList<Frame>();
            Dimension canvas = null;
            for (var timing : timings.entrySet()) {
                var sourceFrame = new SourceFrame(format, images.get(timing.getKey()));
                Dimension size = sourceFrame.size();
                if (canvas == null) canvas = size;
                if (!canvas.equals(size)) throw ContentFailure.invalidResponse();
                addFrames(frames, sourceFrame, timing.getValue());
                if ((long) size.width * size.height * frames.size() > TOTAL_PIXELS) throw ContentFailure.tooLarge();
            }
            var stream = new ApngInputStream(frames, canvas, slots::release);
            var result = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType("image/apng"))
                    .<Resource>body(new InputStreamResource(stream));
            handedOff = true;
            return result;
        } catch (ContentTransport.TransferLimitExceeded excessive) {
            throw ContentFailure.tooLarge();
        } catch (ZipException | EOFException malformed) {
            throw ContentFailure.invalidResponse();
        } catch (IOException | OutboundHttpTransportException failure) {
            throw ContentFailure.transport();
        } finally {
            if (!handedOff) slots.release();
        }
    }

    private static Map<String, Integer> timings(JsonNode frames) {
        if (!frames.isArray() || frames.isEmpty()) throw ContentFailure.invalidResponse();
        if (frames.size() > MAX_FRAMES) throw ContentFailure.tooLarge();
        var timings = new LinkedHashMap<String, Integer>();
        for (var frame : frames) {
            String name = ContentQueries.text(frame, "file");
            JsonNode delay = frame.path("delay");
            if (name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || !delay.isIntegralNumber() || !delay.canConvertToInt() || delay.asInt() < 0
                    || timings.put(name, delay.asInt()) != null) throw ContentFailure.invalidResponse();
        }
        return timings;
    }

    private static Map<String, byte[]> unpack(InputStream raw, long length, Map<String, Integer> timings)
            throws IOException {
        // Count all bytes, including ZIP headers and the central directory, even with chunked HTTP.
        var bounded = new ContentTransport.LimitedImageStream(raw, length, ZIP_BYTES);
        var images = new HashMap<String, byte[]>();
        int remaining = EXTRACTED_BYTES;
        try (var zip = new ZipInputStream(bounded)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !timings.containsKey(name) || images.containsKey(name)) {
                    throw ContentFailure.invalidResponse();
                }
                int limit = Math.min(remaining, FRAME_BYTES);
                if (entry.getSize() > limit) throw ContentFailure.tooLarge();
                byte[] bytes = zip.readNBytes(limit + 1);
                if (bytes.length > limit) throw ContentFailure.tooLarge();
                images.put(name, bytes);
                remaining -= bytes.length;
            }
            if (images.size() != timings.size()) throw ContentFailure.invalidResponse();
            bounded.transferTo(OutputStream.nullOutputStream());
        } catch (IllegalArgumentException malformedName) {
            throw ContentFailure.invalidResponse();
        }
        return images;
    }

    // APNG delay numerator and denominator are unsigned 16-bit values. Split long holds exactly.
    private static void addFrames(List<Frame> frames, SourceFrame source, int millis) {
        while (millis > 65_535) {
            int seconds = Math.min(millis / 1000, 65_535);
            frames.add(new Frame(source, seconds, 1));
            millis -= seconds * 1000;
            if (millis == 0) return;
        }
        frames.add(new Frame(source, millis, 1000));
    }

    record Frame(SourceFrame source, int numerator, int denominator) {}

    record SourceFrame(String format, byte[] bytes) {
        Dimension size() {
            return inspect(false).size();
        }

        BufferedImage decode() {
            return inspect(true).image();
        }

        private Decoded inspect(boolean decode) {
            var reader = ImageIO.getImageReadersByFormatName(format).next();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw ContentFailure.invalidResponse();
                if (width > MAX_EDGE || height > MAX_EDGE || (long) width * height > FRAME_PIXELS) {
                    throw ContentFailure.tooLarge();
                }
                return new Decoded(new Dimension(width, height), decode ? reader.read(0) : null);
            } catch (IOException | IllegalArgumentException invalid) {
                throw ContentFailure.invalidResponse();
            } finally {
                reader.dispose();
            }
        }
    }

    private record Decoded(Dimension size, BufferedImage image) {}
}
