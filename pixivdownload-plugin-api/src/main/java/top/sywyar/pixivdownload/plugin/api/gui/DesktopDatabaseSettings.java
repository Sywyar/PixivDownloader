package top.sywyar.pixivdownload.plugin.api.gui;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 进程级宿主拥有的主数据库配置语义；后端停止时仍可使用，不由插件发布或替换。
 * 检查与迁移使用宿主通用编排，不暴露驱动、凭据或界面组件。
 */
public interface DesktopDatabaseSettings {
    /** 参与数据库连接检查和迁移确认的宿主配置键。 */
    java.util.Set<String> CONNECTION_KEYS = java.util.Set.of("database.provider", "database.engine",
            "database.jdbc-url", "database.username", "database.password");

    /**
     * 将受控诊断码转换为核心文案键；未知值不作为文案或键的一部分回显。
     * @param code 连接检查的 SQLSTATE、SQLSTATE_ 前缀码或迁移诊断码
     * @return 不含凭据和驱动正文的核心 i18n key
     */
    static String failureMessageKey(String code) {
        String value = code == null ? "" : code;
        if (value.startsWith("SQLSTATE_")) value = value.substring("SQLSTATE_".length());
        String reason = switch (value) {
            case "28P01" -> "authentication";
            case "28000" -> "authorization";
            case "3D000" -> "database-missing";
            case "3F000", "DATABASE_SCHEMA_MISSING" -> "schema-missing";
            case "42501", "25006" -> "permission";
            case "53300", "53400", "57P03", "MIGRATION_BUSY", "MIGRATION_SOURCE_STILL_RUNNING",
                    "DATABASE_BUSY", "SOURCE_BUSY" -> "busy";
            case "DATABASE_USERNAME_REQUIRED" -> "username-required";
            case "DATABASE_CONFIGURATION_INVALID", "DATABASE_LOCAL_FILE_UNSUPPORTED" -> "configuration";
            case "DATABASE_PRODUCT_UNSUPPORTED" -> "product";
            case "0A000" -> "unsupported";
            case "DATABASE_ENCODING_UNSUPPORTED" -> "encoding";
            case "DATABASE_TARGET_MISMATCH", "MIGRATION_IDENTITY_CHANGED" -> "target-mismatch";
            case "DATABASE_PROVIDER_UNAVAILABLE", "DATABASE_PROVIDER_NOT_SELECTED", "MIGRATION_UNAVAILABLE" -> "provider-unavailable";
            case "DATABASE_FILE_NOT_FOUND", "DATABASE_FILE_UNAVAILABLE", "DATABASE_STORAGE_PREPARATION_FAILED" -> "file";
            case "TARGET_NOT_EMPTY", "TARGET_REPLACEMENT_UNSUPPORTED" -> "target-not-empty";
            case "TARGET_NOT_WRITABLE" -> "target-read-only";
            case "SOURCE_MISSING", "SOURCE_CORRUPT" -> "source-unavailable";
            case "BACKUP_EXISTS" -> "backup-exists";
            case "DATA_MISMATCH", "MIGRATION_NOT_COMMITTED" -> "data-mismatch";
            case "MIGRATION_SOURCE_CHANGED" -> "source-changed";
            case "CONFIG_CHANGED", "MIGRATION_CONFIG_CHANGED", "MIGRATION_CONFIG_PATH_CHANGED",
                    "MIGRATION_PLAN_CHANGED", "MIGRATION_PLUGINS_CHANGED", "MIGRATION_STORES_CHANGED" -> "plan-changed";
            case "CONFIG_OVERRIDDEN" -> "overridden";
            case "SCHEMA_MISMATCH", "MIGRATION_SCHEMA_MISMATCH", "DATABASE_SCHEMA_UNSUPPORTED",
                    "STORAGE_SCHEMA_MISMATCH", "STORAGE_UNSAFE_COLUMN_CHANGE", "UNDECLARED_OBJECT",
                    "UNDECLARED_TABLE", "REBUILD_OBJECT_MISSING", "NOT_REGENERABLE",
                    "PRIVATE_SCHEMA_MISSING", "SCHEMA_CHECK_UNAVAILABLE", "COLUMN_TYPE",
                    "SEQUENCE_MISSING", "SEQUENCE_UNSUPPORTED", "SEQUENCE_MISMATCH" -> "schema";
            case "DATABASE_PRIVATE_STORAGE_UNAVAILABLE" -> "private-storage";
            case "DATABASE_RESTART_REQUIRED", "RESTART_FAILED" -> "restart";
            case "MIGRATION_CANCELLED", "MIGRATION_INTERRUPTED" -> "interrupted";
            default -> value.matches("08[A-Z0-9]{3}") ? "connection"
                    : value.matches("28[A-Z0-9]{3}") ? "authorization" : "unknown";
        };
        return "gui.config.database.error." + reason;
    }
    /** {@return 本进程已加载且可选择的数据库插件；新安装的重启型插件需先完整重启} */
    List<ProviderChoice> providers();

