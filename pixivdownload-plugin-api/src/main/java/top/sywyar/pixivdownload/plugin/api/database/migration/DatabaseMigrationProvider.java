package top.sywyar.pixivdownload.plugin.api.database.migration;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import java.nio.file.Path;
import java.util.Set;

/**
 * 每个数据库 provider 的必需停机迁移行为。只操作自身目标，不解释其它 provider 的文件或 SQL。
 * 宿主必须持有实例锁和可信插件会话，直到全部迁移会话关闭；缺席或关闭的 provider 不可替换。
 * 所有失败均停止后续副作用；宿主只投影安全机器码，不输出底层异常原文、行数据、凭据或 cause。
 */
public interface DatabaseMigrationProvider {
    /**
     * 只读检查迁移端点及结构兼容性，不创建文件、角色、schema、备份或凭据。
     * 源端返回需要用户确认重建的辅助对象；普通业务表不得作为辅助对象省略。
     * 预检不代替停机后的锁定、目标准入和完整数据核验。
     * @param target 本提供者的精确主库或 owner 私库
     * @param schema 已合并的逻辑结构
     * @param source 是否检查迁出端；迁入端必须报告实际是否为空
     * @return 不含 SQL、行内容或凭据的检查事实
     * @throws Exception 连接、权限、结构或目标身份无法确认
     */
    Inspection inspectMigration(DatabaseConnectionTarget target, MigrationSchema schema, boolean source) throws Exception;

    /**
     * 只读检查非空目标能否在用户确认后安全备份并替换；不改变旧库。
     * 未实现此能力的提供者仍拒绝非空目标，宿主不得自行清空。
     * @param target 本提供者的精确迁入目标
     * @param schema 目标逻辑结构，用于拒绝未知业务对象
     * @return 不含秘密且绑定目标内容的变化凭据
     * @throws Exception 不支持替换、未知结构或无法确认旧库
     */
    default String inspectReplacement(DatabaseConnectionTarget target, MigrationSchema schema) throws Exception {
        throw new IllegalStateException("TARGET_REPLACEMENT_UNSUPPORTED");
    }

    /**
     * 核对已确认的旧目标，锁定并耐久备份，再向隔离暂存库迁入。
     * 会话核验成功后才可提交替换；提交前失败必须保留旧内容，备份始终保留。
     * 不确定的提交结果必须报错，不能声称已回滚。源库与配置不由本方法修改。
     * @param target 本提供者的精确迁入目标
     * @param schema 中性逻辑结构
     * @param replacement 已确认的变化凭据及新备份位置
     * @param control 取消与进度控制
     * @return 宿主负责关闭的替换会话，遵守普通迁入会话的核验和提交合同
     * @throws Exception 旧库变化、备份失败、目标占用或替换不受支持
     */
    default DatabaseImportSession replaceDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
            MigrationReplacement replacement, MigrationControl control) throws Exception {
        throw new IllegalStateException("TARGET_REPLACEMENT_UNSUPPORTED");
    }

    /**
     * 只读核验已提交目标与原迁出快照完全一致，供部分提交重试及配置切换使用。
     * 必须核对目标身份、结构、逐表数据、序列和引用；不能只验证连接或行数。
     * @param target 本提供者的精确目标
     * @param snapshot 已耐久保存的迁出快照
     * @param control 取消与进度控制
     * @throws Exception 目标发生变化或无法证明一致；不得修改目标来迎合快照
     */
    void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot, MigrationControl control) throws Exception;

    /**
     * @param empty 端点没有业务对象
     * @param rebuildObjects 源端需明确确认重建的辅助对象名
     */
    record Inspection(boolean empty, Set<String> rebuildObjects) {
        /**
         * 复制辅助对象集合，拒绝 SQL 片段。
         * @param empty 端点是否没有业务对象
         * @param rebuildObjects 源端需明确确认重建的辅助对象名
         */
        public Inspection {
            rebuildObjects = Set.copyOf(rebuildObjects);
            rebuildObjects.forEach(MigrationSchema::identifier);
        }
    }

    /**
     * 发现数据根中由本 provider 格式拥有的既有私库，用于首次切换前保护历史本地数据。
     * 无本地关系存储的 provider 返回空映射；不得打开连接、创建文件或解释其它 provider 的布局。
     * @param dataDirectory 数据根
     * @param installationId 安装身份
     * @return 绑定 owner 的既有本地目标
     * @throws Exception 发现不完整或路径不安全
     */
    java.util.Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(Path dataDirectory,
                                                                                String installationId) throws Exception;
    /**
     * 枚举当前安装的全部既有私库存储，包括声明已缺失的 owner；不得创建库或遗漏未知 owner。
     * @param main 固定的主目标
     * @param dataDirectory 宿主管理的数据根目录
     * @param installationId 安装身份
     * @return 既有 owner 的不可变集合
     * @throws Exception 枚举不完整、身份不明或目标不可用
     */
    Set<String> privateDatabaseOwners(DatabaseConnectionTarget main, Path dataDirectory, String installationId) throws Exception;

    /**
     * 锁定源并生成一致耐久备份；源结构升级只作用于副本。失败必须释放已取得的全部资源。
     * @param source 当前 provider 的主库或受限 owner 目标
     * @param request 中性结构、全新备份路径及显式可再生对象声明
     * @param control 逐行检查取消及报告进度的宿主控制器
     * @return 由宿主关闭的迁出会话；关闭不删除备份、不提交源
     * @throws Exception 不支持的格式、源占用、未知结构、数据不可表达或备份失败
     */
    DatabaseExportSession exportDatabase(DatabaseConnectionTarget source, MigrationRequest request,
                                         MigrationControl control) throws Exception;

    /**
     * 锁定并检查完全空的目标，开始一个可回滚的迁入事务；失败自行释放资源。
     * @param target 当前 provider 的主库或受限 owner 目标
     * @param schema 中性结构；不支持的合同必须在写入前拒绝
     * @param control 宿主取消及进度控制器
     * @return 由宿主关闭的迁入会话；尚未明确提交时关闭必须中止
     * @throws Exception 非空目标、目标占用、格式不支持或准备失败
     */
    DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
                                         MigrationControl control) throws Exception;

    /**
     * 只读核对源变化凭据，保持与迁出会话同样的身份及完整性规则；不能用连接成功代替数据核验。
     * @param source 原 provider 的精确目标
     * @param revision 迁出时物化的无秘密、版本化变化凭据
     * @throws Exception 源已改变、格式不支持或无法证明相同
     */
    void verifySource(DatabaseConnectionTarget source, String revision) throws Exception;
}
