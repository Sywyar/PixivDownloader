package top.sywyar.pixivdownload.core.ffmpeg;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 同步、有界的宿主媒体进程执行；调用方拥有参数和产物，宿主拥有进程生命周期。 */
public interface FfmpegRunner {
    enum Tool { FFMPEG, FFPROBE }

    /** 返回有界的 UTF-8 输出；非零退出、超限、超时抛出异常，取消保留取消语义。 */
    String run(Tool tool, List<String> arguments, Path workingDirectory, Path output,
               long maximumOutputBytes, Duration timeout, BooleanSupplier cancelled) throws IOException;
}
