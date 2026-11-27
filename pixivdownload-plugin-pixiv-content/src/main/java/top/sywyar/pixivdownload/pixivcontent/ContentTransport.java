package top.sywyar.pixivdownload.pixivcontent;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.plugin.api.http.*;

/** The ranking document and image CDN use distinct credential and response contracts. */
final class ContentTransport {
    static final int JSON_BYTES = 4 * 1024 * 1024;
    private final OutboundHttpClient http;

    ContentTransport(OutboundHttpClient http) {
        this.http = http;
    }

    String ranking(URI uri, String credential) {
        if (!"https".equals(uri.getScheme()) || !"www.pixiv.net".equals(uri.getRawAuthority())
                || !"/ranking.php".equals(uri.getRawPath()) || uri.getRawFragment() != null) {
            throw ContentFailure.invalidResponse();
        }
        return jsonDocument(uri, credential);
    }

    String suggest(String word) {
        URI uri = org.springframework.web.util.UriComponentsBuilder.fromUriString(ContentQueries.WEB + "/rpc/cps.php")
                .queryParam("keyword", "{word}").queryParam("lang", "en").encode().buildAndExpand(word).toUri();
        return jsonDocument(uri, null);
    }

    private String jsonDocument(URI uri, String credential) {
        var headers = new HashMap<String, List<String>>();
        headers.put("Accept", List.of("application/json"));
        headers.put("Referer", List.of("https://www.pixiv.net/"));
        headers.put("X-Requested-With", List.of("XMLHttpRequest"));
        if (credential != null) headers.put("Cookie", List.of(credential));
        try (var response = open(uri, headers)) {
            if (length(response) > JSON_BYTES) throw ContentFailure.tooLarge();
            byte[] bytes = response.body().readNBytes(JSON_BYTES + 1);
            if (bytes.length > JSON_BYTES) throw ContentFailure.tooLarge();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | OutboundHttpTransportException failure) {
            throw ContentFailure.transport();
        }
    }

    ResponseEntity<Resource> image(URI uri, long artworkId) {
        return image(uri, ContentQueries.WEB + "/artworks/" + artworkId);
    }

    ResponseEntity<Resource> avatar(URI uri, long userId) {
        return image(uri, ContentQueries.WEB + "/users/" + userId);
    }

    ResponseEntity<Resource> novelImage(URI uri, long novelId) {
        return image(uri, ContentQueries.WEB + "/novel/show.php?id=" + novelId);
    }

    private ResponseEntity<Resource> image(URI uri, String referer) {
        var response = cdn(uri, referer, "image/jpeg,image/png,image/gif,image/webp");
        try {
            long length = length(response);
            if (length > PixivImageTransferObserver.MAX_IMAGE_BYTES) throw ContentFailure.tooLarge();
            var body = new BufferedInputStream(response.body());
            body.mark(12);
            MediaType type = imageType(body.readNBytes(12));
            body.reset();
            var result = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff").contentType(type);
            if (length >= 0) result.contentLength(length);
            return result.body(new InputStreamResource(new LimitedImageStream(body, length,
                    PixivImageTransferObserver.MAX_IMAGE_BYTES)));
        } catch (IOException failure) {
            response.close();
            throw ContentFailure.transport();
        } catch (RuntimeException failure) {
            response.close();
            throw failure;
        }
    }

    OutboundHttpStreamResponse animationZip(URI uri, long artworkId) {
        return cdn(uri, ContentQueries.WEB + "/artworks/" + artworkId, "application/zip,application/octet-stream");
    }

    private OutboundHttpStreamResponse cdn(URI uri, String referer, String accept) {
        ContentQueries.cdnUri(uri.toString());
        // A Pixiv login cookie belongs to www.pixiv.net, never to a CDN request.
        return open(uri, Map.of("Accept", List.of(accept), "Referer", List.of(referer)));
    }

    private OutboundHttpStreamResponse open(URI uri, Map<String, List<String>> headers) {
        OutboundHttpStreamResponse response;
        try {
            response = http.exchangeStream(new OutboundHttpRequest(uri, "GET", headers, null));
        } catch (OutboundHttpTransportException failure) {
            throw ContentFailure.transport();
        }
        if (response.statusCode() != 200) {
            try (response) {
                throw ContentFailure.upstream(response.statusCode());
            }
        }
        return response;
    }

    static long length(OutboundHttpStreamResponse response) {
        List<String> values = response.headers().get("Content-Length");
        if (values == null) return -1;
        if (values.size() == 1) {
            try {
                long value = Long.parseLong(values.getFirst());
                if (value >= 0) return value;
            } catch (NumberFormatException ignored) {
                // A malformed length cannot be used to frame the downstream response.
            }
        }
        throw ContentFailure.invalidResponse();
    }

    private static MediaType imageType(byte[] h) {
        if (h.length < 12) throw ContentFailure.invalidResponse();
        if (h[0] == (byte) 0xff && h[1] == (byte) 0xd8 && h[2] == (byte) 0xff) return MediaType.IMAGE_JPEG;
        if (Arrays.equals(h, 0, 8, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, 8))
            return MediaType.IMAGE_PNG;
        var magic = new String(h, StandardCharsets.ISO_8859_1);
        if (magic.startsWith("GIF87a") || magic.startsWith("GIF89a")) return MediaType.IMAGE_GIF;
        if (magic.startsWith("RIFF") && magic.endsWith("WEBP")) return MediaType.parseMediaType("image/webp");
        throw ContentFailure.invalidResponse();
    }

    static final class TransferLimitExceeded extends IOException {}

    /** ResourceHttpMessageConverter owns and closes an image stream, including on a downstream disconnect. */
    static final class LimitedImageStream extends InputStream {
        private final InputStream source;
        private final long declared;
        private long remaining;
        private long received;

        LimitedImageStream(InputStream source, long declared, long budget) {
            this.source = source;
            this.declared = declared;
            this.remaining = declared >= 0 ? Math.min(declared, budget) : budget;
        }

        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (remaining == 0) {
                if (source.read() >= 0) {
                    if (declared < 0) throw new TransferLimitExceeded();
                    throw new IOException("Pixiv response exceeded its declared length");
                }
                return -1;
            }
            int read = source.read(bytes, offset, (int) Math.min(remaining, count));
            if (read < 0 && declared >= 0 && received < declared) throw new IOException("Incomplete Pixiv response");
            if (read > 0) {
                received += read;
                remaining -= read;
            }
            return read;
        }

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override public void close() throws IOException {
            source.close();
        }
    }
}
