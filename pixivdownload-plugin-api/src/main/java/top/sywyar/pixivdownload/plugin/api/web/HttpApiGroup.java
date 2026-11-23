package top.sywyar.pixivdownload.plugin.api.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 指定 HTTP 参考中的业务分组。同组控制器共用所属模块的 UTF-8 资源包
 * {@code i18n/http/<value>.properties}，语言后缀取自宿主语言目录。
 * 分组标题使用 {@code title}。操作使用 {@code <controllerSimpleName>.<method>.<VERB>.summary}、
 * {@code .description}、{@code .body}、{@code .parameter.<name>}、{@code .success} 或 {@code .response.<status>}。
 * 对 multipart 文件等内联请求属性使用 {@code .body.field.<property>}。映射覆盖多个 HTTP 方法时，动词取 {@code ANY}。
 * 共享参数说明使用 {@code parameter.<name>}。模型说明使用 {@code schema.<component>.title}、
 * {@code .description} 和 {@code .field.<property>}。组件名及 JSON 字段名取自生成的 OpenAPI 文档。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpApiGroup {
    /** {@return 含所属模块命名空间的稳定分组标识} */
    String value();
}
