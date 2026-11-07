package top.sywyar.pixivdownload.migration.database;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;

/** 耐久切换任务与离线 schema 清单共用的中性表达式编码，不接受 Java 类名。 */
public final class MigrationSchemaJson {
    private MigrationSchemaJson() {}
    public static ObjectMapper mapper() {
        return JsonMapper.builderWithJackson2Defaults()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .addMixIn(Expression.class, CheckExpression.class)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addHandler(new DeserializationProblemHandler() {
                    @Override
                    public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                            ValueDeserializer<?> deserializer, Object target, String property) {
                        // 旧清单中的空 SQL 槽不携带约束；非空值必须拒绝，不能丢弃后继续迁移。
                        return (target instanceof TableSpec || target == TableSpec.class)
                                && property.equals("checkExpression") && parser.currentToken() == JsonToken.VALUE_NULL;
                    }
                })
                .enable(tools.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Expression.Column.class, name = "column"),
            @JsonSubTypes.Type(value = Expression.Value.class, name = "value"),
            @JsonSubTypes.Type(value = Expression.Call.class, name = "call")
    })
    private interface CheckExpression {}
}
