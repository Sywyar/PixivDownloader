package top.sywyar.pixivdownload.common;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 把 JVM 的标准输出/错误流统一替换为 UTF-8 编码的 {@link PrintStream}。
 *
 * <p>问题背景：{@code System.out} / {@code System.err} 默认使用平台相关编码
 * （Windows 中文环境通常为 GBK/cp936），而 logback 的控制台 appender 固定以 UTF-8
 * 写出字节。两者不一致会导致 CLI（如 {@code --setup}）打印的中文与日志中的中文在
 * 同一终端里必有一方乱码。本方法在任何打印或 logback 初始化之前，把标准流强制为
 * UTF-8，使全项目的控制台输出统一为 UTF-8（参见 {@code CLAUDE.md} 的「编码」一节）。
 *
 * <p><strong>调用时机</strong>：必须在每个 {@code main()} 的第一行、且早于第一次
 * {@code LoggerFactory.getLogger()} 调用时执行——logback 的 {@code ConsoleAppender}
 * 在初始化时会捕获当时的 {@code System.out} 引用，之后再替换便不再生效。
 */
public final class Utf8ConsoleStreams {

    private Utf8ConsoleStreams() {
    }

    /**
     * 把 {@code System.out} / {@code System.err} 替换为 UTF-8 编码、自动 flush 的
     * {@link PrintStream}。幂等：重复调用无副作用。无控制台（如 jpackage 窗口化 exe）
     * 时写入会被 {@link PrintStream} 静默吞掉，不影响启动。
     */
    public static void install() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }
}
