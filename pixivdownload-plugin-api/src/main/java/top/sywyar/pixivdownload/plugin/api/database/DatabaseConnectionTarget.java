package top.sywyar.pixivdownload.plugin.api.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 由选定 provider 拥有的固定目标。宿主管理池与连接租约；此接口不向普通业务插件发布。
 * 缺席、已关闭或失败时必须显式拒绝，不返回 null 连接、假查询结果或假提交。
 */
public interface DatabaseConnectionTarget {
    /**
     * 维护窗口内刷新当前存储的查询规划统计，不删除业务数据，不调度后台任务。
     * @param connection 调用方持有并管理事务的连接
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    void optimize(Connection connection) throws SQLException;
    /**
     * 在调用方事务中建表、安全补列、建索引与升级明确声明的旧 CHECK；保留数据且不提交。
     * CHECK 升级仅匹配 previousChecks，全部旧记录须满足新约束；失败须回滚结构与数据变更。
     * strict 为真时须核验声明表的完整结构与约束，任何不安全变更均拒绝。
     * 非严格模式保留附加对象，不以迁移可表达范围限制普通使用；跳过的不安全补列须逐列报告。
     * 调用方在启动或工具执行前仍须核验所需字段、主键和索引，不能将部分补齐视为可用。
     * @param connection 调用方持有并管理事务的连接
     * @param schema 当前存储作用域的结构声明
     * @param strict 是否拒绝无法完整满足声明的既有结构
     * @return 实际结构变化和未能安全补齐的列
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    SchemaApplyResult applySchema(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema,
            boolean strict) throws SQLException;

    /**
     * 将中性期望类型映射到本提供者用于比较的物理类型，不执行数据库操作。
     * @param schema 当前存储作用域的结构声明
     * @return 用于物理结构核验的提供者类型投影
     */
    top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
            top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema schema);

    /**
     * 在同一连接内读取普通表结构；提供者负责系统目录与辅助对象的分类。
     * 不因附加对象无法无损迁移而拒绝普通自省；不可表示的索引保留身份且不能匹配普通索引声明。
     * @param connection 调用方持有并管理事务的连接
     * @return 按表名组织的当前普通表结构
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    java.util.Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
            readSchema(Connection connection) throws SQLException;

    /** 幂等结构应用的实际变化；跳过的列使用 table.column 标识。 */
    record SchemaApplyResult(int createdTables, int addedColumns, int createdIndexes, java.util.List<String> skippedColumns) {
        /**
         * 固定未能安全补齐的列清单。
         * @param addedColumns 实际补齐的列数
         * @param createdIndexes 实际新建的索引数
         * @param createdTables 实际新建的表数
         * @param skippedColumns 非严格模式下无法安全补齐的 table.column 清单
         */
        public SchemaApplyResult { skippedColumns = java.util.List.copyOf(skippedColumns); }
    }

    /**
     * 在调用方事务内初始化或安全演进声明的私有结构。
     * 未知对象、有损变更和不支持的事务性 DDL 必须在修改前拒绝，不提交、不删除数据、不切换目标。
     * @param connection 调用方持有并管理事务的连接
     * @param schema 当前存储作用域的结构声明
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    void initializeStorage(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException;

    /**
     * 创建同步且受作用域约束的中性会话，连接与提交始终由宿主管理。
     * @param connection 调用方持有并管理事务的连接
     * @param schema 当前存储作用域的结构声明
     * @return 仅在当前线程有效、由调用方关闭的会话
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession storageSession(Connection connection,
            top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException;
    /** {@return 规范化、稳定的物理目标身份（文件或服务端、数据库、schema），不含凭据及连接选项} */
    String description();

    /** {@return 文件引擎的实际文件；远程目标为空，不表示数据库不可用} */
    Optional<Path> localFile();

    /**
     * 打开一条独立连接；不执行应用 schema 初始化或数据迁移。
     * @param mode 普通连接、只读检查或必须存在的迁移源
     * @return 由调用方关闭的真实 JDBC 连接
     * @throws SQLException 目标不可用；异常不得包含密码、驱动原文或敏感 cause
     */
    Connection openConnection(OpenMode mode) throws SQLException;

    /**
     * 在有界时间内检查并关闭独立连接，不修改配置、池或业务 schema，也不能创建数据库文件。
     * @throws SQLException 检查失败；只输出安全机器码及 SQLState
     */
    void checkConnection() throws SQLException;

    /**
     * 核验借用连接的物理目标与当前固定目标一致。
     * @param connection 调用方持有的连接，此方法不关闭它
     * @throws SQLException 目标不匹配或不能证明
     */
    void requireConnectionTarget(Connection connection) throws SQLException;

    /**
     * 在 JDBC 事务已开始、业务 SQL 尚未执行时应用方言的写入资格规则。
     * @param connection 当前事务连接
     * @param readOnly 是否为只读事务
     * @throws SQLException 事务准备失败，不得继续执行业务
     */
    void prepareTransaction(Connection connection, boolean readOnly) throws SQLException;

    /**
     * 解析当前 provider 下独立的 owner 存储，不执行 DDL、不打开连接。
     * 普通连接只能访问给定 owner，不能复用主库的高权限连接。
     * @param settings 宿主绑定的 owner、目录及私有认证材料
     * @return 相同生命周期内的私有目标；检查不得初始化存储
     * @throws SQLException 无法支持所请求的隔离存储
     */
    DatabaseConnectionTarget privateStorage(DatabasePrivateStorageSettings settings) throws SQLException;

    /** 连接用途，不改变固定目标或 owner。 */
    enum OpenMode {
        /** 正常访问，文件引擎允许首次创建数据库。 */
        READ_WRITE,
        /** 只读检查，不创建数据库或设置持久化 journal 模式。 */
        READ_ONLY,
        /** 必须存在的读写源，不创建数据库或改变 journal 模式。 */
        EXISTING
    }
}
