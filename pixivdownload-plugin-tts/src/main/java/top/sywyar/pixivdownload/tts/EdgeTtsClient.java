package top.sywyar.pixivdownload.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final MessageResolver messages;

    public EdgeTtsClient(OutboundProxySettings proxyConfig, EdgeTtsVersionService versionService,
                         MessageResolver messages) {
        this.proxyConfig = proxyConfig;
        this.versionService = versionService;
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
                throw new EdgeTtsException(
                        "Edge TTS 握手被拒绝(403)：令牌校验仍失败（已尝试刷新版本号与校正时钟）", e2);
            }
        }
    }

    private byte[] attempt(String text, String voice, String rate, String pitch, String volume) {
        HttpClient client = buildClient();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();

        String connectUrl = WSS_URL
                + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + versionService.secMsGecVersion()
                + "&ConnectionId=" + newId();

        WebSocket ws;
        try {
            ws = client.newWebSocketBuilder()
                    .header("User-Agent", versionService.userAgent())
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(connectUrl), new AudioListener(audio, result))
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Long serverEpoch = handshake403Epoch(cause);
            if (serverEpoch != null) {
                throw new Handshake403(serverEpoch);
            }
            log.warn(logMessage("tts.log.websocket-connect-failed",
                    proxyConfig.getHost(), proxyConfig.getPort(), proxyConfig.isEnabled()), cause);
            throw new EdgeTtsException("无法连接 Edge TTS 服务："
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }

        try {
            ws.sendText(buildConfigMessage(), true).get(10, TimeUnit.SECONDS);
            ws.sendText(buildSsmlMessage(text, voice, rate, pitch, volume), true).get(10, TimeUnit.SECONDS);
            byte[] bytes = result.get(60, TimeUnit.SECONDS);
            if (bytes.length == 0) {
                throw new EdgeTtsException("Edge TTS 未返回音频", null);
            }
            return bytes;
        } catch (TimeoutException e) {
            throw new EdgeTtsException("Edge TTS 合成超时", e);
        } catch (EdgeTtsException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EdgeTtsException("Edge TTS 合成失败：" + cause.getMessage(), cause);
        } finally {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {
            }
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
        HttpResponse<?> resp = ((WebSocketHandshakeException) cause).getResponse();
        if (resp == null || resp.statusCode() != 403) {
            return null;
        }
        return resp.headers().firstValue("Date").map(EdgeTtsClient::parseHttpDateEpoch).orElse(0L);
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
        final long serverEpochSec;

        Handshake403(long serverEpochSec) {
            super("WebSocket handshake returned 403");
            this.serverEpochSec = serverEpochSec;
        }
    }

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        if (proxyConfig.isEnabled()) {
            String host = proxyConfig.getHost();
            int port = proxyConfig.getPort();
            if (host != null && !host.isBlank() && port > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            }
        }
        return builder.build();
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
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
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

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
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

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    /**
     * WebSocket 监听器：累积二进制音频帧，遇到 {@code Path:turn.end} 文本帧时完成。
     * onText / onBinary 可能分多片回调，按 {@code last} 标志拼接成完整消息后再解析。
     */
    private static final class AudioListener implements WebSocket.Listener {

        private final ByteArrayOutputStream audio;
        private final CompletableFuture<byte[]> result;
        private final StringBuilder textBuf = new StringBuilder();
        private final ByteArrayOutputStream binaryBuf = new ByteArrayOutputStream();

        AudioListener(ByteArrayOutputStream audio, CompletableFuture<byte[]> result) {
            this.audio = audio;
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String message = textBuf.toString();
                textBuf.setLength(0);
                if (message.contains("Path:turn.end")) {
                    result.complete(audio.toByteArray());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuf.writeBytes(chunk);
            if (last) {
                byte[] full = binaryBuf.toByteArray();
                binaryBuf.reset();
                appendAudio(full);
            }
            webSocket.request(1);
            return null;
        }

        private void appendAudio(byte[] full) {
            if (full.length < 2) return;
            int headerLen = ((full[0] & 0xFF) << 8) | (full[1] & 0xFF);
            int audioStart = 2 + headerLen;
            if (audioStart > full.length) return;
            String header = new String(full, 2, headerLen, StandardCharsets.UTF_8);
            if (header.contains("Path:audio")) {
                audio.write(full, audioStart, full.length - audioStart);
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 正常情况下 turn.end 已经 complete；这里兜底，避免连接异常关闭时调用方一直等待。
            if (!result.isDone()) {
                result.complete(audio.toByteArray());
            }
            return null;
        }
    }
}
