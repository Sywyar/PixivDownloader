package top.sywyar.pixivdownload.ffmpeg;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把宿主 FFmpeg 安装探测适配为稳定命令解析端口。
 */
@Component
public class FfmpegCommandResolverAdapter implements FfmpegCommandResolver {

    private final Supplier<Optional<FfmpegInstallation>> installationLocator;
    private final Supplier<String> fallbackCommand;

    public FfmpegCommandResolverAdapter() {
        this(FfmpegLocator::locate, FfmpegLocator::fallbackCommand);
    }

    FfmpegCommandResolverAdapter(
            Supplier<Optional<FfmpegInstallation>> installationLocator,
            Supplier<String> fallbackCommand
    ) {
        this.installationLocator = Objects.requireNonNull(installationLocator, "installationLocator");
        this.fallbackCommand = Objects.requireNonNull(fallbackCommand, "fallbackCommand");
    }

    @Override
    public ResolvedFfmpegCommand resolve() {
        return installationLocator.get()
                .map(FfmpegCommandResolverAdapter::resolved)
                .orElseGet(() -> new ResolvedFfmpegCommand(
                        fallbackCommand.get(),
                        ResolvedFfmpegCommand.Source.FALLBACK
                ));
    }

    private static ResolvedFfmpegCommand resolved(FfmpegInstallation installation) {
        ResolvedFfmpegCommand.Source source = switch (installation.source()) {
            case MANAGED -> ResolvedFfmpegCommand.Source.MANAGED;
            case BUNDLED -> ResolvedFfmpegCommand.Source.BUNDLED;
            case SYSTEM -> ResolvedFfmpegCommand.Source.SYSTEM;
        };
        return new ResolvedFfmpegCommand(installation.ffmpegPath().toString(), source);
    }
}
