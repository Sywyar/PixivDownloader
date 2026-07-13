package top.sywyar.pixivdownload.tts;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 微软 Edge「大声朗读」（Read Aloud）在线 TTS 客户端。
 *
 * <p>该服务没有公开的 REST 接口，只能通过 WebSocket（{@code wss://speech.platform.bing.com/...}）合成，
 * 因此用 JDK 自带的 {@link java.net.http.WebSocket} 直接对接，不引入额外依赖。协议要点：
 * <ol>
 *   <li>连接 URL 需带固定的 {@code TrustedClientToken} 以及随时间变化的 {@code Sec-MS-GEC} 安全令牌；</li>
 *   <li>连接后先发 {@code speech.config} 文本帧（音频输出格式），再发 {@code ssml} 文本帧（要朗读的内容）；</li>
 *   <li>服务端以二进制帧回传 MP3 音频块（每帧前 2 字节大端表示头部长度，头部含 {@code Path:audio}），
 *       并以 {@code Path:turn.end} 文本帧表示合成结束。</li>
 * </ol>
 *
 * <p>所有外发连接复用全局代理设置（与下载/Pixiv 代理一致），便于在受限网络环境下访问。
 * 每次合成对应一个受管异步会话；Bean 停止时先拒绝新会话，再中止连接并排空所有已经进入的监听器回调。
 */
@Component
@Slf4j
public class EdgeTtsClient {

    /** edge-tts 公开使用的固定信任令牌（微软 Read Aloud 客户端内置常量）。 */
    static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    /** Windows FILETIME 纪元(1601-01-01)相对 Unix 纪元(1970-01-01)的秒数差。 */
    private static final long WIN_EPOCH_OFFSET_SECONDS = 11_644_473_600L;
    /** Sec-MS-GEC 令牌按 5 分钟取整：5min * 60 * 10^7（100ns 为单位）。 */
    private static final long FIVE_MINUTES_IN_TICKS = 3_000_000_000L;

    /**
     * 本机与 Edge 服务端的时钟偏差（秒）。握手 403 时按服务端 {@code Date} 头校正，
     * 令 {@code Sec-MS-GEC} 落在服务端认可的 5 分钟令牌窗口内；一旦校正后续请求复用。
     */
    private static volatile long clockSkewSeconds = 0L;

    private final OutboundProxySettings proxyConfig;
    private final EdgeTtsVersionService versionService;
    private final EdgeTtsWebSocketConnector connector;
    private final MessageResolver messages;
    private final Object sessionMonitor = new Object();
    private final Set<AsyncSession> sessions = new HashSet<>();
    private boolean acceptingSessions = true;

    public EdgeTtsClient(OutboundProxySettings proxyConfig,
                         EdgeTtsVersionService versionService,
                         EdgeTtsWebSocketConnector connector,
                         MessageResolver messages) {
        this.proxyConfig = proxyConfig;
        this.versionService = versionService;
        this.connector = connector;
        this.messages = messages;
    }

    /**
     * 合成一段文本为 MP3 字节。
     *
     * @param text   要朗读的纯文本（调用方负责裁剪长度）
     * @param voice  语音 ShortName，如 {@code zh-CN-XiaoxiaoNeural}
     * @param rate   语速，取值如 {@code +0%} / {@code -20%} / {@code +50%}
     * @param pitch  音调，取值如 {@code +0Hz} / {@code -10Hz}
     * @param volume 音量，取值如 {@code +0%}
     * @return MP3 音频字节
     */
    public byte[] synthesize(String text, String voice, String rate, String pitch, String volume) {
        try {
            return attempt(text, voice, rate, pitch, volume);
        } catch (Handshake403 e) {
            // 403 两种常见诱因：① Sec-MS-GEC-Version 过期；② 本机时钟与服务端 5 分钟令牌窗口不一致。
            // 重试前同时：强制刷新版本号 + 用响应 Date 头校正时钟偏差。
            versionService.forceRefresh();
            if (e.serverEpochSec > 0) {
                long oldSkew = clockSkewSeconds;
                clockSkewSeconds = e.serverEpochSec - System.currentTimeMillis() / 1000L;
                log.info(logMessage("tts.log.clock-skew-corrected", clockSkewSeconds, oldSkew));
            }
            try {
                return attempt(text, voice, rate, pitch, volume);
            } catch (Handshake403 e2) {
                throw new EdgeTtsException(message("tts.edge.error.handshake-rejected"), e2);
            }
        }
    }

    private byte[] attempt(String text, String voice, String rate, String pitch, String volume) {
        AsyncSession session = admitSession();
        try {
            WebSocket webSocket = connect(session);
            try {
                await(webSocket.sendText(buildConfigMessage(), true), 10, TimeUnit.SECONDS);
                await(webSocket.sendText(buildSsmlMessage(text, voice, rate, pitch, volume), true),
                        10, TimeUnit.SECONDS);
                byte[] bytes = await(session.result(), 60, TimeUnit.SECONDS);
                if (bytes.length == 0) {
                    throw new EdgeTtsException(message("tts.edge.error.empty-audio"), null);
                }
                return bytes;
            } catch (TimeoutException e) {
                throw new EdgeTtsException(message("tts.edge.error.timeout"), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EdgeTtsException(message("tts.edge.error.interrupted"), e);
            } catch (ExecutionException | CompletionException e) {
                throw synthesisFailure(unwrap(e));
            } catch (EdgeTtsException e) {
                throw e;
            } catch (RuntimeException e) {
                throw synthesisFailure(unwrap(e));
            }
        } finally {
            finishAndDrain(session);
        }
    }

    private WebSocket connect(AsyncSession session) {
        String connectUrl = WSS_URL
                + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + versionService.secMsGecVersion()
                + "&ConnectionId=" + newId();
        CompletableFuture<WebSocket> connecting = null;
        try {
            connecting = connector.connect(
                    URI.create(connectUrl), session.listener());
            if (connecting == null) {
                throw new IllegalStateException("Edge TTS connector returned null");
            }
            session.attach(connecting);
            return await(session.connected(), 20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            abandonConnect(session, connecting, e);
            throw connectFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandonConnect(session, connecting, e);
            throw new EdgeTtsException(message("tts.edge.error.interrupted"), e);
        } catch (ExecutionException | CompletionException e) {
            abandonConnect(session, connecting, e);
            throw connectFailure(unwrap(e));
        } catch (RuntimeException e) {
            abandonConnect(session, connecting, e);
            throw connectFailure(unwrap(e));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            abandonConnect(session, connecting, fatal);
            throw fatal;
        }
    }

    private void abandonConnect(AsyncSession session,
                                CompletableFuture<WebSocket> connecting,
                                Throwable failure) {
        Throwable fatalFailure = fatalOnly(failure);
        if (connecting != null) {
            try {
                connecting.cancel(true);
            } catch (Throwable cancelFailure) {
                fatalFailure = firstFailure(fatalFailure, fatalOnly(cancelFailure));
            }
        }
        try {
            session.connectFailed(failure);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            fatalFailure = firstFailure(fatalFailure, fatal);
        }
        rethrowFatal(fatalFailure);
    }

    private RuntimeException connectFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        rethrowFatal(cause);
        Long serverEpoch = handshake403Epoch(cause);
        if (serverEpoch != null) {
            return new Handshake403(serverEpoch);
        }
        if (!(cause instanceof ClientStopping)) {
            log.warn("{}: {}", logMessage("tts.log.websocket-connect-failed",
                    proxyConfig.getHost(), proxyConfig.getPort(), proxyConfig.isEnabled()),
                    cause.getClass().getSimpleName());
        }
        return new EdgeTtsException(message("tts.edge.error.connect-failed"), cause);
    }

    private EdgeTtsException synthesisFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        rethrowFatal(cause);
        return new EdgeTtsException(message("tts.edge.error.synthesis-failed"), cause);
    }

    private AsyncSession admitSession() {
        synchronized (sessionMonitor) {
            if (!acceptingSessions) {
                throw new EdgeTtsException(message("tts.edge.error.stopping"), null);
            }
            AsyncSession session = new AsyncSession();
            sessions.add(session);
            session.terminal().whenComplete((ignored, failure) -> releaseSession(session));
            return session;
        }
    }

    private void releaseSession(AsyncSession session) {
        synchronized (sessionMonitor) {
            sessions.remove(session);
        }
    }

    /**
     * 停止 Bean：拒绝新会话、立即中止已发布或迟到的 WebSocket，再不可中断地等待监听器终止。
     */
    @PreDestroy
    public void close() {
        List<AsyncSession> draining;
        synchronized (sessionMonitor) {
            acceptingSessions = false;
            draining = List.copyOf(sessions);
        }

        Throwable fatalFailure = null;
        for (AsyncSession session : draining) {
            try {
                session.stopNow();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                fatalFailure = firstFailure(fatalFailure, fatal);
            }
        }
        for (AsyncSession session : draining) {
            try {
                session.awaitTerminalUninterruptibly();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                fatalFailure = firstFailure(fatalFailure, fatal);
            }
        }
        rethrowFatal(fatalFailure);
    }

    private void finishAndDrain(AsyncSession session) {
        Throwable fatalFailure = null;
        try {
            session.finishGracefully();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            fatalFailure = fatal;
        }
        try {
            session.awaitTerminalUninterruptibly();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            fatalFailure = firstFailure(fatalFailure, fatal);
        }
        rethrowFatal(fatalFailure);
    }

    private void stopSocket(StopAction action) {
        Throwable fatalFailure = null;
        try {
            if (action.graceful()) {
                CompletableFuture<WebSocket> closing = action.webSocket()
                        .sendClose(WebSocket.NORMAL_CLOSURE, "done");
                if (closing != null) {
                    awaitUninterruptibly(closing);
                }
            }
        } catch (Throwable failure) {
            fatalFailure = fatalOnly(failure);
            log.debug("Edge TTS WebSocket close failed: {}", failure.getClass().getSimpleName());
        } finally {
            try {
                action.webSocket().abort();
            } catch (Throwable failure) {
                fatalFailure = firstFailure(fatalFailure, fatalOnly(failure));
                log.debug("Edge TTS WebSocket abort failed: {}", failure.getClass().getSimpleName());
            } finally {
                action.session().socketStopped(fatalFailure);
            }
        }
        rethrowFatal(fatalFailure);
    }

    private static void abortUntracked(WebSocket webSocket) {
        try {
            webSocket.abort();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // 会话已经终止，迟到 socket 只需尽力中止，不再把它发布给调用线程。
        }
    }

    private static <T> T await(CompletableFuture<T> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (future == null) {
            throw new CompletionException(new IllegalStateException("Async operation returned null"));
        }
        return future.get(timeout, unit);
    }

    private static void awaitUninterruptibly(CompletableFuture<?> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    future.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = unwrap(e);
                    rethrowFatal(cause);
                    throw new CompletionException(cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static Throwable fatalOnly(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof VirtualMachineError || cause instanceof ThreadDeath ? cause : null;
    }

    private static Throwable firstFailure(Throwable current, Throwable candidate) {
        return current != null ? current : candidate;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    /**
     * 若异常是 WebSocket 握手 403，返回服务端 {@code Date} 头解析出的 epoch 秒（无 Date 头时返回 0）；
     * 非 403 握手异常返回 {@code null}（交由上层按"连接失败"处理）。
     */
    private static Long handshake403Epoch(Throwable cause) {
        if (!(cause instanceof WebSocketHandshakeException)) {
            return null;
        }
        HttpResponse<?> response = ((WebSocketHandshakeException) cause).getResponse();
        if (response == null || response.statusCode() != 403) {
            return null;
        }
        return response.headers().firstValue("Date").map(EdgeTtsClient::parseHttpDateEpoch).orElse(0L);
    }

    private static long parseHttpDateEpoch(String date) {
        try {
            return ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        } catch (Exception e) {
            log.debug("Failed to parse HTTP Date header: {}", date, e);
            return 0L;
        }
    }

    /** WebSocket 握手返回 403 的内部信号，携带服务端 Date（epoch 秒，0 表示缺失）用于校正时钟偏差。 */
    private static final class Handshake403 extends RuntimeException {
        private final long serverEpochSec;

        private Handshake403(long serverEpochSec) {
            super("WebSocket handshake returned 403");
            this.serverEpochSec = serverEpochSec;
        }
    }

    /**
     * 生成 Sec-MS-GEC 安全令牌：对「FILETIME 取 5 分钟整 + 信任令牌」做 SHA-256，结果取大写 16 进制。
     */
    static String generateSecMsGec() {
        long ticks = (System.currentTimeMillis() / 1000L + clockSkewSeconds + WIN_EPOCH_OFFSET_SECONDS)
                * 10_000_000L;
        ticks -= ticks % FIVE_MINUTES_IN_TICKS;
        String toHash = ticks + TRUSTED_TOKEN;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(toHash.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02X", value));
            }
            return result.toString();
        } catch (Exception e) {
            throw new EdgeTtsException("无法生成 Sec-MS-GEC 令牌", e);
        }
    }

    private static String buildConfigMessage() {
        String body = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + body;
    }

    private static String buildSsmlMessage(String text, String voice, String rate, String pitch, String volume) {
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
                + "xml:lang='en-US'><voice name='" + voice + "'>"
                + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='" + volume + "'>"
                + escapeXml(text)
                + "</prosody></voice></speak>";
        return "X-RequestId:" + newId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    private static String timestamp() {
        return DateTimeFormatter
                .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                .format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String message(String code, Object... args) {
        return messages.get(code, args);
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    /** 一个合成调用拥有的 WebSocket、监听器回调门禁与终止信号。 */
    private final class AsyncSession {

        private final Object monitor = new Object();
        private final CompletableFuture<WebSocket> connected = new CompletableFuture<>();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();
        private final AudioListener listener = new AudioListener(this);
        private WebSocket webSocket;
        private boolean connectResolved;
        private boolean stopRequested;
        private boolean listenerSealed;
        private boolean gracefulStopStarted;
        private boolean abortStopStarted;
        private boolean socketStopped = true;
        private int activeStopActions;
        private int activeCallbacks;
        private Throwable terminalFatal;

        private AudioListener listener() {
            return listener;
        }

        private CompletableFuture<WebSocket> connected() {
            return connected;
        }

        private CompletableFuture<byte[]> result() {
            return result;
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }

        private void attach(CompletableFuture<WebSocket> connecting) {
            connecting.whenComplete((socket, failure) -> connectionCompleted(socket, failure));
        }

        private void connectFailed(Throwable failure) {
            connectionCompleted(null, failure);
        }

        private void connectionCompleted(WebSocket socket, Throwable failure) {
            StopAction action = null;
            boolean rejectLateSocket = false;
            synchronized (monitor) {
                if (connectResolved) {
                    rejectLateSocket = socket != null;
                } else {
                    connectResolved = true;
                    Throwable cause = failure == null ? null : unwrap(failure);
                    if (cause != null || socket == null) {
                        Throwable actual = cause != null
                                ? cause
                                : new IllegalStateException("Edge TTS connector completed without a socket");
                        connected.completeExceptionally(actual);
                    } else {
                        observeSocketLocked(socket);
                        if (stopRequested) {
                            connected.completeExceptionally(ClientStopping.INSTANCE);
                            action = claimSocketStopLocked(false);
                        } else {
                            connected.complete(socket);
                        }
                    }
                    completeTerminalIfReadyLocked();
                }
            }
            if (rejectLateSocket) {
                abortUntracked(socket);
            } else if (action != null) {
                stopSocket(action);
            }
        }

        private boolean enterCallback() {
            synchronized (monitor) {
                if (listenerSealed) {
                    return false;
                }
                activeCallbacks++;
                return true;
            }
        }

        private void leaveCallback() {
            synchronized (monitor) {
                activeCallbacks--;
                completeTerminalIfReadyLocked();
            }
        }

        private boolean socketOpened(WebSocket socket) {
            StopAction action = null;
            boolean requestAllowed;
            synchronized (monitor) {
                if (terminal.isDone()) {
                    requestAllowed = false;
                } else {
                    observeSocketLocked(socket);
                    requestAllowed = !stopRequested && !listenerSealed;
                    if (!requestAllowed) {
                        action = claimSocketStopLocked(false);
                    }
                }
            }
            if (terminal.isDone()) {
                abortUntracked(socket);
            } else if (action != null) {
                stopSocket(action);
            }
            return requestAllowed;
        }

        private void rejectLateSocket(WebSocket socket) {
            StopAction action = null;
            boolean untracked;
            synchronized (monitor) {
                untracked = terminal.isDone();
                if (!untracked) {
                    observeSocketLocked(socket);
                    action = claimSocketStopLocked(false);
                }
            }
            if (untracked) {
                abortUntracked(socket);
            } else if (action != null) {
                stopSocket(action);
            }
        }

        private boolean shouldRequestNext() {
            synchronized (monitor) {
                return !listenerSealed && !stopRequested;
            }
        }

        private void callbackFailed(Throwable failure) {
            Throwable actual = failure == null
                    ? new IllegalStateException("Edge TTS listener failed without a cause")
                    : failure;
            result.completeExceptionally(actual);
        }

        private void finishGracefully() {
            StopAction action;
            synchronized (monitor) {
                requestStopLocked();
                action = claimSocketStopLocked(true);
                completeTerminalIfReadyLocked();
            }
            if (action != null) {
                stopSocket(action);
            }
        }

        private void stopNow() {
            StopAction action;
            synchronized (monitor) {
                requestStopLocked();
                action = claimSocketStopLocked(false);
                completeTerminalIfReadyLocked();
            }
            if (action != null) {
                stopSocket(action);
            }
        }

        private void requestStopLocked() {
            stopRequested = true;
            listenerSealed = true;
            connected.completeExceptionally(ClientStopping.INSTANCE);
            result.completeExceptionally(ClientStopping.INSTANCE);
        }

        private void observeSocketLocked(WebSocket socket) {
            if (webSocket == null) {
                webSocket = socket;
                socketStopped = false;
            }
        }

        private StopAction claimSocketStopLocked(boolean graceful) {
            if (webSocket == null) {
                return null;
            }
            if (graceful) {
                if (gracefulStopStarted || abortStopStarted) {
                    return null;
                }
                gracefulStopStarted = true;
            } else {
                if (abortStopStarted) {
                    return null;
                }
                abortStopStarted = true;
            }
            activeStopActions++;
            return new StopAction(this, webSocket, graceful);
        }

        private void socketStopped(Throwable fatalFailure) {
            synchronized (monitor) {
                activeStopActions--;
                socketStopped = true;
                terminalFatal = firstFailure(terminalFatal, fatalFailure);
                completeTerminalIfReadyLocked();
            }
        }

        private void completeTerminalIfReadyLocked() {
            if (!stopRequested || !connectResolved || activeCallbacks != 0
                    || activeStopActions != 0 || !socketStopped) {
                return;
            }
            if (terminalFatal == null) {
                terminal.complete(null);
            } else {
                terminal.completeExceptionally(terminalFatal);
            }
        }

        private void awaitTerminalUninterruptibly() {
            awaitUninterruptibly(terminal);
        }
    }

    private record StopAction(AsyncSession session, WebSocket webSocket, boolean graceful) {
    }

    private static final class ClientStopping extends RuntimeException {
        private static final ClientStopping INSTANCE = new ClientStopping();

        private ClientStopping() {
            super("Edge TTS client is stopping", null, false, false);
        }
    }

    /**
     * WebSocket 监听器：累积二进制音频帧，遇到 {@code Path:turn.end} 文本帧时完成。
     * onText / onBinary 可能分多片回调，按 {@code last} 标志拼接成完整消息后再解析。
     */
    private static final class AudioListener implements WebSocket.Listener {

        private final AsyncSession session;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private AudioListener(AsyncSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!session.enterCallback()) {
                session.rejectLateSocket(webSocket);
                return;
            }
            try {
                if (session.socketOpened(webSocket)) {
                    requestNext(webSocket);
                }
            } finally {
                session.leaveCallback();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!session.enterCallback()) {
                return null;
            }
            try {
                textBuffer.append(data);
                if (last) {
                    String message = textBuffer.toString();
                    textBuffer.setLength(0);
                    if (message.contains("Path:turn.end")) {
                        session.result().complete(audio.toByteArray());
                    }
                }
                requestNext(webSocket);
                return null;
            } finally {
                session.leaveCallback();
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (!session.enterCallback()) {
                return null;
            }
            try {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                binaryBuffer.writeBytes(chunk);
                if (last) {
                    byte[] full = binaryBuffer.toByteArray();
                    binaryBuffer.reset();
                    appendAudio(full);
                }
                requestNext(webSocket);
                return null;
            } finally {
                session.leaveCallback();
            }
        }

        private void appendAudio(byte[] full) {
            if (full.length < 2) {
                return;
            }
            int headerLength = ((full[0] & 0xFF) << 8) | (full[1] & 0xFF);
            int audioStart = 2 + headerLength;
            if (audioStart > full.length) {
                return;
            }
            String header = new String(full, 2, headerLength, StandardCharsets.UTF_8);
            if (header.contains("Path:audio")) {
                audio.write(full, audioStart, full.length - audioStart);
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!session.enterCallback()) {
                return;
            }
            try {
                session.callbackFailed(error);
            } finally {
                session.leaveCallback();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!session.enterCallback()) {
                return null;
            }
            try {
                // 正常情况下 turn.end 已经 complete；这里兜底，避免连接异常关闭时调用方一直等待。
                session.result().complete(audio.toByteArray());
                return null;
            } finally {
                session.leaveCallback();
            }
        }

        private void requestNext(WebSocket webSocket) {
            if (!session.shouldRequestNext()) {
                return;
            }
            try {
                webSocket.request(1);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (RuntimeException failure) {
                session.callbackFailed(failure);
            }
        }
    }
}
