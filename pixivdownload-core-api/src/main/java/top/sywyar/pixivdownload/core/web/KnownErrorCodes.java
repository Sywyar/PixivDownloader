package top.sywyar.pixivdownload.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 列出指定 HTTP 响应状态下可能返回的稳定错误码。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(KnownErrorCodes.Group.class)
public @interface KnownErrorCodes {
    /** {@return 这些错误码对应的 HTTP 状态码} */
    String status();
    /** {@return 在该状态下可能返回的错误码} */
    String[] value();

    /** 保存同一声明上重复使用的错误码注解。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Group {
        /** {@return 按响应状态声明的各组错误码} */
        KnownErrorCodes[] value();
    }
}
