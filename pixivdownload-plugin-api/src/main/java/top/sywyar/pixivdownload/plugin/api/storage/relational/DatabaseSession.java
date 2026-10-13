package top.sywyar.pixivdownload.plugin.api.storage.relational;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 对当前 owner 声明表执行数据库无关的操作，值沿用迁移类型合同。
 * 提供者统一移除文本参数中的 U+0000；其他字符、NULL、空串与 BLOB 保持原义。
 * 插入、更新、冲突更新与条件参数采用同一规则，业务调用方无需另行清洗。
 * 会话只供当前线程同步使用，外层回调结束后失效；操作失败后不能继续提交或执行依赖该写入的文件、网络副作用。
 */
public interface DatabaseSession extends AutoCloseable {
    /**
     * 初始化关联清理规则；不支持时在任何写入前以 0A000 拒绝，不能以空实现代替。
     * @param cleanup 同语句关联清理规则
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    void initializeCleanup(StorageFeatures.Cleanup cleanup) throws SQLException;
    /**
     * 初始化可再生正文索引。辅助失败仍会使本次事务失败，应以独立事务或保存点隔离。
     * @param index 正文索引声明
     * @return 实际采用的索引策略
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    StorageFeatures.TextIndexMode initializeTextIndex(StorageFeatures.TextIndex index) throws SQLException;
    /**
     * 同步单个主键；key 为 null 时回收不可见行并回填缺失索引，不修改权威正文。
     * @param index 正文索引声明
     * @param key 待同步的主键；null 表示回收不可见行并回填缺失索引
     * @return 实际采用的索引策略
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    StorageFeatures.TextIndexMode refreshTextIndex(StorageFeatures.TextIndex index, Long key) throws SQLException;
    /**
     * 字面子串检索；至少三个字符时 Unicode 大小写折叠，短词仅折叠 ASCII，不解释通配符。
     * @param index 正文索引声明
     * @param term 按字面解释的搜索文本
     * @return 匹配的主记录身份
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    List<Long> searchText(StorageFeatures.TextIndex index, String term) throws SQLException;
    /**
     * 任一操作失败后返回 true，捕获异常不能恢复该事务的可提交状态。
     * @return 是否已经发生使事务不可提交的操作失败
     */
    boolean rollbackOnly();
    /** 使会话失效；不提交或关闭宿主持有的连接。 */
    @Override void close();
    /**
     * 执行有界查询，返回按投影名组织的不可变行。
     * @param query 受当前声明范围限制的查询
     * @return 按投影名称组织的不可变结果行
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    List<Map<String, Object>> query(Query query) throws SQLException;
    /**
     * 逐行读取同一查询快照；回调返回 false 时立即停止并释放游标，不累计未消费的行。
     * @param query 受当前声明范围限制的查询
     * @param visitor 同步逐行消费回调
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    void scan(Query query, RowVisitor visitor) throws SQLException;
    /** 每行为不可变值；返回 false 提前结束，异常使当前事务不可提交。 */
    @FunctionalInterface interface RowVisitor {
        /**
         * 消费当前行。
         * @param row 按投影名组织的不可变行，允许空值
         * @return 是否继续读取后续行
         * @throws SQLException 消费失败，本次事务必须回滚
         */
        boolean visit(Map<String, Object> row) throws SQLException;
    }
    /**
     * IGNORE 只忽略唯一键冲突；显式写入自增主键后，生成器不得再分配已占用的身份。
     * @param conflict 唯一键冲突处理规则
     * @param table 当前作用域中已声明的表或引用别名
     * @param values 按声明列名组织的写入值，允许受声明支持的空值
     * @return 实际受影响的行数
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    int insert(String table, Map<String, Object> values, Conflict conflict) throws SQLException;
    /**
     * 插入并返回数据库生成的单列整数主键；冲突必须失败，不复用删除过的自增身份。
     * @param key 已声明的身份列
     * @param table 当前作用域中已声明的表或引用别名
     * @param values 按声明列名组织的写入值，允许受声明支持的空值
     * @return 本次插入生成的整数主键
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    long insertGenerated(String table, Map<String, Object> values, String key) throws SQLException;
    /**
     * 按查询投影名称写入同库声明列；数据库内批量执行，冲突规则与单行写入一致。
     * @param conflict 唯一键冲突处理规则
     * @param query 受当前声明范围限制的查询
     * @param table 当前作用域中已声明的表或引用别名
     * @return 实际受影响的行数
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    int insertFrom(String table, Query query, Conflict conflict) throws SQLException;
    /**
     * 更新满足条件的行；必须显式提供条件。
     * @param changes 按列名组织的更新表达式
     * @param table 当前作用域中已声明的表或引用别名
     * @param where 匹配条件，必须显式提供
     * @return 实际更新的行数
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    int update(String table, Map<String, Expression> changes, Expression where) throws SQLException;
    /**
     * 原子更新并返回本次实际修改后的列；未命中返回空列表，不得另行查询拼接 CAS 结果。
     * @param changes 按列名组织的更新表达式
     * @param returning 需要返回的修改后列名
     * @param table 当前作用域中已声明的表或引用别名
     * @param where 匹配条件，必须显式提供
     * @return 本次原子更新实际命中的行及修改后列值
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    List<Map<String, Object>> updateReturning(String table, Map<String, Expression> changes, Expression where,
                                              List<String> returning) throws SQLException;
    /**
     * 删除满足条件的行；必须显式提供条件。
     * @param table 当前作用域中已声明的表或引用别名
     * @param where 匹配条件，必须显式提供
     * @return 实际删除的行数
     * @throws java.sql.SQLException 数据库不可用、能力不支持或执行失败；当前事务不得继续提交
     */
    int delete(String table, Expression where) throws SQLException;

