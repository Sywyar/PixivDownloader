package top.sywyar.pixivdownload.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stable codes among the errors an existing HTTP response can return. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(KnownErrorCodes.Group.class)
public @interface KnownErrorCodes {
    String status();
    String[] value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Group { KnownErrorCodes[] value(); }
}
