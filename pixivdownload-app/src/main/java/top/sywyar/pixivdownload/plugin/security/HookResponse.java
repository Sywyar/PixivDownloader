package top.sywyar.pixivdownload.plugin.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;

/** 原响应即时写出，仅获批 JSON 结果字段保留有界副本，超限释放副本并继续原响应。 */
final class HookResponse extends HttpServletResponseWrapper {
    private ByteArrayOutputStream copy;
    private ServletOutputStream output;
    private PrintWriter writer;
    private StringBuilder text;
    HookResponse(HttpServletResponse response) { super(response); }
    void capture() { copy = new ByteArrayOutputStream(); }
    synchronized byte[] captured() {
        if (copy == null) return null;
        byte[] bytes = text == null ? copy.toByteArray() : text.toString().getBytes(java.nio.charset.Charset.forName(getCharacterEncoding()));
        return bytes.length > 65536 ? null : bytes;
    }
    private synchronized void record(String value, int offset, int length) {
        if (copy == null) return;
        if (text == null) text = new StringBuilder();
        if (length > 65536 - text.length()) { copy = null; text = null; return; }
        text.append(value, offset, offset + length);
    }
    private synchronized void record(byte[] bytes, int offset, int length) {
        if (copy == null) return;
        if (length > 65536 - copy.size()) { copy = null; return; }
        copy.write(bytes, offset, length);
    }
    private synchronized void record(int value) {
        if (copy == null) return;
        if (copy.size() >= 65536) copy = null; else copy.write(value);
    }
    private synchronized void record(char[] value, int offset, int length) {
        if (copy == null) return;
        if (length > 65536 - (text == null ? 0 : text.length())) { copy = null; text = null; return; }
        if (text == null) text = new StringBuilder();
        text.append(value, offset, length);
    }
    private ServletOutputStream output() throws IOException {
        if (output == null) {
            var delegate = super.getOutputStream();
            output = new ServletOutputStream() {
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                @Override public void write(int value) throws IOException { delegate.write(value); record(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { delegate.write(bytes, offset, length); record(bytes, offset, length); }
                @Override public void flush() throws IOException { delegate.flush(); }
                @Override public void close() throws IOException { delegate.close(); }
            };
        }
        return output;
    }
    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) throw new IllegalStateException("Writer already selected");
        return output();
    }
    @Override public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            if (output != null) throw new IllegalStateException("Output stream already selected");
            writer = new PrintWriter(super.getWriter()) {
                @Override public void write(String value, int offset, int length) { super.write(value, offset, length); record(value, offset, length); }
                @Override public void write(char[] value, int offset, int length) {
                    super.write(value, offset, length); record(value, offset, length);
                }
                @Override public void write(int value) { super.write(value); if (copy != null) record(String.valueOf((char) value), 0, 1); }
            };
        }
        return writer;
    }
    @Override public void setCharacterEncoding(String value) { if (writer == null) super.setCharacterEncoding(value); }
    @Override public void flushBuffer() throws IOException { if (writer != null) writer.flush(); super.flushBuffer(); }
    @Override public void resetBuffer() { super.resetBuffer(); synchronized (this) { if (copy != null) copy.reset(); text = null; } }
    @Override public void reset() { super.reset(); output = null; writer = null; synchronized (this) { if (copy != null) copy.reset(); text = null; } }
}
