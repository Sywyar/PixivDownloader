package top.sywyar.pixivdownload.plugin.api.gui.importing;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import java.util.List;
import java.util.Objects;

/**
 * 从用户选择的记录文件登记现存本地作品的可选能力；不描述页面或控件。
 * 宿主发布精确身份，撤回后旧句柄拒绝命令，不转投替代来源。预览不写库，
 * 启动必须消费预览凭据；提供者负责后台任务登记、取消和实际退出。
 * @param identity 精确发布身份
 * @param description 注册时物化的说明
 * @since 1.4.0
 */
public record DesktopFileImportTool(Identity identity, Description description) {
    /** 校验发布描述。
     * @param identity 精确发布身份
     * @param description 注册时物化的说明
     */
    public DesktopFileImportTool { Objects.requireNonNull(identity); Objects.requireNonNull(description); }
    /**
     * @param pluginId 可信插件身份
     * @param packageId 包身份
     * @param generation 运行代际
     * @param publication 发布身份
     */
    public record Identity(String pluginId, String packageId, long generation, long publication) {}
    /**
     * @param title 工具名称
     * @param help 输入格式与导入行为说明
     */
    public record Description(DesktopUiText title, DesktopUiText help) {
        /** 校验纯值边界。
         * @param title 工具名称
         * @param help 输入与行为说明
         */
        public Description { Objects.requireNonNull(title); Objects.requireNonNull(help); }
    }
    /**
     * 插件 child context 可提供一个来源；所有命令经宿主可撤回代理调用。
     * 检查同步返回，提供者须支持状态查询与取消同时到达；启动只接纳后台任务。
     */
    public interface Source {
        /**
         * @return 注册时冻结的说明
         */
        Description description();
        /**
         * @param request 本次输入
         * @return 不产生持久化副作用的检查结果；新检查使旧凭据失效
         */
        Result<Preview> inspect(Request request);
        /**
         * 消费一次性凭据并启动导入；凭据只用于本次执行，不能进入结果、日志或磁盘。
         * @param token 预览凭据
         * @param credential 可空的临时来源站点凭据
         * @return 接纳状态或明确失败，接纳不等于完成
         */
        Result<Status> start(String token, String credential);
        /**
         * @return 当前实际状态；离开界面不取消执行
         */
        Status status();
        /**
         * 请求取消；运行线程退出前仍报告正在取消，已登记作品保留。
         */
        void cancel();
    }
    /**
     * @param recordFile 用户选择的记录文件或分片目录绝对路径
     * @param sourceRoot 用户批准的源目录绝对路径
     */
    public record Request(String recordFile, String sourceRoot) {
        /** 校验纯值边界。
         * @param recordFile 记录文件或分片目录
         * @param sourceRoot 用户批准的原文件根目录
         */
        public Request {
            requirePath(recordFile);
            requirePath(sourceRoot);
        }
        private static void requirePath(String value) {
            if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid import path");
        }
    }
    /**
     * 预览条目的确定状态；候选仍需获取元数据并校验完整文件集合。
     */
    public enum Disposition {
        /** 本地文件可供下一步核验。 */ CANDIDATE,
        /** 作品已登记。 */ ALREADY_PRESENT,
        /** 缺失文件或页不完整。 */ MISSING_FILES,
        /** 路径或记录冲突。 */ CONFLICT
    }
    /**
     * @param workKey 来源内的作品身份
     * @param files 记录中的文件数
     * @param disposition 本地检查结果
     */
    public record Item(String workKey, int files, Disposition disposition) {
        /** 校验纯值边界。
         * @param workKey 来源内作品身份
         * @param files 文件数
         * @param disposition 本地检查结果
         */
        public Item {
            if (workKey == null || workKey.length() > 128 || files < 0) throw new IllegalArgumentException("invalid import item");
            Objects.requireNonNull(disposition);
        }
    }
    /**
     * 有界样本不限制实际导入数量，计数覆盖全部输入。
     * @param token 单次启动凭据
     * @param records 原始记录数
     * @param works 去重作品数
     * @param candidates 待联网核验数
     * @param skipped 已登记数
     * @param unavailable 缺文件或冲突数
     * @param samples 前若干作品；最多 100 项
     */
    public record Preview(String token, int records, int works, int candidates, int skipped, int unavailable, List<Item> samples) {
        /** 校验纯值边界。
         * @param token 一次性预览凭据
         * @param records 原始记录数
         * @param works 去重作品数
         * @param candidates 待核验数
         * @param skipped 已登记数
         * @param unavailable 缺文件或冲突数
         * @param samples 最多 100 项样本
         */
        public Preview {
            if (token == null || token.isBlank() || token.length() > 128 || records < 0 || works < 0 || candidates < 0 || skipped < 0
                    || unavailable < 0 || (long) candidates + skipped + unavailable != works) throw new IllegalArgumentException("invalid import preview");
            samples = List.copyOf(samples);
            if (samples.size() > 100) throw new IllegalArgumentException("too many import samples");
        }
    }
    /**
     * 执行状态由实际任务驱动。
     */
    public enum Phase {
        /** 尚未检查。 */ IDLE,
        /** 正在检查本地输入。 */ INSPECTING,
        /** 预览可供启动。 */ READY,
        /** 执行尚未退出。 */ RUNNING,
        /** 已请求取消，执行尚未退出。 */ CANCELLING,
        /** 全部候选已处理，可能存在逐项失败。 */ COMPLETED,
        /** 已取消并实际退出。 */ CANCELLED,
        /** 任务未能完成。 */ FAILED
    }
    /**
     * @param workKey 失败作品身份
     * @param reason 可本地化失败说明，不携带凭据或原始异常
     */
    public record Problem(String workKey, DesktopUiText reason) {
        /** 校验纯值边界。
         * @param workKey 失败作品身份，任务级失败可为空
         * @param reason 可本地化失败说明
         */
        public Problem {
            if (workKey == null || workKey.length() > 128) throw new IllegalArgumentException("invalid work key");
            Objects.requireNonNull(reason);
        }
    }
    /**
     * @param phase 当前阶段
     * @param total 候选数
     * @param processed 已处理数
     * @param imported 成功数
     * @param skipped 执行时已存在数
     * @param failed 失败数
     * @param problems 前若干失败，最多 100 项
     */
    public record Status(Phase phase, int total, int processed, int imported, int skipped, int failed, List<Problem> problems) {
        /** 校验纯值边界。
         * @param phase 执行阶段
         * @param total 候选数
         * @param processed 已处理数
         * @param imported 成功数
         * @param skipped 执行时已存在数
         * @param failed 失败数
         * @param problems 最多 100 项失败说明
         */
        public Status {
            Objects.requireNonNull(phase);
            if (total < 0 || processed < 0 || imported < 0 || skipped < 0 || failed < 0
                    || processed > total || (long) imported + skipped + failed != processed) throw new IllegalArgumentException("invalid import status");
            problems = List.copyOf(problems);
            if (problems.size() > 100) throw new IllegalArgumentException("too many import problems");
        }
        /**
         * @return 尚未实际退出的任务状态
         */
        public boolean active() { return phase == Phase.INSPECTING || phase == Phase.RUNNING || phase == Phase.CANCELLING; }
    }
    /**
     * @param <T> 结果类型
     * @param value 成功结果
     * @param error 失败说明；二者必须恰有一个非空
     */
    public record Result<T>(T value, DesktopUiText error) {
        /** 校验纯值边界。
         * @param value 成功结果
         * @param error 失败说明
         */
        public Result {
            if ((value == null) == (error == null)) throw new IllegalArgumentException("result requires value or error");
        }
        /**
         * @param value 成功结果
         * @param <T> 结果类型
         * @return 成功
         */
        public static <T> Result<T> success(T value) { return new Result<>(Objects.requireNonNull(value), null); }
        /**
         * @param error 失败说明
         * @param <T> 结果类型
         * @return 失败
         */
        public static <T> Result<T> failure(DesktopUiText error) { return new Result<>(null, Objects.requireNonNull(error)); }
        /**
         * @return 成功结果
         * @throws OperationException 操作失败
         */
        public T valueOrThrow() {
            if (error != null) throw new OperationException(error);
            return value;
        }
    }
    /**
     * GUI 边界失败只携带纯值，不持有插件异常或 classloader。
     */
    public static final class OperationException extends RuntimeException {
        /** 不携带插件异常对象的纯值说明。 */
        private final DesktopUiText text;
        /**
         * @param text 可本地化说明
         */
        public OperationException(DesktopUiText text) { super(text.key()); this.text = text; }
        /**
         * @return 失败说明
         */
        public DesktopUiText text() { return text; }
    }
}
