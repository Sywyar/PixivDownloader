package top.sywyar.pixivdownload.core.web;

import java.io.IOException;

/**
 * 已开始发送的流式响应被内容 owner 主动中止。
 *
 * <p>抛出前，owner 必须已在默认日志级别记录中止原因与必要的业务上下文。宿主收到该异常时只结束响应，
 * 不再把它当作服务器读写错误记录完整堆栈，也不会补写错误正文。上游内容截断、超出字节上限或转换失败
 * 适合使用本异常；下游客户端断开连接仍沿用宿主原有的断开处理。
 */
public final class ResponseStreamAbortedException extends IOException {

    /**
     * 创建中止异常。
     *
     * @param reason 固定英文的中止原因，不得包含凭据、地址或上游正文
     * @param cause 触发中止的原始读写失败，可为 {@code null}
     */
    public ResponseStreamAbortedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
