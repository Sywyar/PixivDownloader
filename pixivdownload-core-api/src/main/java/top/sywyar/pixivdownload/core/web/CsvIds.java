package top.sywyar.pixivdownload.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记以逗号分隔的正整数 ID 参数，供 HTTP 接口参考描述其格式。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CsvIds {}
