package top.sywyar.pixivdownload.douyin.client;

import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DefaultDouyinClientParserTestSupport {

    private DefaultDouyinClientParserTestSupport() { }

    static DefaultDouyinClient client(String... bodies) {
        FakeRestTemplate rest = new FakeRestTemplate();
        for (String body : bodies) {
            rest.enqueue(200, body);
        }
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow(),
                new DouyinSignedUriBuilder(), ignored -> {
                });
    }

    static DefaultDouyinClient client(FakeRestTemplate rest) {
        DouyinUrlParser parser = new DouyinUrlParser();
        return new DefaultDouyinClient(parser, rest,
                (input, cookie) -> parser.parse(input).orElseThrow(),
                new DouyinSignedUriBuilder(), ignored -> {
                });
    }

    static String mixInfo() {
        return """
                {"status_code":0,"mix_info":{"mix_name":"Mix title","author":{"nickname":"Owner"}}}
                """;
    }

    static String mixPage(int first, int count, boolean hasMore, long nextCursor) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            int id = 8000 + first + index;
            items.append("{\"aweme_id\":\"").append(id)
                    .append("\",\"desc\":\"Work ").append(id)
                    .append("\",\"video\":{\"play_addr\":{\"url_list\":[\"https://v3.douyinvod.com/")
                    .append(id).append(".mp4\"]}}}");
        }
        return "{\"status_code\":0,\"has_more\":" + (hasMore ? 1 : 0)
                + ",\"max_cursor\":" + nextCursor + ",\"aweme_list\":[" + items + "]}";
    }

    static String userPage(int first, int count, boolean hasMore, String nextCursor) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            int id = 9000 + first + index;
            items.append("{\"aweme_id\":\"").append(id)
                    .append("\",\"desc\":\"Work ").append(id)
                    .append("\",\"video\":{\"play_addr\":{\"url_list\":[\"https://v3.douyinvod.com/")
                    .append(id).append(".mp4\"]}}}");
        }
        return "{\"status_code\":0,\"has_more\":" + (hasMore ? 1 : 0)
                + ",\"max_cursor\":\"" + nextCursor + "\",\"aweme_list\":[" + items + "]}";
    }

    static String resolveTitle(String awemeJson) throws Exception {
        return client("{\"aweme_detail\":" + awemeJson + "}")
                .resolvePublicWork("https://www.douyin.com/video/title-test", null)
                .title();
    }

    static String page(String json) {
        String encoded = URLEncoder.encode(json.replace("\n", ""), StandardCharsets.UTF_8);
        return "<html><script id=\"RENDER_DATA\" type=\"application/json\">" + encoded + "</script></html>";
    }

    static void assertCode(ThrowingRunnable action, DouyinClientErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(code);
    }

    static DouyinAccount favoriteAccount() {
        return new DouyinAccount("uid-1", "sec-1", "Me", "mine");
    }

    static void assertCodeName(ThrowingRunnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code().name())
                .isEqualTo(code);
    }

    static void assertSearchHttpCode(int status, String code) {
        FakeRestTemplate rest = new FakeRestTemplate();
        int attempts = status == 429 || status >= 500 ? 3 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            rest.enqueue(status, "{}");
        }
        assertCodeName(() -> client(rest).searchWorksPage("猫", "0", 24, "sessionid=test"), code);
        assertThat(rest.requests()).hasSize(attempts);
    }

    static void assertFavoriteHttpCode(int status, String code) {
        FakeRestTemplate rest = new FakeRestTemplate();
        int attempts = status == 429 || status >= 500 ? 3 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            rest.enqueue(status, "{}");
        }
        assertCodeName(() -> client(rest).listAccountWorksPage(
                favoriteAccount(), DouyinAccountSource.FAVORITE_WORKS, "0", 20, null), code);
        assertThat(rest.requests()).hasSize(attempts);
        assertThat(rest.methods()).containsOnly("POST").hasSize(attempts);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static final class FakeRestTemplate implements OutboundHttpClient {
        private final Queue<Object> responses = new ArrayDeque<>();
        private final List<URI> requests = new ArrayList<>();
        private final List<String> cookies = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new QueuedResponse(status, body.getBytes(StandardCharsets.UTF_8)));
        }

        void enqueueNetworkFailure() {
            responses.add(new OutboundHttpTransportException("synthetic network failure"));
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            requests.add(request.uri());
            methods.add(request.method());
            cookies.add(request.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Cookie"))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst().orElse(null));
            Object next = responses.isEmpty()
                    ? new QueuedResponse(200, "{}".getBytes(StandardCharsets.UTF_8))
                    : responses.remove();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            QueuedResponse response = (QueuedResponse) next;
            return new OutboundHttpStreamResponse(
                    response.status(), "mock", Map.of(), new ByteArrayInputStream(response.body()));
        }

        @Override
        public void close() {
        }

        List<URI> requests() {
            return requests;
        }

        List<String> cookies() {
            return cookies;
        }

        List<String> methods() {
            return methods;
        }

        private record QueuedResponse(int status, byte[] body) { }
    }

    static String queryValue(URI uri, String name) {
        for (String part : uri.getRawQuery().split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && name.equals(part.substring(0, equals))) {
                return URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Required query parameter is missing");
    }
}
