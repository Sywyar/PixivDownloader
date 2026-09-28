package top.sywyar.pixivdownload.core.ffmpeg;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 同步、有界的宿主媒体进程执行；调用方拥有参数和产物，宿主拥有进程生命周期。 */
public interface FfmpegRunner {
    /** 由宿主解析实际路径的媒体工具。 */
    enum Tool {
        /** 转码工具。 */
        FFMPEG,
        /** 媒体探测工具。 */
        FFPROBE
    }
    /** 进程执行阶段。 */
    enum Phase {
        /** 等待共享进程额度。 */
        WAITING,
        /** 进程已启动。 */
        RUNNING
    }

    /**
     * 返回有界的 UTF-8 输出；取消保留取消语义。
     * @param tool 要执行的媒体工具
     * @param arguments 命令参数
     * @param workingDirectory 工作目录
     * @param output 待监控的输出文件
     * @param maximumOutputBytes 输出文件字节上限
     * @param timeout 单次执行时限
     * @param cancelled 取消信号
     * @return 有界的 UTF-8 诊断输出
     * @throws IOException 进程启动失败、非零退出、输出超限或执行超时
     */
    default String run(Tool tool, List<String> arguments, Path workingDirectory, Path output,
                       long maximumOutputBytes, Duration timeout, BooleanSupplier cancelled) throws IOException {
        return run(tool, arguments, workingDirectory, output, maximumOutputBytes, timeout, cancelled, null);
    }

    /**
     * 同步通知等待额度和进程已启动；通知不包含命令、路径或诊断输出，不创建额外后台任务。
     * @param tool 要执行的媒体工具
     * @param arguments 命令参数
     * @param workingDirectory 工作目录
     * @param output 待监控的输出文件
     * @param maximumOutputBytes 输出文件字节上限
     * @param timeout 单次执行时限
     * @param cancelled 取消信号
     * @param progress 执行阶段回调；不需要通知时为 {@code null}
     * @return 有界的 UTF-8 诊断输出
     * @throws IOException 进程启动失败、非零退出、输出超限或执行超时
     */
    String run(Tool tool, List<String> arguments, Path workingDirectory, Path output,
               long maximumOutputBytes, Duration timeout, BooleanSupplier cancelled,
               Consumer<Phase> progress) throws IOException;
}
