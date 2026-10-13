package top.sywyar.pixivdownload.plugin.api.storage.relational;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import java.util.List;
import java.util.Objects;

/** 有类型的操作词汇；标识符和值均不能包含可执行 SQL。 */
public sealed interface Expression {
    /** 声明列；table 对应查询中的表名或显式别名。 */
    record Column(String table, String name) implements Expression {
        /**
         * 校验表引用与列名。
         * @param name 符合标识符规则的逻辑名称
         * @param table 当前作用域中已声明的表或引用别名
         */
        public Column { MigrationSchema.identifier(table); MigrationSchema.identifier(name); }
    }
    /** 冲突更新中的传入值，与既有行区分。 */
    record Incoming(String name) implements Expression {
        /**
         * 校验传入列名。
         * @param name 符合标识符规则的逻辑名称
         */
        public Incoming { MigrationSchema.identifier(name); }
    }
    /** 支持 NULL、文本、二进制、64 位整数和有限双精度数；小整数无损提升。 */
    record Value(Object value) implements Expression {
        /**
         * 固定受支持的值，复制二进制并无损提升小整数。
         * @param value 待固定的值
         */
        public Value {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) value = ((Number) value).longValue();
            if (value instanceof byte[] bytes) value = bytes.clone();
            if (value != null && !(value instanceof String || value instanceof Long || value instanceof byte[]
                    || value instanceof Double number && Double.isFinite(number))) throw new IllegalArgumentException("STORAGE_VALUE_INVALID");
        }
        /**
         * 返回固定值；二进制返回独立副本。
         * @return 固定的值；二进制读取为独立副本
         */
        @Override public Object value() { return value instanceof byte[] bytes ? bytes.clone() : value; }
    }
    /** 操作语义跨提供者一致；不支持的操作在写入前拒绝。 */
    record Call(Operator operator, List<Expression> arguments) implements Expression {
        /**
         * 固定参数并校验操作数数量。
         * @param arguments 按操作约定排序的参数
         * @param operator 具有固定语义的操作
         */
        public Call {
            Objects.requireNonNull(operator);
            arguments = List.copyOf(arguments);
            int size = arguments.size();
            boolean valid = switch (operator) {
                case COUNT -> size == 0;
                case IS_NULL, NOT, LOWER_ASCII, TRIM, MAX, MIN, SUM, COUNT_DISTINCT, TEXT, LENGTH, LOWER_HEX -> size == 1;
                case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, LIKE, NULL_IF, MINIMUM,
                     ADD, SUBTRACT, MULTIPLY, RTRIM, EPOCH_MONTH -> size == 2;
                case AND, OR, COALESCE, IN, CONCAT -> size >= 2;
                case SUBSTRING -> size == 3;
            };
            if (!valid) throw new IllegalArgumentException("STORAGE_EXPRESSION_INVALID");
        }
    }
    /** 关联存在性检查仍限制在当前受管存储声明中。 */
    record Exists(Query query) implements Expression {
        /**
         * 固定存在性子查询。
         * @param query 受当前声明范围限制的查询
         */
        public Exists { Objects.requireNonNull(query); }
    }
    /** 标量子查询仅取一行一列；无行时为 NULL，调用方负责声明稳定排序。 */
    record Scalar(Query query) implements Expression {
        /**
         * 校验子查询只投影一列且最多取一行。
         * @param query 受当前声明范围限制的查询
         */
        public Scalar {
            Objects.requireNonNull(query);
            if (query.select().size() != 1 || query.limit() != 1) throw new IllegalArgumentException("STORAGE_SCALAR_INVALID");
        }
    }
    /** 按条件选择两个同类型表达式之一。 */
    record Conditional(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {
        /**
         * 拒绝缺失的条件或分支表达式。
         * @param condition 布尔条件表达式
         * @param whenFalse 条件不成立时的值
         * @param whenTrue 条件成立时的值
         */
        public Conditional { Objects.requireNonNull(condition); Objects.requireNonNull(whenTrue); Objects.requireNonNull(whenFalse); }
    }
    /** LIKE 使用 %、_ 和 ! 转义，并仅折叠 ASCII 大小写；子串位置从 1 起。 */
    enum Operator {
        /** 相等比较。 */
        EQUAL,
        /** 不等比较。 */
        NOT_EQUAL,
        /** 小于比较。 */
        LESS,
        /** 小于或等于比较。 */
        LESS_EQUAL,
        /** 大于比较。 */
        GREATER,
        /** 大于或等于比较。 */
        GREATER_EQUAL,
        /** 条件合取。 */
        AND,
        /** 条件析取。 */
        OR,
        /** 条件取反。 */
        NOT,
        /** 判断值是否为空。 */
        IS_NULL,
        /** 判断首值是否属于后续值集合。 */
        IN,
        /** 使用百分号、下划线和感叹号转义进行模式匹配，仅折叠 ASCII 大小写。 */
        LIKE,
        /** 仅将 ASCII 大写字母转为小写。 */
        LOWER_ASCII,
        /** 移除文本两端的空格。 */
        TRIM,
        /** 两值相等时返回空值，否则返回首值。 */
        NULL_IF,
        /** 返回首个非空值。 */
        COALESCE,
        /** 无参数聚合，统计行数。 */
        COUNT,
        /** 统计不同的非空值数量。 */
        COUNT_DISTINCT,
        /** 聚合最大非空值。 */
        MAX,
        /** 聚合最小非空值。 */
        MIN,
        /** 聚合非空值之和。 */
        SUM,
        /** 取两个非空标量中的较小值；任一为空时返回空值。 */
        MINIMUM,
        /** 转换为文本表示。 */
        TEXT,
        /** 返回文本字符数。 */
        LENGTH,
        /** 文本仅含 ASCII 0-9、a-f；空串为真，NULL 仍为 NULL，长度由调用方另行约束。 */
        LOWER_HEX,
        /** 移除文本末尾属于给定字符集合的字符。 */
        RTRIM,
        /** 按从一开始的位置和长度截取文本。 */
        SUBSTRING,
        /** 按顺序连接文本。 */
        CONCAT,
        /** 数值相加，整数结果必须无损。 */
        ADD,
        /** 数值相减，整数结果必须无损。 */
        SUBTRACT,
        /** 数值相乘，整数结果必须无损。 */
        MULTIPLY,
        /** 将毫秒时间戳按 IANA 时区或固定偏移格式化为年月。 */
        EPOCH_MONTH
    }

    /**
     * 创建声明列引用。
     * @param name 符合标识符规则的逻辑名称
     * @param table 当前作用域中已声明的表或引用别名
     * @return 列引用表达式
     */
    static Column column(String table, String name) { return new Column(table, name); }
    /**
     * 创建绑定值，不将文本解释为 SQL。
     * @param value 待固定的值
     * @return 固定的值；二进制读取为独立副本
     */
    static Value value(Object value) { return new Value(value); }
    /**
     * 创建具有固定操作语义的表达式。
     * @param arguments 按操作约定排序的参数
     * @param operator 具有固定语义的操作
     * @return 操作表达式
     */
    static Call call(Operator operator, Expression... arguments) { return new Call(operator, List.of(arguments)); }
}
