package top.sywyar.pixivdownload.migration.database;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;

/** 耐久切换任务与离线 schema 清单共用的中性表达式编码，不接受 Java 类名。 */
public final class MigrationSchemaJson {
    private MigrationSchemaJson() {}
    public static ObjectMapper mapper() {
        return new ObjectMapper().addMixIn(Expression.class, CheckExpression.class)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Expression.Column.class, name = "column"),
            @JsonSubTypes.Type(value = Expression.Value.class, name = "value"),
            @JsonSubTypes.Type(value = Expression.Call.class, name = "call")
    })
    private interface CheckExpression {}
}
