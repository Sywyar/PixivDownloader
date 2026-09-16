package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.IntFunction;

/**
 * 在实际临时文件与产物路径都可用时，才采用用户选择的命名方式。
 * @param baseNames 按页排列的文件名主干
 * @param maxLength 实际使用的主干长度上限
 * @param defaultName 是否采用默认文件名模板
 */
public record DownloadPathPlan(List<String> baseNames, int maxLength, boolean defaultName) {
    /**
     * 保存本次下载采用的名称。
     * @param baseNames 按页排列的文件名主干，将复制为不可变列表
     * @param maxLength 实际使用的主干长度上限
     * @param defaultName 是否采用默认文件名模板
     */
    public DownloadPathPlan {
        baseNames = List.copyOf(baseNames);
    }

    /**
     * 根据已知文件系统限制解析本次命名方案。
     * @param directory 作品目录
     * @param limits 已知文件系统长度限制
     * @param names 根据长度上限生成全部页文件名的函数
     * @param defaults 默认短文件名列表
     * @param suffixes 产物和临时文件需要使用的后缀
     * @param fixedPaths 不随文件名变化的目录内相对路径
     * @param action 用户已授权的处理方式
     * @return 所有路径均可用的命名方案
     * @throws NeedsAction 原始路径超限且没有可执行的改名授权
     * @throws CancellationException 用户授权取消超限作品
     */
    public static DownloadPathPlan resolve(Path directory, DownloadPathLimits limits,
                                           IntFunction<List<String>> names, List<String> defaults,
                                           List<String> suffixes, List<String> fixedPaths,
                                           DownloadPathAction action) {
        return resolve(directory, limits::accepts, names, defaults, suffixes, fixedPaths, action);
    }

    /**
     * 同时使用宿主查出的限制与目标卷的只读能力探测。
     * @param directory 作品目录
     * @param supported 判断候选路径是否满足目标卷限制的谓词
     * @param names 根据长度上限生成全部页文件名的函数
     * @param defaults 默认短文件名列表
     * @param suffixes 产物和临时文件需要使用的后缀
     * @param fixedPaths 不随文件名变化的目录内相对路径
     * @param action 用户已授权的处理方式
     * @return 所有路径均可用的命名方案
     * @throws NeedsAction 原始路径超限且没有可执行的改名授权
     * @throws CancellationException 用户授权取消超限作品
     */
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

    /**
     * 候选为空时该操作不可用，不授权程序自行改写下载根目录。
     * @param originalPath 原始路径预览
     * @param truncatedPath 截断后的路径预览；不可用时为空
     * @param defaultPath 默认短文件名的路径预览；不可用时为空
     */
    public record Problem(String originalPath, String truncatedPath, String defaultPath) {}

    /** 路径超限且仍需用户处理，由调用方投影为交互提示或计划任务待处理状态。 */
    public static final class NeedsAction extends RuntimeException {
        /** 原始路径与可供用户选择的命名候选。 */
        private final Problem problem;

        /**
         * 保存供用户选择的路径预览。
         * @param problem 原始路径及可用候选
         */
        public NeedsAction(Problem problem) {
            super("DOWNLOAD_PATH_ACTION_REQUIRED");
            this.problem = problem;
        }

        /**
         * 返回供用户选择的路径预览。
         * @return 原始路径及可用候选
         */
        public Problem problem() { return problem; }
    }
}