    /**
     * 数据库插件身份与宿主支持的 SQL 方言；不包含插件对象或加载器。
     * @param owner 提供者的插件身份
     * @param databaseId 数据库类型标识
     * @param connectionFields 提供者声明的连接字段
     */
    record ProviderChoice(String owner, String databaseId,
                          List<top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField> connectionFields) {
        /**
         * 防御性复制连接字段列表。
         * @param owner 提供者的插件身份
         * @param databaseId 数据库类型标识
         * @param connectionFields 提供者声明的连接字段
         */
        public ProviderChoice { connectionFields = List.copyOf(connectionFields); }
    }

    /**
     * 校验并规范化待保存的宿主配置增量，不连接数据库；可能核验本地迁移文件，必须在 UI 线程外调用。
     * 密码键缺席表示保留，显式空值表示清除；调用方不得传入显示掩码。
     * 返回值仍通过统一配置事务保存，失败不得持久化部分配置。
     * @param updates 真实草稿增量
     * @return 校验后的增量，包含所选提供者清除的不适用字段
     * @throws IOException 配置不可读或目标非法
     */
    Map<String, String> prepare(Map<String, String> updates) throws IOException;

    /**
     * 检查草稿目标，必须在 UI 线程之外调用。检查使用独立、有界连接并负责关闭；
     * 不初始化业务 schema，不创建 SQLite 文件，不修改配置或运行目标。
     * @param updates 同 prepare 的草稿增量，不应用环境或启动参数覆盖
     * @return 不含凭据或驱动异常正文的结果
     * @throws IOException 配置不可读或目标非法
     */
    CheckResult check(Map<String, String> updates) throws IOException;

    /**
     * @return 运行目标与按真实启动优先级解析的下次启动目标；无后端时 activeTarget 为空
     * @throws IOException 无法读取配置
     */
    State state() throws IOException;

    /** {@return 数据库迁移使用说明地址，只在用户点击时打开} */
    URI migrationGuide();

    /**
     * 只读检查并返回待确认清单；确认前不持久化配置或创建目标存储。
     * @param updates 待迁移目标的配置增量，密码语义与 {@link #prepare(Map)} 一致
     * @return 绑定当前配置、结构和逻辑库清单的确认计划
     * @throws IOException 迁移不可用、配置不可读或目标非法
     */
    default MigrationPlan previewMigration(Map<String, String> updates) throws IOException {
        throw new IOException("MIGRATION_UNAVAILABLE");
    }

    /**
     * 确认精确计划并安排完整进程重启；重启后迁移，全部核验成功才切换配置。
     * @param token 只读预检返回的精确计划凭据
     * @throws IOException 迁移不可用、计划失效或无法安排重启
     */
    default void startMigration(String token) throws IOException { throw new IOException("MIGRATION_UNAVAILABLE"); }

    /**
     * 确认计划后先通过原配置事务保存其它设置，再安排迁移；数据库设置仍只在全部核验后提交。
     * @param token 只读预检返回的精确计划凭据
     * @param saveOtherSettings 通过原配置事务保存非数据库设置的操作
     * @throws IOException 迁移不可用、计划失效、其它设置保存失败或无法安排重启
     */
    default void startMigration(String token, DesktopUiHost.IoOperation saveOtherSettings) throws IOException {
        throw new IOException("MIGRATION_UNAVAILABLE");
    }

