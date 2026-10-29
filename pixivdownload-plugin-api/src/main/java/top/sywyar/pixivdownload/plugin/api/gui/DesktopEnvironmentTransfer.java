package top.sywyar.pixivdownload.plugin.api.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可选的宿主迁移能力；归档、校验、备份和恢复由宿主负责。
 * 桌面会话仅传递用户选择，不直接写运行目录。能力缺席时隐藏入口；命令失败不得显示成功。
 * 密码只在同步调用期间使用，调用方在返回后清除字符数组，不保存到界面快照。
 * @since 1.4
 */
public interface DesktopEnvironmentTransfer {
    /**
     * 导出已保存配置；含凭据、状态或数据库时必须提供非空密码，资源要求同时选择数据库。
     * @param destination 尚不存在的目标文件
     * @param password 归档密码；不含受保护类别时允许 null 或空数组
     * @param includeCredentials 是否包含已声明凭据和管理员身份文件
     * @param includeDatabases 是否包含主库和已声明私库的快照
     * @param includeState 是否包含已声明的状态和暂停队列
     * @param includeResources 是否包含已声明用户资源
     * @throws IOException 停写、校验或读写失败；不会发布部分归档
     */
    void exportConfiguration(Path destination, char[] password, boolean includeCredentials, boolean includeDatabases,
                             boolean includeState, boolean includeResources) throws IOException;

    /**
     * 完整校验后返回预览，不修改目标文件、不连接归档描述的数据库。
     * @param archive 已存在的归档文件
     * @param password 解密密码；明文归档可为空
     * @return 绑定当前归档和目标状态的选择预览
     * @throws IOException 归档、密码或配置预检失败
     */
    Preview previewConfiguration(Path archive, char[] password) throws IOException;

    /**
     * 保存备份并请求重启；目标变化时拒绝，数据库分别提交。
     * @param token 最近一次预览的确认标识
     * @param selected 要恢复的已声明字段
     * @param mappedPaths 选中路径字段的新值；未指定的字段使用归档值
     * @param password 归档密码，重新校验时使用
     * @param includeDatabases 是否恢复预览列出的全部数据库
     * @param fileGroups 要恢复的已声明文件组
     * @param databasePaths 数据库前缀 ID 到目标绝对路径；0 为下载根
     * @throws IOException 选择、目标状态、备份或重启请求失败
     */
    void importConfiguration(String token, Set<FieldKey> selected, Map<FieldKey, String> mappedPaths, char[] password,
                             boolean includeDatabases, Set<FieldKey> fileGroups, Map<Long, String> databasePaths) throws IOException;

    /**
     * 查询实际恢复结果；请求重启不等同于导入完成。
     * @return 最后一次任务状态，无任务时为 NONE
     * @throws IOException 任务日志不可读取或校验失败
     */
    RestoreState restoreState() throws IOException;

    /**
     * 只读查询安装登记；修复由安装器在明确确认目标位置后执行。
     * @return 安装类别与已知路径，无法读取时为 UNKNOWN
     */
    Installation installation();

    /** 原生安装登记与当前目录的关系。 */
    enum InstallationKind {
        /** 当前平台或启动方式没有原生安装探测。 */ UNSUPPORTED,
        /** 当前目录没有安装登记或卸载记录。 */ PORTABLE,
        /** 当前目录与登记一致。 */ INSTALLED,
        /** 安装记录指向其它目录。 */ MOVED,
        /** 登记不完整或上次修复中断。 */ REPAIR_REQUIRED,
        /** 无法可靠读取登记。 */ UNKNOWN
    }
    /**
     * 安装探测结果。
     * @param kind 探测类别
     * @param directory 当前原生程序目录，不可用时为空
     * @param registeredDirectory 已登记目录，不可用时为空
     */
    record Installation(InstallationKind kind, String directory, String registeredDirectory) {}

