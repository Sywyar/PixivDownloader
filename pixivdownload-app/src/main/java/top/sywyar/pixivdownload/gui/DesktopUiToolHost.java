package top.sywyar.pixivdownload.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 桌面维护、迁移、FFmpeg 与图片分类工具所需的宿主能力。
 */
interface DesktopUiToolHost {
    /**
     * 定位可用的 FFmpeg 安装。
     *
     * @return 找到的可用安装；没有时为空
     */
    Optional<FfmpegInstallation> locateFfmpeg();

    /**
     * 返回为宿主管理的 FFmpeg 安装预留的目录。
     *
     * @return 受管 FFmpeg 目录
     */
    Path managedFfmpegDirectory();

    /**
     * 创建并返回为宿主管理的 FFmpeg 安装预留的目录。
     *
     * @return 已准备的受管 FFmpeg 目录
     * @throws IOException 无法准备目录时抛出
     */
    default Path prepareManagedFfmpegDirectory() throws IOException {
        throw new UnsupportedOperationException(
                "Managed FFmpeg storage is not supported by this host");
    }

    /**
     * 返回此宿主能否安装 FFmpeg。
     *
     * @return 是否支持受管安装
     */
    boolean supportsManagedFfmpegInstall();

    /**
     * 安装宿主管理的 FFmpeg，并报告粗粒度进度。
     *
     * @param proxy    可选下载代理
     * @param listener 进度监听器
     * @return 已安装的 FFmpeg 路径
     * @throws IOException          安装失败时抛出
     * @throws InterruptedException 安装被中断时抛出
     */
    FfmpegInstallation installManagedFfmpeg(
            FfmpegProxy proxy,
            FfmpegProgressListener listener
    ) throws
            IOException,
            InterruptedException;

    /**
     * 返回当前维护任务快照。
     *
     * @return 维护快照
     */
    MaintenanceSnapshot maintenanceSnapshot();

    /**
     * 返回默认作品元数据回填选项。
     *
     * @return 默认回填选项
     */
    BackfillOptions defaultBackfillOptions();

    /**
     * 返回数据库是否支持请求的回填列。
     *
     * @param column 请求的列
     * @return 是否支持该列
     */
    boolean supportsBackfillColumn(DatabaseColumn column);

    /**
     * 统计作品元数据回填候选项。
     *
     * @param options 回填选项
     * @return 候选项数量
     * @throws Exception 无法检查数据库时抛出
     */
    int countBackfillCandidates(BackfillOptions options) throws Exception;

    /**
     * 执行作品元数据回填。
     *
     * @param options 回填选项
     * @return 聚合结果
     * @throws Exception 回填失败时抛出
     */
    BackfillSummary runBackfill(BackfillOptions options) throws Exception;

    /**
     * 统计旧 JSON 到数据库迁移的候选项。
     *
     * @param options 迁移选项
     * @return 候选项数量
     * @throws Exception 无法检查旧状态时抛出
     */
    int countMigrationCandidates(MigrationOptions options) throws Exception;

    /**
     * 执行旧 JSON 到数据库的迁移。
     *
     * @param options  迁移选项
     * @param reporter 进度报告器
     * @return 聚合结果
     * @throws Exception 迁移失败时抛出
     */
    MigrationSummary runMigration(
            MigrationOptions options,
            Consumer<String> reporter
    ) throws
            Exception;

    /**
     * 为桌面工具打开隔离日志会话。
     *
     * @param stem 稳定日志文件主干名
     * @return 活动日志会话
     * @throws Exception 无法创建日志会话时抛出
     */
    ToolLogSession openToolLog(String stem) throws Exception;

    /**
     * FFmpeg 安装的路径与来源元数据。
     *
     * @param ffmpegPath  FFmpeg 可执行文件路径
     * @param ffprobePath FFprobe 可执行文件路径
     * @param homeDir     安装主目录
     * @param source      安装来源
     */
    record FfmpegInstallation(
            Path ffmpegPath,
            Path ffprobePath,
            Path homeDir,
            FfmpegSource source
    ) {
    }

    /**
     * FFmpeg 安装的稳定来源。
     */
    enum FfmpegSource {
        /**
         * 安装在宿主管理的存储中。
         */
        MANAGED,
        /**
         * 随应用分发包提供。
         */
        BUNDLED,
        /**
         * 从操作系统发现。
         */
        SYSTEM
    }

