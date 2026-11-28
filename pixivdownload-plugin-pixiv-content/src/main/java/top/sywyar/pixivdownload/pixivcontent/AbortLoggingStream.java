package top.sywyar.pixivdownload.pixivcontent;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.core.web.ResponseStreamAbortedException;

/**
 * 包装已开始回传的响应正文：读取上游或生成内容失败时，在默认级别记录一次原因后中止响应。
 *
 * <p>抛出的 {@link ResponseStreamAbortedException} 让宿主只结束连接，不再按服务器读写错误重复记录。
 * 下游客户端断开发生在写出端，不经过本类，仍由宿主按断开处理。
 */
final class AbortLoggingStream extends FilterInputStream {
    private static final Logger log = LoggerFactory.getLogger(AbortLoggingStream.class);
    private final RequestDiagnostics request;
    private final Function<IOException, String> reason;
    private long sent;
    private boolean reported;

    /** {@code reason} 把读取失败归类为固定英文原因码，例如 {@code media_size_limit}。 */
    AbortLoggingStream(InputStream source, RequestDiagnostics request, Function<IOException, String> reason) {
        super(source);
        this.request = request;
        this.reason = reason;
    }

    @Override public int read() throws IOException {
        try {
            int value = super.read();
            if (value >= 0) sent++;
            return value;
        } catch (IOException failure) {
            throw abort(failure);
        }
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        try {
            int count = super.read(bytes, offset, length);
            if (count > 0) sent += count;
            return count;
        } catch (IOException failure) {
            throw abort(failure);
        }
    }

    private IOException abort(IOException failure) {
        if (failure instanceof ResponseStreamAbortedException already) return already;
        String cause = reason.apply(failure);
        if (!reported) {
            reported = true;
            log.warn("Pixiv content stream aborted: {} reason={} bytesSent={}", request.fields(), cause, sent);
        }
        return new ResponseStreamAbortedException("Pixiv content stream aborted: " + cause, failure);
    }
}
