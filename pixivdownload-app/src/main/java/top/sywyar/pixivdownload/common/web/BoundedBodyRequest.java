package top.sywyar.pixivdownload.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.*;

/** 宿主在控制器之前检查正文预算时保留原字节，DTO 仍由 MVC 原解析器处理。 */
public final class BoundedBodyRequest extends HttpServletRequestWrapper {
    private byte[] body;
    private ServletInputStream replay;
    private boolean exceeded;
    private IOException readFailure;
    public BoundedBodyRequest(HttpServletRequest request) { super(request); }
    public byte[] readBody(int limit) throws IOException {
        if (readFailure != null) throw readFailure;
        if (body == null) {
            var original = super.getInputStream();
            try { body = original.readNBytes(limit + 1); }
            catch (IOException failure) { readFailure = failure; throw failure; }
            exceeded = body.length > limit;
            var input = new ByteArrayInputStream(body);
            replay = new ServletInputStream() {
                // 可选观察超限后仍须交还完整正文；只缓存前缀，其余继续读取原流。
                @Override public int read() throws IOException { return input.available() > 0 ? input.read() : original.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return input.available() > 0 ? input.read(bytes, offset, length) : original.read(bytes, offset, length);
                }
                @Override public boolean isFinished() { return input.available() == 0 && original.isFinished(); }
                @Override public boolean isReady() { return input.available() > 0 || original.isReady(); }
                @Override public void close() throws IOException { original.close(); }
                @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("HTTP_NONBLOCKING_INPUT_UNSUPPORTED"); }
            };
        }
        if (exceeded || body.length > limit) throw new IllegalArgumentException("HTTP_BODY_LIMIT");
        return body;
    }
    @Override public ServletInputStream getInputStream() throws IOException {
        if (readFailure != null) throw readFailure;
        return replay == null ? super.getInputStream() : replay;
    }
    @Override public BufferedReader getReader() throws IOException {
        if (readFailure != null) throw readFailure;
        return replay == null ? super.getReader() : new BufferedReader(new InputStreamReader(replay,
                getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding())));
    }
}