    /**
     * 仅用于受管 FFmpeg 下载的代理设置。
     *
     * @param enabled 是否启用代理
     * @param host    代理主机
     * @param port    代理端口
     */
    record FfmpegProxy(boolean enabled, String host, int port) {
        /**
         * 将缺失的代理主机规范化为空字符串。
         *
         * @param enabled 是否启用代理
         * @param host    代理主机
         * @param port    代理端口
         */
        public FfmpegProxy {
            host = host == null ? "" : host.trim();
        }
    }

    /**
     * 安装受管 FFmpeg 时报告的稳定阶段。
     */
    enum FfmpegInstallStage {
        /**
         * 正在建立下载连接。
         */
        CONNECTING,
        /**
         * 正在下载归档。
         */
        DOWNLOADING,
        /**
         * 正在解压已下载的归档。
         */
        EXTRACTING,
        /**
         * 受管安装已完成。
         */
        COMPLETED
    }

    /**
     * 接收安装受管 FFmpeg 时的粗粒度进度。
     */
    @FunctionalInterface
    interface FfmpegProgressListener {
        /**
         * 报告当前阶段和工作单元。
         *
         * @param stage   稳定进度阶段
         * @param current 已完成单元数
         * @param total   总单元数；未知时为零
         */
        void onProgress(FfmpegInstallStage stage, long current, long total);
    }

    /**
     * 桌面回填工具请求的数据库列。
     *
     * @param tableName  表名
     * @param columnName 列名
     */
    record DatabaseColumn(String tableName, String columnName) {
    }

    /**
     * 用户选择的作品元数据回填选项。
     *
     * @param dbPath    数据库路径
     * @param proxyHost 代理主机
     * @param proxyPort 代理端口
     * @param useProxy  是否启用代理
     * @param delayMs   请求间隔毫秒数
     * @param limit     最大候选项数量
     * @param dryRun    是否避免持久化写入
     */
    record BackfillOptions(
            String dbPath,
            String proxyHost,
            int proxyPort,
            boolean useProxy,
            long delayMs,
            int limit,
            boolean dryRun
    ) {
    }

    /**
     * 一次作品元数据回填的聚合结果。
     *
     * @param totalCandidates       候选项总数
     * @param processed             已处理数量
     * @param filledAuthor          已填充作者值数量
     * @param filledR18             已填充成人标记数量
     * @param filledAi              已填充 AI 标记数量
     * @param filledDescription     已填充描述数量
     * @param filledTags            已填充标签集数量
     * @param filledSeries          已填充系列值数量
     * @param deletedCount          观察到的已删除记录数量
     * @param skipped               已跳过数量
     * @param previouslyUnreachable 先前不可达数量
     * @param newlyUnreachable      新增不可达数量
     * @param dryRun                是否禁用持久化写入
     * @param rateLimited           是否因限流停止
     */
    record BackfillSummary(
            int totalCandidates,
            int processed,
            int filledAuthor,
            int filledR18,
            int filledAi,
            int filledDescription,
            int filledTags,
            int filledSeries,
            int deletedCount,
            int skipped,
            int previouslyUnreachable,
            int newlyUnreachable,
            boolean dryRun,
            boolean rateLimited
    ) {
    }

    /**
     * 用户选择的旧数据迁移路径。
     *
     * @param dbPath     目标数据库路径
     * @param rootFolder 旧数据根目录
     */
    record MigrationOptions(String dbPath, String rootFolder) {
    }

    /**
     * 一次旧数据迁移的聚合结果。
     *
     * @param totalCandidates    候选项总数
     * @param migrated           已迁移数量
     * @param skipped            已跳过数量
     * @param historyFileMissing 旧历史文件是否缺失
     * @param message            摘要消息
     */
    record MigrationSummary(
            int totalCandidates,
            int migrated,
            int skipped,
            boolean historyFileMissing,
            String message
    ) {
    }

    /**
     * 活动维护任务的只读进度快照。
     *
     * @param active        维护是否活动
     * @param trigger       触发器标识符
     * @param index         当前任务索引
     * @param total         任务总数
     * @param taskName      当前任务名称
     * @param taskStartedAt 任务开始时间的 epoch 毫秒值
     * @param unitsDone     已完成工作单元数
     * @param unitsTotal    工作单元总数
     */
    record MaintenanceSnapshot(
            boolean active,
            String trigger,
            int index,
            int total,
            String taskName,
            long taskStartedAt,
            int unitsDone,
            int unitsTotal
    ) {
    }

