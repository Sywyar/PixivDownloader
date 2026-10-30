package top.sywyar.pixivdownload.plugin.api.contract.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式选择供 SDK 开发工具生成 JSON Schema 的公开 record。
 * 注解只留在源码中；生成不注册服务、不导出网络接口，也不改变 Java 服务的值边界。
 *
 * <p>生成支持同一源码模块中的非泛型 record、无实例字段的枚举、String、boolean、
 * byte/short/int/long 及其包装类型、UUID、Instant、Duration、float/double、List 和以 String 为键的 Map。
 * UUID、时间与有限浮点使用 {@link ValueEncoding} 固定的无损字符串表示；long 默认保持 JSON 整数，
 * 完整范围需要在属性上显式选择 DECIMAL_STRING。每个 record 属性必须声明 {@link ValueProperty}。
 * Optional、其它时间类型、数组、通配符及任意对象因缺少明确编码而拒绝，作者应先转换为受支持的边界 DTO。
 * 生成物采用 JSON Schema 2020-12；对象拒绝未声明属性，整数保留 Java 类型可表示的范围。
 * 字段的业务范围只取显式注解，Java 构造器中的校验不会成为 Schema；x-encoding 扩展由 SDK 校验器执行。
 * 工具不读取自定义 JSON 序列化器，也不代替宿主的 Java 服务准入校验。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ValueSchema {
}