    /**
     * 最近一次耐久迁移结果，不含密码、行内容或底层异常。
     * @return 最近一次迁移状态；没有迁移记录时返回空状态
     * @throws IOException 无法读取迁移记录
     */
    default MigrationState migrationState() throws IOException { return new MigrationState("", "", List.of()); }

    /**
     * 一个逻辑库的检查或执行事实。
     * @param owner 主库为 main，私库为 private: 加插件 owner
     * @param source 不含凭据的迁出目标说明
     * @param target 不含凭据的迁入目标说明
     * @param phase 检查或执行阶段的机器码
     * @param rebuildObjects 需要确认重建的辅助对象名
     */
    record MigrationStore(String owner, String source, String target, String phase, List<String> rebuildObjects) {
        /**
         * 防御性复制辅助对象列表。
         * @param owner 逻辑库的宿主或插件身份
         * @param source 迁出目标说明
         * @param target 迁入目标说明
         * @param phase 检查或执行阶段的机器码
         * @param rebuildObjects 需要确认重建的辅助对象名
         */
        public MigrationStore { rebuildObjects = List.copyOf(rebuildObjects); }
    }
    /**
     * token 绑定本次配置、结构和清单；任一阻断项存在时不得确认。
     * @param token 本次预检计划的确认凭据
     * @param ready 是否允许确认迁移
     * @param stores 纳入迁移的逻辑库检查结果
     * @param failure 阻断原因的受控机器码
     */
    record MigrationPlan(String token, boolean ready, List<MigrationStore> stores, String failure) {
        /**
         * 防御性复制逻辑库清单。
         * @param token 本次预检计划的确认凭据
         * @param ready 是否允许确认迁移
         * @param stores 逻辑库检查结果
         * @param failure 阻断原因的受控机器码
         */
        public MigrationPlan { stores = List.copyOf(stores); }
    }
    /**
     * status 和 failure 为稳定机器码，由界面本地化。
     * @param status 最近一次迁移的状态码，无记录时为空
     * @param failure 失败原因的受控机器码，无失败时为空
     * @param stores 各逻辑库的迁移结果
     */
    record MigrationState(String status, String failure, List<MigrationStore> stores) {
        /**
         * 防御性复制逐库结果。
         * @param status 最近一次迁移的状态码
         * @param failure 失败原因的受控机器码
         * @param stores 各逻辑库的迁移结果
         */
        public MigrationState { stores = List.copyOf(stores); }
    }

    /** 检查结果；未创建的 SQLite 文件不能冒充连接成功。 */
    enum Outcome {
        /** 已连接并核对目标身份。 */
        CONNECTED,
        /** SQLite 文件尚未创建，检查未创建文件。 */
        NOT_CREATED,
        /** 连接或目标检查失败。 */
        FAILED,
        /** 进程内已有连接检查正在进行。 */
        BUSY
    }

    /**
     * @param outcome 检查结果
     * @param target 不含用户名、密码和 URL 参数的目标说明
     * @param sqlState 受控 SQLSTATE 或诊断码，使用 failureMessageKey 转换为用户文案
     */
    record CheckResult(Outcome outcome, String target, String sqlState) {}

    /**
     * @param activeTarget 当前运行连接池的目标说明，后端停止时为空
     * @param nextTarget 已保存配置按启动优先级解析的目标说明，非法时为空
     * @param overriddenKeys 被更高优先级配置覆盖的键名，不含值
     * @param restartRequired 已保存连接配置与正在使用的连接配置不同
     */
    record State(String activeTarget, String nextTarget, List<String> overriddenKeys, boolean restartRequired) {
        /**
         * 防御性复制覆盖键名列表。
         * @param activeTarget 当前运行目标
         * @param nextTarget 下次启动目标
         * @param overriddenKeys 被覆盖的配置键
         * @param restartRequired 是否需要进程重启
         */
        public State { overriddenKeys = List.copyOf(overriddenKeys); }
    }
}