    /**
     * 一次桌面工具调用拥有的隔离 HTML 日志会话。
     */
    interface ToolLogSession extends AutoCloseable {
        /**
         * 返回稳定的最新日志路径。
         *
         * @return 最新日志路径
         */
        Path latestPath();

        /**
         * 返回本次调用的不可变日志路径。
         *
         * @return 本次调用的日志路径
         */
        Path sessionPath();

        /**
         * 在系统浏览器中打开最新日志。
         *
         * @throws Exception 无法打开浏览器时抛出
         */
        void openLatestInBrowser() throws Exception;

        /**
         * 关闭日志会话。
         */
        @Override
        void close();
    }

    /**
     * 检查所选数据库中记录的作品目录。
     *
     * @param databasePath SQLite 数据库路径
     * @return 活动作总数和不可访问条目
     * @throws Exception 无法读取数据库时抛出
     */
    default FolderCheckResult checkArtworkFolders(Path databasePath) throws Exception {
        throw new UnsupportedOperationException(
                "Artwork folder checking is not supported by this host");
    }

    /**
     * 更新一个作品的原始目录或移动后目录。
     *
     * @param databasePath SQLite 数据库路径
     * @param artworkId    作品标识符
     * @param moved        是否选择移动后目录列
     * @param newPath      替换目录路径
     * @throws Exception 更新失败时抛出
     */
    default void updateArtworkFolder(
            Path databasePath,
            long artworkId,
            boolean moved,
            String newPath
    ) throws Exception {
        throw new UnsupportedOperationException(
                "Artwork folder updates are not supported by this host");
    }

    /**
     * 加载已持久化的图片分类器设置。
     *
     * @param rootFolder 应用下载根目录
     * @return 分类器设置
     * @throws IOException 无法读取设置时抛出
     */
    default ImageClassifierSettings loadImageClassifierSettings(String rootFolder) throws
            IOException {
        throw new UnsupportedOperationException(
                "Image classifier settings are not supported by this host");
    }

    /**
     * 持久化图片分类器设置。
     *
     * @param rootFolder 应用下载根目录
     * @param settings   要持久化的设置
     * @throws IOException 无法写入设置时抛出
     */
    default void saveImageClassifierSettings(
            String rootFolder,
            ImageClassifierSettings settings
    ) throws IOException {
        throw new UnsupportedOperationException(
                "Image classifier settings are not supported by this host");
    }

    /**
     * 检查分类器路径是否为现存目录。
     *
     * @param path 要检查的路径
     * @return 该路径是否为现存目录
     */
    default boolean isImageClassifierDirectory(Path path) {
        throw new UnsupportedOperationException(
                "Image classifier paths are not supported by this host");
    }

    /**
     * 按显示顺序列出分类器工作目录。
     *
     * @param parent 父目录
     * @return 有序子目录
     * @throws IOException 无法读取目录时抛出
     */
    default List<Path> listImageClassifierFolders(Path parent) throws IOException {
        throw new UnsupportedOperationException(
                "Image classifier folders are not supported by this host");
    }

    /**
     * 列出一个分类器工作目录中支持的图片。
     *
     * @param folder 工作目录
     * @return 有序图片路径
     * @throws IOException 无法读取目录时抛出
     */
    default List<Path> listImageClassifierImages(Path folder) throws IOException {
        throw new UnsupportedOperationException(
                "Image classifier images are not supported by this host");
    }

    /**
     * 仅在分类器工作目录为空时删除它。
     *
     * @param folder 工作目录
     * @throws IOException 无法检查或删除目录时抛出
     */
    default void deleteImageClassifierFolderIfEmpty(Path folder) throws IOException {
        throw new UnsupportedOperationException(
                "Image classifier cleanup is not supported by this host");
    }

    /**
     * 解析已配置的分类器服务，包括 HTTP/HTTPS 回退。
     *
     * @param configuredUrl 已配置的服务 URL
     * @return 可用性和实际响应的 URL
     */
    default ImageClassifierServer checkImageClassifierServer(String configuredUrl) {
        throw new UnsupportedOperationException(
                "Image classifier server checks are not supported by this host");
    }

