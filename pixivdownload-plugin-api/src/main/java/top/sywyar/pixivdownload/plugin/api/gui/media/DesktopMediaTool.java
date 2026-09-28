package top.sywyar.pixivdownload.plugin.api.gui.media;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;

import java.util.List;
import java.util.Objects;

/**
 * 原生桌面媒体维护的业务合同，不携带页面、组件或插件对象。
 * 宿主为活动 Source 盖章精确 publication；撤回后旧句柄必须失败，不能改投新实例。
 *
 * @param identity 宿主颁发的来源身份
 * @param description 工具的文本与默认输出语义
 */
public record DesktopMediaTool(Identity identity, Description description) {
    /**
     * @param identity 宿主颁发的来源身份
     * @param description 工具的文本与默认输出语义
     */
    public DesktopMediaTool {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(description);
    }

    /**
     * @param pluginId 功能 owner
     * @param packageId 包 owner
     * @param generation 物理代际
     * @param publication 本次启动身份
     */
    public record Identity(String pluginId, String packageId, long generation, long publication) {}

    /**
     * @param title 工具名称
     * @param namespace 工具消息命名空间
     * @param imageFormats 默认图片格式
     * @param ugoiraFormats 默认动图格式
     */
    public record Description(DesktopUiText title, String namespace, String imageFormats, String ugoiraFormats) {
        /**
         * @param title 工具名称
         * @param namespace 工具消息命名空间
         * @param imageFormats 默认图片格式
         * @param ugoiraFormats 默认动图格式
         */
        public Description {
            Objects.requireNonNull(title);
            if (namespace == null || !namespace.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("namespace");
            Objects.requireNonNull(imageFormats);
            Objects.requireNonNull(ugoiraFormats);
        }
    }

    /** child context 可选提供的用户主动命令；经宿主可撤回代理调用，异常不等于成功。 */
    public interface Source {
        /** @return 在 publication 发布前物化的业务描述 */
        Description description();
        /**
         * @param request 本次明确选择的范围
         * @return 只读预览与一次性启动凭据
         */
        Result<Preview> preview(Request request);
        /**
         * @param token 预览凭据
         * @return 已接纳任务状态
         */
        Result<Status> start(String token);
        /** @return 当前任务的真实状态 */
        Status status();
        /** 请求停止当前任务；在终态前保留正在取消的展示。 */
        void cancel();
        /** @return 用户主动执行的真实编解码检查结果 */
        Result<Report> capabilities();
    }

    /**
     * @param imageFormats 图片格式
     * @param ugoiraFormats 动图格式
     * @param repairThumbnails 补齐缩略图
     */
    public record Request(String imageFormats, String ugoiraFormats, boolean repairThumbnails) {}
    /**
     * @param artworkId 作品 ID
     * @param page 页码
     * @param fileName 本地文件名
     * @param missingFormats 可补齐的缺失格式
     * @param missingThumbnail 是否需要生成缩略图
     */
    public record Item(long artworkId, int page, String fileName, List<String> missingFormats, boolean missingThumbnail) {
        /**
         * @param artworkId 作品 ID
         * @param page 页码
         * @param fileName 本地文件名
         * @param missingFormats 复制为不可变列表的缺失格式
         * @param missingThumbnail 是否需要生成缩略图
         */
        public Item { missingFormats = List.copyOf(missingFormats); }
    }
    /**
     * @param token 一次性预览凭据
     * @param files 真实候选文件
     * @param scanned 已检查的作品数
     * @param skipped 缺少可用源或无法读取的文件数
     * @param limited 是否达到本批候选上限，处理后需再次检测
     */
    public record Preview(String token, List<Item> files, int scanned, int skipped, boolean limited) {
        /**
         * @param token 一次性预览凭据
         * @param files 复制为不可变列表的候选文件
         * @param scanned 已检查的作品数
         * @param skipped 缺少可用源或无法读取的文件数
         * @param limited 是否达到本批候选上限
         */
        public Preview { files = List.copyOf(files); }
    }
    /**
     * @param artworkId 失败作品 ID
     * @param page 失败页码
     */
    public record Failure(long artworkId, int page) {}
    /**
     * @param state idle/scanning/running/completed/cancelled
     * @param total 总数
     * @param completed 已处理数
     * @param failed 失败数
     * @param failures 失败文件身份
     */
    public record Status(String state, int total, int completed, int failed, List<Failure> failures) {
        /**
         * @param state 任务状态机器码
         * @param total 总数
         * @param completed 已处理数
         * @param failed 失败数
         * @param failures 复制为不可变列表的失败文件身份
         */
        public Status { failures = List.copyOf(failures); }
    }
    /**
     * @param name 能力机器码
     * @param available 实测是否可用
     */
    public record Capability(String name, boolean available) {}
    /**
     * @param command 实际执行路径
     * @param source 路径来源
     * @param capabilities 实测能力
     */
    public record Report(String command, String source, List<Capability> capabilities) {
        /**
         * @param command 实际执行路径
         * @param source 路径来源
         * @param capabilities 复制为不可变列表的实测能力
         */
        public Report { capabilities = List.copyOf(capabilities); }
    }

    /**
     * 业务拒绝作为纯值跨越调用边界，避免异常脱敏丢失消息键与参数。
     * @param value 成功结果
     * @param error 可本地化的业务拒绝；与结果必须有且仅有一个非空
     * @param <T> 纯值结果类型
     */
    public record Result<T>(T value, DesktopUiText error) {
        /**
         * @param value 成功结果；失败时为 {@code null}
         * @param error 失败语义；成功时为 {@code null}
         * @throws IllegalArgumentException 结果与失败语义同时为空或同时非空
         */
        public Result {
            if ((value == null) == (error == null)) throw new IllegalArgumentException("result");
        }
        /** @return 当前结果；由 GUI 在调用边界之外转换失败语义 */
        public T valueOrThrow() {
            if (error != null) throw new OperationException(error);
            return value;
        }
    }

    /** GUI 本地使用的失败，不携带插件异常或其 classloader。 */
    public static final class OperationException extends RuntimeException {
        /** 可由 GUI 按当前语言解析的失败语义。 */
        private final DesktopUiText text;
        /** @param text 用户可见的失败语义 */
        public OperationException(DesktopUiText text) {
            super(text.key());
            this.text = text;
        }
        /** @return 当前语言可解析的失败语义 */
        public DesktopUiText text() { return text; }
    }
}