    /**
     * 所有者范围内的字段或文件组标识。
     * @param owner 宿主或插件 ID
     * @param key 字段键或文件组 ID
     */
    record FieldKey(String owner, String key) {}
    /**
     * 敏感字段的前后值始终为空字符串，仅表示该字段将被更新。
     * @param field 字段身份
     * @param sensitive 是否为凭据
     * @param path 是否允许输入路径映射
     * @param before 目标已保存值，敏感值为空
     * @param after 归档值，敏感值为空
     */
    record Change(FieldKey field, boolean sensitive, boolean path, String before, String after) {}
    /**
     * 一个完整数据库的恢复候选。
     * @param owner 数据所属宿主或插件
     * @param rows 归档行数
     * @param replacesExisting 是否替换现有目标
     */
    record DatabaseStore(String owner, long rows, boolean replacesExisting) {}
    /**
     * 可映射的数据库路径前缀。
     * @param id 源前缀 ID，0 表示下载根
     * @param source 源安装解析出的路径
     */
    record DatabasePath(long id, String source) {}
    /**
     * 插件补齐提示，不代表兼容性或安装授权。
     * @param id 插件 ID
     * @param sourceVersion 归档版本，不可用时为空
     * @param installed 当前是否已注册该插件
     * @param currentVersion 当前版本，不可用时为空
     */
    record PluginPackage(String id, String sourceVersion, boolean installed, String currentVersion) {}
    /**
     * 仓库地址仅供重新导入并确认信任，不随环境恢复自动启用。
     * @param id 仓库 ID
     * @param descriptorUrl 已过滤的 HTTPS 描述地址，不可用时为空
     * @param manifestUrl 已过滤的 HTTPS 清单地址，不可用时为空
     */
    record RepositoryLocation(String id, String descriptorUrl, String manifestUrl) {}
    /**
     * 可选择的所有者文件组。
     * @param id 文件组身份
     * @param kind STATE、RESOURCE 或 CREDENTIALS
     * @param files 文件数
     * @param bytes 未压缩内容字节数
     * @param requiresDatabase 是否要求同时恢复数据库
     */
    record FileGroup(FieldKey id, String kind, long files, long bytes, boolean requiresDatabase) {}
    /**
     * 只读预览；集合不可修改，未声明的数据不能通过手工构造选择恢复。
     * @param token 确认标识
     * @param applicationVersion 来源应用版本
     * @param encrypted 归档是否加密
     * @param changes 可恢复的配置变化
     * @param unavailable 当前未声明的字段
     * @param databases 可恢复数据库，默认由用户显式选择
     * @param databaseFailure 数据库不可恢复的受控消息键，无失败时为空
     * @param files 可恢复文件组
     * @param unavailableFiles 当前未声明或不兼容的文件组
     * @param databasePaths 可映射的源前缀
     * @param plugins 插件版本提示
     * @param repositories 仓库位置提示
     */
    record Preview(String token, String applicationVersion, boolean encrypted, List<Change> changes, Set<FieldKey> unavailable,
                   List<DatabaseStore> databases, String databaseFailure, List<FileGroup> files, Set<FieldKey> unavailableFiles,
                   List<DatabasePath> databasePaths, List<PluginPackage> plugins, List<RepositoryLocation> repositories) {
        /**
         * 构造不可变的选择快照。
         * @param token 确认标识
         * @param applicationVersion 来源应用版本
         * @param encrypted 是否加密
         * @param changes 可选配置变化
         * @param unavailable 未声明字段
         * @param databases 可选数据库
         * @param databaseFailure 数据库错误消息键
         * @param files 可选文件组
         * @param unavailableFiles 不可用文件组
         * @param databasePaths 可映射前缀
         * @param plugins 插件版本提示
         * @param repositories 仓库位置提示
         */
        public Preview {
            changes = List.copyOf(changes); unavailable = Set.copyOf(unavailable); databases = List.copyOf(databases);
            files = List.copyOf(files); unavailableFiles = Set.copyOf(unavailableFiles);
            databasePaths = List.copyOf(databasePaths);
            plugins = List.copyOf(plugins); repositories = List.copyOf(repositories);
        }
    }
    /** 文件和逐库恢复报告的聚合状态。 */
    enum Status {
        /** 没有任务。 */ NONE,
        /** 已备份并等待新进程。 */ PENDING,
        /** 正在恢复。 */ APPLYING,
        /** 所有已选目标恢复完成。 */ SUCCEEDED,
        /** 已停止，目标未变化或已回滚。 */ FAILED,
        /** 提交结果或回滚状态不确定，需人工核对备份。 */ RECOVERY_REQUIRED
    }
    /**
     * 最近一次恢复结果。
     * @param status 耐久状态
     * @param failureCode 受控错误码，不含源文件正文或凭据
     */
    record RestoreState(Status status, String failureCode) {}
}
