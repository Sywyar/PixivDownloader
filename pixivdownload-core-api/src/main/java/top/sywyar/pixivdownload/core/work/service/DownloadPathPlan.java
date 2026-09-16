package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.IntFunction;

/** 在实际临时文件与产物路径都可用时，才采用用户选择的命名方式。 */
public record DownloadPathPlan(List<String> baseNames, int maxLength, boolean defaultName) {
    public DownloadPathPlan {
        baseNames = List.copyOf(baseNames);
    }

    public static DownloadPathPlan resolve(Path directory, DownloadPathLimits limits,
                                           IntFunction<List<String>> names, List<String> defaults,
                                           List<String> suffixes, List<String> fixedPaths,
                                           DownloadPathAction action) {
        return resolve(directory, limits::accepts, names, defaults, suffixes, fixedPaths, action);
    }

    /** 同时使用宿主查出的限制与目标卷的只读能力探测。 */
    public static DownloadPathPlan resolve(Path directory, java.util.function.Predicate<Path> supported,
                                           IntFunction<List<String>> names, List<String> defaults,
                                           List<String> suffixes, List<String> fixedPaths,
                                           DownloadPathAction action) {
        int maximum = PixivWorkFileNameFormatter.MAX_BASENAME_LENGTH;
        List<String> original = names.apply(maximum);
        boolean directoryFits = supported.test(directory)
                && fixedPaths.stream().allMatch(value -> supported.test(directory.resolve(value)));
        if (directoryFits && fits(directory, supported, original, suffixes)) {
            return new DownloadPathPlan(original, maximum, false);
        }
        if (action == DownloadPathAction.CANCEL) throw new CancellationException("DOWNLOAD_PATH_CANCELLED");
        DownloadPathPlan truncated = null;
        if (directoryFits) {
            for (int length = maximum - 1; length > 0; length--) {
                try {
                    List<String> candidate = names.apply(length);
                    if (fits(directory, supported, candidate, suffixes)) {
                        truncated = new DownloadPathPlan(candidate, length, false);
                        break;
                    }
                } catch (IllegalArgumentException cannotFit) {
                    // 过小的长度无法保留字符或页码，继续确认其它候选长度。
                }
            }
        }
        boolean defaultFits = directoryFits && fits(directory, supported, defaults, suffixes);
        if (action == DownloadPathAction.TRUNCATE && truncated != null) return truncated;
        if (action == DownloadPathAction.DEFAULT_NAME && defaultFits) {
            return new DownloadPathPlan(defaults, maximum, true);
        }
        throw new NeedsAction(new Problem(
                preview(directory, original, suffixes),
                truncated == null ? null : preview(directory, truncated.baseNames(), suffixes),
                defaultFits ? preview(directory, defaults, suffixes) : null));
    }

    private static boolean fits(Path directory, java.util.function.Predicate<Path> supported, List<String> names,
                                List<String> suffixes) {
        return names.stream().allMatch(name -> suffixes.stream()
                .allMatch(suffix -> supported.test(directory.resolve(name + suffix))));
    }

    private static String preview(Path directory, List<String> names, List<String> suffixes) {
        return directory.resolve(names.get(0) + suffixes.get(0)).toString();
    }

    /** 候选为空时该操作不可用，不授权程序自行改写下载根目录。 */
    public record Problem(String originalPath, String truncatedPath, String defaultPath) {}

    public static final class NeedsAction extends RuntimeException {
        private final Problem problem;

        public NeedsAction(Problem problem) {
            super("DOWNLOAD_PATH_ACTION_REQUIRED");
            this.problem = problem;
        }

        public Problem problem() { return problem; }
    }
}
