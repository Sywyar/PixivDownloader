package top.sywyar.pixivdownload.plugin.api.contract.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * record 属性的 JSON 约束；必填与可空必须明确声明，数值范围与长度不会从业务名称推断。
 * 集合元素默认可空；此注解不在 Java 构造器或服务调用时执行校验。
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface ValueProperty {
    /** @return JSON 对象是否必须包含此属性 */
    boolean required();
    /** @return 属性值是否允许 JSON null；基本类型不能声明为可空 */
    boolean nullable();
    /** @return 集合元素是否可空；只用于 List 元素或 Map 值 */
    boolean elementsNullable() default true;
    /** @return 整数下界的十进制表示，空字符串表示没有额外业务下界 */
    String minimum() default "";
    /** @return 整数上界的十进制表示，空字符串表示没有额外业务上界 */
    String maximum() default "";
    /** @return 字符串最少 Unicode 码点数，-1 表示不声明 */
    int minLength() default -1;
    /** @return 字符串最多 Unicode 码点数，-1 表示不声明 */
    int maxLength() default -1;
    /** @return List 最少元素数，-1 表示不声明 */
    int minItems() default -1;
    /** @return List 最多元素数，-1 表示不声明 */
    int maxItems() default -1;
}
