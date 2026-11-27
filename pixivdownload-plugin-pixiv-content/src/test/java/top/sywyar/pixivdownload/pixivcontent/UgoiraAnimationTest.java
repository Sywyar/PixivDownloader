package top.sywyar.pixivdownload.pixivcontent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;

import static org.assertj.core.api.Assertions.*;

class UgoiraAnimationTest {
    private static final String META = """
            {"error":false,"body":{"src":"https://i.pximg.net/play.zip","mime_type":"image/png",
            "frames":[{"file":"first.png","delay":17},{"file":"second.png","delay":65537}]}}
            """;
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private static final int FIRST = 0x7f123456;
    private static final int SECOND = 0xffa5c328;

    @Test void producesPlayableFramesInManifestOrderAndPreservesExactMilliseconds() throws IOException {
        byte[] source = zip("second.png", png(SECOND), "first.png", png(FIRST));
        var closed = new AtomicBoolean();
        var service = service(META, source, closed);
        var response = service.read(23, "fixture credential");
        assertThat(closed).isTrue();
        assertThat(response.getBody()).isExactlyInstanceOf(InputStreamResource.class);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/apng");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        try (var stream = response.getBody().getInputStream()) {
            var decoded = decode(stream.readAllBytes());
            assertThat(decoded).containsExactly(new Playback(FIRST, 17), new Playback(SECOND, 65_000),
                    new Playback(SECOND, 537));
        }
    }

    @Test void twoOpenResponsesUseBothSlotsAndClosingOrFinishingAStreamReleasesItsSlot() throws IOException {
        var service = service(META, zip("first.png", png(FIRST), "second.png", png(SECOND)), new AtomicBoolean());
        try (var first = service.read(23, "fixture credential").getBody().getInputStream();
             var second = service.read(23, "fixture credential").getBody().getInputStream()) {
            assertThatThrownBy(() -> service.read(23, "fixture credential")).hasMessage("PIXIV_ANIMATION_BUSY");
            first.read();
            first.close();
            try (var third = service.read(23, "fixture credential").getBody().getInputStream()) {
                third.readAllBytes();
                try (var fourth = service.read(23, "fixture credential").getBody().getInputStream()) {
                    assertThat(fourth.read()).isEqualTo(137);
                }
            }
        }
    }

    @Test void rejectsUnexpectedMissingOrOversizedEntriesAndReturnsSlotsOnFailure() throws IOException {
        for (var source : List.of(zip("first.png", png(FIRST), "../second.png", png(SECOND)),
                zip("first.png", png(FIRST)),
                zip("first.png", new byte[8 * 1024 * 1024 + 1], "second.png", png(SECOND)))) {
            var closed = new AtomicBoolean();
            var service = service(META, source, closed);
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThatThrownBy(() -> service.read(23, "fixture credential"))
                        .hasMessageMatching("PIXIV_RESPONSE_(INVALID|TOO_LARGE)");
                assertThat(closed).isTrue();
            }
        }
    }

    @Test void rejectsWrongFirstFrameOrUntrustedCdnBeforeSendingAnimationBytes() throws IOException {
        var service = service(META, zip("first.png", new byte[30], "second.png", png(SECOND)), new AtomicBoolean());
        assertThatThrownBy(() -> service.read(23, "fixture credential")).hasMessage("PIXIV_RESPONSE_INVALID");
        var closed = new AtomicBoolean();
        var invalid = service(META.replace("https://i.pximg.net/", "https://i.pximg.net.evil.example/"),
                new byte[0], closed);
        assertThatThrownBy(() -> invalid.read(23, "fixture credential")).hasMessage("PIXIV_RESPONSE_INVALID");
        assertThat(closed).isFalse();
    }

    @Test void boundsBothDimensionsAndTotalPixelWorkBeforeDecoding() throws IOException {
        for (int edge : List.of(4097, 2048)) {
            byte[] frame = png(FIRST);
            var bytes = ByteBuffer.wrap(frame);
            bytes.putInt(16, edge).putInt(20, edge);
            bytes.putInt(29, checksum("IHDR", java.util.Arrays.copyOfRange(frame, 16, 29)));
            // A long hold needs multiple APNG frames to retain the exact delay.
            String metadata = META.replace("\"delay\":17", "\"delay\":2147483647")
                    .replace("\"delay\":65537", "\"delay\":2147483647");
            var service = service(metadata, zip("first.png", frame, "second.png", frame), new AtomicBoolean());
            assertThatThrownBy(() -> service.read(23, "fixture credential")).hasMessage("PIXIV_RESPONSE_TOO_LARGE");
        }
    }

    @Test void aCorruptLaterFrameTerminatesTheStreamAndReleasesItsSlot() throws IOException {
        byte[] headerOnly = java.util.Arrays.copyOf(png(SECOND), 33);
        var service = service(META, zip("first.png", png(FIRST), "second.png", headerOnly), new AtomicBoolean());
        try (var held = service.read(23, "fixture credential").getBody().getInputStream()) {
            for (int i = 0; i < 3; i++) {
                try (var stream = service.read(23, "fixture credential").getBody().getInputStream()) {
                    assertThatIOException().isThrownBy(stream::readAllBytes);
                }
            }
        }
    }

    private static UgoiraAnimation service(String metadata, byte[] archive, AtomicBoolean closed) {
        var queries = new ContentQueries((uri, cookie) -> {
            assertThat(uri.toString()).isEqualTo("https://www.pixiv.net/ajax/illust/23/ugoira_meta");
            assertThat(cookie).isEqualTo("fixture credential");
            return metadata;
        }, null, new JsonMapper());
        var http = new OutboundHttpClient() {
            @Override public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
                assertThat(request.uri().toString()).isEqualTo("https://i.pximg.net/play.zip");
                assertThat(request.headers()).doesNotContainKey("Cookie")
                        .containsEntry("Referer", List.of("https://www.pixiv.net/artworks/23"));
                return new OutboundHttpStreamResponse(200, "", Map.of(), new ByteArrayInputStream(archive) {
                    @Override public void close() { closed.set(true); }
                });
            }

            @Override public void close() {}
        };
        return new UgoiraAnimation(queries, new ContentTransport(http));
    }

    private static byte[] png(int color) throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        image.setRGB(1, 0, color);
        image.setRGB(0, 1, color);
        image.setRGB(1, 1, color);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", buffer);
        return buffer.toByteArray();
    }

    private static byte[] zip(Object... entries) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private record Playback(int pixel, int milliseconds) {}

    // Recover ordinary PNGs from the animation data so the JDK PNG decoder independently verifies every frame.
    private static List<Playback> decode(byte[] animation) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(animation));
        assertThat(input.readNBytes(8)).isEqualTo(SIGNATURE);
        int sequence = 0;
        int duration = 0;
        byte[] header = null;
        var frames = new ArrayList<Playback>();
        var png = new ByteArrayOutputStream();
        while (input.available() > 0) {
            int size = input.readInt();
            String name = new String(input.readNBytes(4), StandardCharsets.US_ASCII);
            byte[] data = input.readNBytes(size);
            int crc = input.readInt();
            assertThat(crc).isEqualTo(checksum(name, data));
            var fields = ByteBuffer.wrap(data);
            switch (name) {
                case "IHDR" -> header = data;
                case "acTL" -> {
                    assertThat(fields.getInt()).isEqualTo(3);
                    assertThat(fields.getInt()).isZero();
                }
                case "fcTL" -> {
                    if (png.size() > 0) frames.add(decodeFrame(png, duration));
                    png.reset();
                    png.write(SIGNATURE);
                    writeChunk(png, "IHDR", header);
                    assertThat(fields.getInt()).isEqualTo(sequence++);
                    fields.position(20);
                    duration = Short.toUnsignedInt(fields.getShort()) * 1000 / Short.toUnsignedInt(fields.getShort());
                }
                case "fdAT" -> {
                    assertThat(fields.getInt()).isEqualTo(sequence++);
                    writeChunk(png, "IDAT", java.util.Arrays.copyOfRange(data, 4, data.length));
                }
                case "IDAT" -> writeChunk(png, "IDAT", data);
                case "IEND" -> frames.add(decodeFrame(png, duration));
                default -> throw new AssertionError(name);
            }
        }
        return frames;
    }

    private static Playback decodeFrame(ByteArrayOutputStream png, int duration) throws IOException {
        writeChunk(png, "IEND", new byte[0]);
        var image = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
        return new Playback(image.getRGB(1, 1), duration);
    }

    private static void writeChunk(ByteArrayOutputStream buffer, String name, byte[] data) throws IOException {
        var stream = new DataOutputStream(buffer);
        stream.writeInt(data.length);
        stream.writeBytes(name);
        stream.write(data);
        stream.writeInt(checksum(name, data));
    }

    private static int checksum(String name, byte[] data) {
        var crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        return (int) crc.getValue();
    }
}
