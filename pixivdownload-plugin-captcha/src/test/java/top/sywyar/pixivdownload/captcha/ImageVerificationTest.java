package top.sywyar.pixivdownload.captcha;

import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageVerificationTest {
    private final CaptchaDiagnostics diagnostics = new CaptchaDiagnostics(
            top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver.of(null, getClass().getClassLoader(),
                    new top.sywyar.pixivdownload.i18n.LocaleBundlePolicy() {
                        public Locale normalize(Locale value) { return Locale.US; }
                        public Locale fallbackLocale() { return Locale.US; }
                        public List<String> resourceSuffixChain(Locale value) { return List.of("en", ""); }
                    }, "i18n.web.captcha"));
    private static OperationVerificationRequest request(String attempt, boolean response, String proof) {
        return new OperationVerificationRequest("test:submit", "subject", "{}", attempt, "revision", "", response, proof);
    }
    private static ImageChallengeStore.Images known(String answer, int bytes) {
        return new ImageChallengeStore.Images(answer, List.of(new byte[bytes]), 600, 360, 110);
    }
    @Test @DisplayName("真实字符与滑块图片可解码，挑战只公开短期图片地址")
    void generatesRealImages() throws Exception {
        try (var store = new ImageChallengeStore()) {
            for (boolean slider : List.of(false, true)) {
                var provider = new ImageVerification(store, slider, diagnostics);
                var result = provider.verify(request("attempt", false, ""));
                assertEquals(OperationVerificationResult.Status.CHALLENGE, result.status());
                var value = new JSONObject(result.challenge());
                assertEquals(slider ? Set.of("id", "expiresAt", "image", "width", "height", "piece", "pieceWidth")
                        : Set.of("id", "expiresAt", "image", "width", "height", "length"), value.keySet());
                assertTrue(result.challenge().length() < 1024);
                String id = value.getString("id");
                var background = ImageIO.read(new ByteArrayInputStream(store.image(id, 0)));
                assertEquals(value.getInt("width"), background.getWidth());
                assertEquals(value.getInt("height"), background.getHeight());
                if (slider) {
                    var piece = ImageIO.read(new ByteArrayInputStream(store.image(id, 1)));
                    assertEquals(value.getInt("pieceWidth"), piece.getWidth());
                    assertEquals(background.getHeight(), piece.getHeight());
                }
                var controller = new CaptchaImageController(store);
                var response = controller.image(id, 0);
                assertEquals(200, response.getStatusCode().value());
                assertEquals("no-store", response.getHeaders().getCacheControl());
                assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
                assertEquals(404, controller.image(id, 2).getStatusCode().value());
                store.consume(id, provider.policyId(), "attempt");
                assertEquals(404, controller.image(id, 0).getStatusCode().value());
            }
        }
    }
    @Test @DisplayName("答案绑定策略和尝试，正确与错误提交都只能消费一次")
    void answersAreBoundAndSingleUse() {
        try (var store = new ImageChallengeStore()) {
            for (boolean slider : List.of(false, true)) {
                var provider = new ImageVerification(store, slider, diagnostics);
                String id = store.create(provider.policyId(), "attempt", () -> known(slider ? "200" : "ABCD2", 10)).id();
                String proof = new JSONObject().put("id", id).put(slider ? "position" : "answer", slider ? 200 : "abcd2").toString();
                assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request("wrong", true, proof)).status());
                assertEquals(OperationVerificationResult.Status.REJECTED,
                        new ImageVerification(store, !slider, diagnostics).verify(request("attempt", true, proof)).status());
                assertEquals(OperationVerificationResult.Status.APPROVED, provider.verify(request("attempt", true, proof)).status());
                assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request("attempt", true, proof)).status());
                for (Object invalid : slider ? List.of(-1, 700, 210, "NaN", "Infinity") : List.of("", "ABCDE", "ABCD22")) {
                    String next = store.create(provider.policyId(), "attempt", () -> known(slider ? "200" : "ABCD2", 10)).id();
                    String wrong = new JSONObject().put("id", next).put(slider ? "position" : "answer", invalid).toString();
                    assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request("attempt", true, wrong)).status());
                    assertNull(store.image(next, 0), "wrong answer consumes this attempt");
                }
                for (String invalid : List.of("", "{", "x".repeat(257), "{\"id\":\"bad\"}"))
                    assertEquals(OperationVerificationResult.Status.REJECTED, provider.verify(request("attempt", true, invalid)).status());
            }
        }
    }
    @Test @DisplayName("过期、容量预算和卸载释放图片，失败后仍能签发")
    void expiryCapacityAndClose() {
        final long[] now = {0};
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        try (var store = new ImageChallengeStore(clock)) {
            String first = store.create("policy", "attempt", () -> known("ABCD2", 1)).id();
            for (int i = 1; i < ImageChallengeStore.MAX_ENTRIES; i++) store.create("policy", "attempt", () -> known("ABCD2", 1));
            assertThrows(IllegalStateException.class, () -> store.create("policy", "attempt", () -> fail("capacity checked before generating")));
            now[0] = ImageChallengeStore.LIFETIME_MILLIS;
            assertNull(store.image(first, 0));
            assertNull(store.consume(first, "policy", "attempt"));
            for (int i = 0; i < ImageChallengeStore.MAX_TOTAL_BYTES / ImageChallengeStore.MAX_IMAGE_BYTES; i++)
                store.create("policy", "attempt", () -> known("ABCD2", ImageChallengeStore.MAX_IMAGE_BYTES));
            assertThrows(IllegalStateException.class, () -> store.create("policy", "attempt", () -> fail("byte capacity checked before generating")));
            now[0] += ImageChallengeStore.LIFETIME_MILLIS;
            assertThrows(IllegalStateException.class, () -> store.create("policy", "attempt", () -> known("ABCD2", ImageChallengeStore.MAX_IMAGE_BYTES + 1)));
            String last = store.create("policy", "attempt", () -> known("ABCD2", 10)).id();
            store.close();
            assertNull(store.image(last, 0));
            assertThrows(IllegalStateException.class, () -> store.create("policy", "attempt", () -> known("ABCD2", 1)));
            try (var replacement = new ImageChallengeStore()) { assertNotEquals(store.generation(), replacement.generation()); }
        }
    }
    @Test @DisplayName("并发生成有界，卸载期间完成的图片不会回填缓存")
    void concurrentGenerationAndUnload() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try (var store = new ImageChallengeStore()) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> store.create("policy", "attempt", () -> {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return known("ABCD2", 10);
            })));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("CAPTCHA_IMAGE_BUSY", assertThrows(IllegalStateException.class,
                    () -> store.create("policy", "attempt", () -> fail("must not generate"))).getMessage());
            store.close(); release.countDown();
            for (var job : jobs) assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> job.get(10, TimeUnit.SECONDS)).getCause());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