    /**
     * 解析分类器目录对应的作品身份与可选元数据。
     *
     * @param folder 分类器工作目录
     * @param server 先前解析的服务状态
     * @return 作品元数据；无法解析出正数作品 ID 时为空
     */
    default Optional<ImageClassifierArtwork> resolveImageClassifierArtwork(
            Path folder,
            ImageClassifierServer server
    ) {
        throw new UnsupportedOperationException(
                "Image classifier artwork lookup is not supported by this host");
    }

    /**
     * 复制、移除并记录一个已分类作品。
     *
     * <p>复制失败会回滚并抛出异常。源文件删除失败会报告给
     * {@code deleteFailureHandler}；返回 {@code true} 会重试删除，返回
     * {@code false} 则保留已复制的目标文件及其余源文件。</p>
     *
     * @param sourceFolder         源工作目录
     * @param images               源图片
     * @param artworkId            作品标识符
     * @param targetFolder         选择的分类目标目录
     * @param server               先前解析的服务状态
     * @param deleteFailureHandler 源文件删除失败时的用户决策回调
     * @return 实际目标目录
     * @throws IOException 创建目标目录或复制失败时抛出
     */
    default Path classifyImageFolder(
            Path sourceFolder,
            List<Path> images,
            long artworkId,
            Path targetFolder,
            ImageClassifierServer server,
            ImageClassifierDeleteFailureHandler deleteFailureHandler
    ) throws IOException {
        throw new UnsupportedOperationException("Image classification is not supported by this host");
    }

    /**
     * 目录检查器报告的作品目录。
     *
     * @param artworkId 作品标识符
     * @param title     作品标题
     * @param path      不可访问路径；可能为 {@code null}
     * @param moved     路径是否来自移动后目录列
     */
    record FolderArtwork(
            long artworkId,
            String title,
            String path,
            boolean moved
    ) {
    }

    /**
     * 目录检查的聚合结果。
     *
     * @param total        活动作总数
     * @param inaccessible 不可访问的作品目录
     */
    record FolderCheckResult(int total, List<FolderArtwork> inaccessible) {
        /**
         * 复制结果列表，防止调用方修改宿主状态。
         *
         * @param total        活动作总数
         * @param inaccessible 不可访问的作品目录
         */
        public FolderCheckResult {
            inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
        }
    }

    /**
     * 一个已配置的分类器目标。
     *
     * @param folder 目标目录
     * @param remark 用户可见备注
     */
    record ImageClassifierTarget(String folder, String remark) {
    }

    /**
     * 已持久化的分类器设置。
     *
     * @param defaultFolder  默认源父目录
     * @param showSkipButton 是否显示跳过按钮
     * @param serverUrl      已配置的后端 URL
     * @param targets        分类器目标
     */
    record ImageClassifierSettings(
            String defaultFolder,
            boolean showSkipButton,
            String serverUrl,
            List<ImageClassifierTarget> targets
    ) {
        /**
         * 规范化可为 null 的标量值并复制目标列表。
         *
         * @param defaultFolder  默认源父目录
         * @param showSkipButton 是否显示跳过按钮
         * @param serverUrl      已配置的后端 URL
         * @param targets        分类器目标
         */
        public ImageClassifierSettings {
            defaultFolder = defaultFolder == null ? "" : defaultFolder;
            serverUrl = serverUrl == null || serverUrl.isBlank() ? "http://localhost:6999" : serverUrl.trim();
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * 已解析的分类器服务状态。
     *
     * @param available 服务是否成功响应
     * @param url       已配置或成功解析的 URL
     */
    record ImageClassifierServer(boolean available, String url) {
    }

    /**
     * 分类器视图使用的作品元数据。
     *
     * @param artworkId 作品标识符
     * @param title     可选标题
     * @param xRestrict 可选 Pixiv 限制值
     */
    record ImageClassifierArtwork(long artworkId, String title, Integer xRestrict) {
    }

    /**
     * 接收源文件删除失败，并返回是否应重试删除。
     */
    @FunctionalInterface
    interface ImageClassifierDeleteFailureHandler {
        /**
         * 处理一次删除失败。
         *
         * @param detail       失败详情
         * @param sourceFolder 仍存在的源目录
         * @return {@code true} 表示重试，{@code false} 表示保留其余源文件
         */
        boolean retry(String detail, Path sourceFolder);
    }
}
