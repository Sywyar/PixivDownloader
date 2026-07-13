package top.sywyar.pixivdownload.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class EdgeTtsClientTest {

    private static final OutboundProxySettings NO_PROXY = new OutboundProxySettings() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public String getHost() {
            return "127.0.0.1";
        }

        @Override
        public int getPort() {
            return 7890;
        }
    };
    private static final MessageResolver MESSAGES = new MapMessageResolver(Map.of(
            "tts.edge.error.connect-failed", "无法连接 Edge TTS 服务",
            "tts.edge.error.synthesis-failed", "Edge TTS 合成失败",
            "tts.edge.error.timeout", "Edge TTS 合成超时",
            "tts.edge.error.interrupted", "Edge TTS 合成已中断",
            "tts.edge.error.empty-audio", "Edge TTS 未返回音频",
            "tts.edge.error.stopping", "Edge TTS 服务正在停止",
            "tts.edge.error.handshake-rejected", "Edge TTS 握手被拒绝"));

    @Test
    @DisplayName("正常合成等待关闭帧完成并排空监听器后才返回")
    void waitsForCloseAndListenerTerminalBeforeReturning() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = TestWebSocket.withPendingClose();
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);

        connector.awaitConnect();
        connector.succeed(socket);
        socket.awaitTextFrames(2);
        byte[] expected = {3, 1, 4, 1, 5};
        connector.listener().onBinary(socket, ByteBuffer.wrap(audioFrame(expected)), true);
        connector.listener().onText(socket, "Path:turn.end", true);
        socket.awaitCloseStarted();

        assertTrue(invocation.thread().isAlive(), "sendClose 完成前不应释放合成调用");
        assertEquals(0, socket.abortCount.get());

        socket.completeClose();
        invocation.join();

        assertArrayEquals(expected, invocation.result.get());
        assertEquals(1, socket.closeCount.get());
        assertEquals(1, socket.abortCount.get());
        assertFalse(invocation.thread().isAlive());
        client.close();
    }

    @Test
    @DisplayName("停止会等待已进入的回调并让停止后的回调全部失效")
    void closeDrainsEnteredCallbackAndRejectsLateCallbacks() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = new TestWebSocket();
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);

        connector.awaitConnect();
        connector.succeed(socket);
        socket.awaitTextFrames(2);

        BlockingCharSequence blockedText = new BlockingCharSequence("Path:turn.end");
        Thread callback = daemonThread(() -> connector.listener().onText(socket, blockedText, true));
        callback.start();
        blockedText.awaitEntered();

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = daemonThread(() -> {
            try {
                client.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        closer.start();
        socket.awaitAborted();

        assertTrue(closer.isAlive(), "停止必须等待已进入的 listener 回调退出");
        blockedText.release();
        callback.join(2_000);
        closer.join(2_000);
        invocation.join();

        assertFalse(closer.isAlive());
        assertFalse(callback.isAlive());
        assertEquals(null, closeFailure.get());
        assertTrue(invocation.failure.get() instanceof EdgeTtsException);

        int requestsAtTerminal = socket.requestCount.get();
        connector.listener().onText(socket, "Path:turn.end", true);
        connector.listener().onBinary(socket, ByteBuffer.wrap(audioFrame(new byte[]{9})), true);
        connector.listener().onClose(socket, WebSocket.NORMAL_CLOSURE, "late");
        connector.listener().onError(socket, new IllegalStateException("late"));
        assertEquals(requestsAtTerminal, socket.requestCount.get());
    }

    @Test
    @DisplayName("停止会取消阻塞建连并立即排空，迟到成功仍被中止且不发布 socket")
    void closeCancelsBlockedConnectAndAbortsLateSocket() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = new TestWebSocket();
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);
        connector.awaitConnect();

        AtomicBoolean closeThreadInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = daemonThread(() -> {
            try {
                Thread.currentThread().interrupt();
                client.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            } finally {
                closeThreadInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        closer.start();
        closer.join(2_000);
        invocation.join();

        assertFalse(closer.isAlive());
        assertEquals(null, closeFailure.get());
        assertTrue(closeThreadInterrupted.get(), "排空结束后应恢复中断标记");
        assertTrue(connector.connection.isCancelled(), "停止必须释放永不完成的 connector future");

        connector.succeed(socket);
        socket.awaitAborted();
        assertTrue(socket.abortCount.get() >= 1);
        assertEquals(0, socket.textCount.get(), "迟到 socket 不得发布给合成发送路径");
        assertTrue(invocation.failure.get() instanceof EdgeTtsException);
    }

    @Test
    @DisplayName("合成线程被中断后会取消未完成建连并排空，迟到 socket 仍被中止")
    void interruptedSynthesisCancelsPendingConnectAndRejectsLateSocket() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = new TestWebSocket();
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);
        connector.awaitConnect();

        invocation.thread().interrupt();
        invocation.join();

        assertTrue(invocation.failure.get() instanceof EdgeTtsException);
        assertEquals("Edge TTS 合成已中断", invocation.failure.get().getMessage());
        assertTrue(invocation.interrupted.get());
        assertTrue(connector.connection.isCancelled(), "中断必须释放永不完成的 connector future");

        connector.succeed(socket);
        socket.awaitAborted();
        assertTrue(socket.abortCount.get() >= 1);
        assertEquals(0, socket.textCount.get());
        client.close();
    }

    @Test
    @DisplayName("普通建连异常对调用方使用净化后的稳定消息")
    void sanitizesOrdinaryConnectFailure() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        connector.connection.completeExceptionally(
                new IllegalStateException("credential=do-not-expose"));
        EdgeTtsClient client = client(connector);

        EdgeTtsException failure = assertThrows(EdgeTtsException.class,
                () -> client.synthesize("hello", "voice", "+0%", "+0Hz", "+0%"));

        assertEquals("无法连接 Edge TTS 服务", failure.getMessage());
        assertFalse(failure.getMessage().contains("do-not-expose"));
        client.close();
    }

    @Test
    @DisplayName("停止后拒绝新会话且不再调用 connector")
    void rejectsNewSessionsAfterClose() {
        ControlledConnector connector = new ControlledConnector();
        EdgeTtsClient client = client(connector);
        client.close();

        EdgeTtsException failure = assertThrows(EdgeTtsException.class,
                () -> client.synthesize("hello", "voice", "+0%", "+0Hz", "+0%"));

        assertEquals("Edge TTS 服务正在停止", failure.getMessage());
        assertEquals(0, connector.connectCount.get());
    }

    @Test
    @DisplayName("普通监听器异常对调用方使用净化后的稳定消息")
    void sanitizesOrdinaryListenerFailure() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = new TestWebSocket();
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);

        connector.awaitConnect();
        connector.succeed(socket);
        socket.awaitTextFrames(2);
        connector.listener().onError(socket, new IOException("token=do-not-expose"));
        invocation.join();

        assertTrue(invocation.failure.get() instanceof EdgeTtsException);
        assertEquals("Edge TTS 合成失败", invocation.failure.get().getMessage());
        assertFalse(invocation.failure.get().getMessage().contains("do-not-expose"));
        client.close();
    }

    @Test
    @DisplayName("建连 future 中的虚拟机致命错误保持原实例抛出")
    void preservesVirtualMachineErrorIdentity() {
        ControlledConnector connector = new ControlledConnector();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal-marker");
        connector.connection.completeExceptionally(fatal);
        EdgeTtsClient client = client(connector);

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> client.synthesize("hello", "voice", "+0%", "+0Hz", "+0%"));

        assertSame(fatal, thrown);
        client.close();
    }

    @Test
    @DisplayName("致命建连等待失败后会中止迟到成功的 WebSocket")
    void abortsSocketArrivingAfterFatalConnectResolution() throws Exception {
        ThreadDeath fatal = new ThreadDeath();
        FatalAwaitConnector connector = new FatalAwaitConnector(fatal);
        EdgeTtsClient client = client(connector);
        Invocation invocation = startSynthesis(client);
        connector.awaitConnect();
        invocation.join();

        assertSame(fatal, invocation.failure.get());
        TestWebSocket lateSocket = new TestWebSocket();
        connector.completeWithoutOpen(lateSocket);
        lateSocket.awaitAborted();

        assertEquals(1, lateSocket.abortCount.get());
        assertEquals(0, lateSocket.textCount.get());
        client.close();
    }

    @Test
    @DisplayName("监听器 future 中的 ThreadDeath 保持原实例抛出")
    void preservesThreadDeathIdentity() throws Exception {
        ControlledConnector connector = new ControlledConnector();
        TestWebSocket socket = new TestWebSocket();
        EdgeTtsClient client = client(connector);
        ThreadDeath fatal = new ThreadDeath();
        Invocation invocation = startSynthesis(client);

        connector.awaitConnect();
        connector.succeed(socket);
        socket.awaitTextFrames(2);
        connector.listener().onError(socket, fatal);
        invocation.join();

        assertSame(fatal, invocation.failure.get());
        client.close();
    }

    private static EdgeTtsClient client(EdgeTtsWebSocketConnector connector) {
        EdgeTtsVersionService versionService = mock(EdgeTtsVersionService.class);
        when(versionService.secMsGecVersion()).thenReturn("1-0.0.0.0");
        when(versionService.userAgent()).thenReturn("test-agent");
        return new EdgeTtsClient(NO_PROXY, versionService, connector, MESSAGES);
    }

    private static Invocation startSynthesis(EdgeTtsClient client) {
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = daemonThread(() -> {
            try {
                result.set(client.synthesize("hello", "voice", "+0%", "+0Hz", "+0%"));
            } catch (Throwable caught) {
                failure.set(caught);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        return new Invocation(thread, result, failure, interrupted);
    }

    private static Thread daemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "edge-tts-client-test");
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(thread.isAlive());
        assertTrue(thread.getState() == Thread.State.WAITING
                || thread.getState() == Thread.State.TIMED_WAITING,
                () -> "线程未进入等待态: " + thread.getState());
    }

    private static byte[] audioFrame(byte[] audio) {
        byte[] header = "Path:audio\r\n".getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(2 + header.length + audio.length);
        frame.putShort((short) header.length);
        frame.put(header);
        frame.put(audio);
        return frame.array();
    }

    private record Invocation(Thread thread,
                              AtomicReference<byte[]> result,
                              AtomicReference<Throwable> failure,
                              AtomicBoolean interrupted) {
        private void join() throws InterruptedException {
            thread.join(2_000);
            assertFalse(thread.isAlive(), "合成调用未在预期时间内终止");
            assertNotNull(result.get() != null ? result.get() : failure.get());
        }
    }

    private static final class ControlledConnector implements EdgeTtsWebSocketConnector {
        private final CompletableFuture<WebSocket> connection = new CompletableFuture<>();
        private final CountDownLatch connectCalled = new CountDownLatch(1);
        private final AtomicInteger connectCount = new AtomicInteger();
        private volatile WebSocket.Listener listener;

        @Override
        public CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
            connectCount.incrementAndGet();
            this.listener = listener;
            connectCalled.countDown();
            return connection;
        }

        private void awaitConnect() throws InterruptedException {
            assertTrue(connectCalled.await(2, TimeUnit.SECONDS));
        }

        private WebSocket.Listener listener() {
            assertNotNull(listener);
            return listener;
        }

        private void succeed(TestWebSocket socket) {
            listener().onOpen(socket);
            connection.complete(socket);
        }
    }

    private static final class FatalAwaitConnector implements EdgeTtsWebSocketConnector {
        private final FatalAttachFuture connection;
        private final CountDownLatch connectCalled = new CountDownLatch(1);

        private FatalAwaitConnector(ThreadDeath fatal) {
            this.connection = new FatalAttachFuture(fatal);
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
            connectCalled.countDown();
            return connection;
        }

        private void awaitConnect() throws InterruptedException {
            assertTrue(connectCalled.await(2, TimeUnit.SECONDS));
        }

        private void completeWithoutOpen(WebSocket socket) {
            connection.complete(socket);
        }
    }

    private static final class FatalAttachFuture extends CompletableFuture<WebSocket> {
        private final ThreadDeath fatal;
        private final AtomicBoolean failAttach = new AtomicBoolean(true);

        private FatalAttachFuture(ThreadDeath fatal) {
            this.fatal = fatal;
        }

        @Override
        public CompletableFuture<WebSocket> whenComplete(
                BiConsumer<? super WebSocket, ? super Throwable> action) {
            CompletableFuture<WebSocket> dependent = super.whenComplete(action);
            if (failAttach.compareAndSet(true, false)) {
                throw fatal;
            }
            return dependent;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class TestWebSocket implements WebSocket {
        private final AtomicInteger textCount = new AtomicInteger();
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger abortCount = new AtomicInteger();
        private final CountDownLatch textFrames = new CountDownLatch(2);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch aborted = new CountDownLatch(1);
        private final CompletableFuture<WebSocket> closeFuture = new CompletableFuture<>();

        private TestWebSocket() {
            closeFuture.complete(this);
        }

        private TestWebSocket(boolean pendingClose) {
            if (!pendingClose) {
                closeFuture.complete(this);
            }
        }

        private static TestWebSocket withPendingClose() {
            return new TestWebSocket(true);
        }

        private void awaitTextFrames(int expected) throws InterruptedException {
            assertEquals(2, expected);
            assertTrue(textFrames.await(2, TimeUnit.SECONDS));
        }

        private void awaitCloseStarted() throws InterruptedException {
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
        }

        private void awaitAborted() throws InterruptedException {
            assertTrue(aborted.await(2, TimeUnit.SECONDS));
        }

        private void completeClose() {
            closeFuture.complete(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            textCount.incrementAndGet();
            textFrames.countDown();
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCount.incrementAndGet();
            closeStarted.countDown();
            return closeFuture;
        }

        @Override
        public void request(long n) {
            requestCount.incrementAndGet();
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return abortCount.get() > 0;
        }

        @Override
        public boolean isInputClosed() {
            return abortCount.get() > 0;
        }

        @Override
        public void abort() {
            abortCount.incrementAndGet();
            aborted.countDown();
            closeFuture.completeExceptionally(new IOException("aborted"));
        }
    }

    private static final class BlockingCharSequence implements CharSequence {
        private final String value;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingCharSequence(String value) {
            this.value = value;
        }

        private void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        }

        private void release() {
            release.countDown();
        }

        @Override
        public int length() {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return value.length();
        }

        @Override
        public char charAt(int index) {
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }
    }

    private static final class MapMessageResolver implements MessageResolver {
        private final Map<String, String> messages;

        private MapMessageResolver(Map<String, String> messages) {
            this.messages = messages;
        }

        @Override
        public String get(String code, Object... args) {
            return get(Locale.SIMPLIFIED_CHINESE, code, args);
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return format(messages.getOrDefault(code, code), locale, args);
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return getOrDefault(Locale.SIMPLIFIED_CHINESE, code, defaultMessage, args);
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return format(messages.getOrDefault(code, defaultMessage), locale, args);
        }

        @Override
        public String getForLog(String code, Object... args) {
            return format(code, Locale.ROOT, args);
        }

        private static String format(String pattern, Locale locale, Object... args) {
            return args == null || args.length == 0
                    ? pattern
                    : new MessageFormat(pattern, locale).format(args);
        }
    }
}