    /** IGNORE 的空键表示任意唯一键；UPDATE 必须指定已声明的唯一键。 */
    record Conflict(Mode mode, List<String> keys, Map<String, Expression> changes) {
        /**
         * 固定冲突规则；更新必须指定唯一键和非空变更。
         * @param changes 按列名组织的更新表达式
         * @param keys 冲突目标唯一键；忽略模式下可为空
         * @param mode 冲突处理方式
         */
        public Conflict {
            java.util.Objects.requireNonNull(mode);
            keys = List.copyOf(keys);
            changes = Map.copyOf(changes);
            if (mode == Mode.UPDATE && (keys.isEmpty() || changes.isEmpty())
                    || mode != Mode.UPDATE && !changes.isEmpty()) throw new IllegalArgumentException("STORAGE_CONFLICT_INVALID");
        }
        /**
         * 创建遇到冲突即失败的规则。
         * @return 失败冲突规则
         */
        public static Conflict error() { return new Conflict(Mode.ERROR, List.of(), Map.of()); }
        /**
         * 创建仅忽略指定唯一键冲突的规则；空键集合表示任意唯一键。
         * @param keys 冲突目标唯一键；忽略模式下可为空
         * @return 忽略冲突规则
         */
        public static Conflict ignore(String... keys) { return new Conflict(Mode.IGNORE, List.of(keys), Map.of()); }
    }
    /** 唯一键冲突的处理方式。 */
    enum Mode {
        /** 拒绝冲突并回滚事务。 */
        ERROR,
        /** 保留已有行，仅忽略声明范围内的唯一键冲突。 */
        IGNORE,
        /** 对指定唯一键命中的既有行应用更新表达式。 */
        UPDATE
    }
    /** 回调在同一连接上执行，抛出异常时回滚全部写入。 */
    @FunctionalInterface interface Work<T> {
        /**
         * 在受管会话内同步执行操作。
         * @param session 当前线程和回调内有效的会话
         * @return 回调结果，不得携带仍需使用会话的延迟操作
         * @throws SQLException 操作失败，本次事务必须回滚
         */
        T run(DatabaseSession session) throws SQLException;
    }
}
