package top.sywyar.pixivdownload.plugin.api.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明 MVC 请求正文的字节预算；宿主在 DTO 反序列化前强制执行。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface RequestBodyLimit {
    /**
     * 返回该参数允许读取的最大字节数。
     * @return 正文预算，单位为字节
     */
    int value();
}
